package br.com.bellube.sankhya.eventos.VendaPixAdianta.async;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.CancelamentoHelper;
import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.util.JapeSessionContext;

import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduler embedded para reconciliacao automatica de adiantamentos PIX orfaos.
 *
 * INDEPENDENTE do Cuckoo (Sankhya), cron Linux, ou SQL Agent. Roda dentro da
 * JVM do WildFly como um ScheduledExecutorService daemon. Inicializa
 * automaticamente quando a classe e carregada (static block) - basta o JAR
 * estar deployado e algum codigo referenciar esta classe (forcado em
 * VendaPixAdiantaEvent).
 *
 * Caracteristicas:
 *   - Pool de 1 thread daemon (nao impede shutdown da JVM)
 *   - Primeira execucao 5 minutos apos o startup (deixa o servidor estabilizar)
 *   - Repeticao a cada 30 minutos com taxa fixa
 *   - Tolerante a falhas (exception capturada e logada, ciclo continua)
 *   - Idempotente (rodar 2x nao causa estrago - o filtro por DHMOV/PROVISAO impede)
 *
 * Cobertura combinada:
 *   1. afterUpdate (sincrono - tentativa imediata)
 *   2. afterDelete (sincrono - quando pedido e deletado)
 *   3. ESTE scheduler (eventual - safety net dentro da JVM)
 */
public class ReconciliacaoScheduler {

    private static final Logger LOGGER = Logger.getLogger("ReconciliacaoScheduler");

    private static final int INITIAL_DELAY_MINUTES = 2;
    private static final int PERIOD_MINUTES = 5;
    private static final int DIAS_LOOKBACK = 30;
    /** Grace em MINUTOS (era 24 HORAS antes do V12). Mais responsivo para
     * capturar cancelamentos via SP customizada (AD_MARCA_NAO_PENDENTE)
     * que faz UPDATE direto em TGFITE/TGFCAB sem disparar JAPE event.
     * 5 min eh seguro para nao pegar adiantamento durante a criacao do
     * pedido (janela onde TGFVAR ainda pode ser criado). */
    private static final int GRACE_MINUTOS = 5;
    private static final BigDecimal CODUSU_SISTEMA = new BigDecimal("376");

    /** Marker em System.properties que sobrevive a redeploys de classloader.
     * Sem isso, cada redeploy cria uma nova instancia do scheduler em paralelo
     * com as anteriores (problema observado em producao 04/05-05/05/2026 onde
     * 2 schedulers rodavam simultaneamente apos 2 deploys do JAR). */
    private static final String SYS_PROP_SCHEDULER_OWNER = "br.com.bellube.VendaPix.ReconciliacaoScheduler.owner";

    /** ID unico desta instancia (geracao do classloader) - usado pra
     * identificar qual instance "venceu" a corrida pelo lock no system property. */
    private static final String INSTANCE_ID = String.valueOf(System.currentTimeMillis())
            + "_" + Integer.toHexString(System.identityHashCode(ReconciliacaoScheduler.class));

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VendaPix-Reconciliacao");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private static volatile boolean iniciado = false;
    private static volatile boolean inibido = false;

    static {
        iniciar();
    }

    private ReconciliacaoScheduler() {}

    /**
     * Inicializa o scheduler (chamado automaticamente pelo static block).
     * Idempotente: chamadas multiplas nao criam jobs duplicados.
     *
     * <p>Garantia anti-redeploy: usa um marker em System.properties que
     * sobrevive a recargas de classloader. Se ja existe outra instancia
     * registrada, esta e ABORTADA (silenciosamente) - apenas a primeira
     * a vencer a corrida fica ativa.
     */
    public static synchronized void iniciar() {
        if (iniciado || inibido) return;
        try {
            // ESTRATEGIA: last-write-wins com auto-suicidio.
            // Sempre toma posse no System.property (sobrescreve owner anterior).
            // Cada ciclo verifica se ainda eh dono - se outro instance tomou posse depois,
            // este se auto-encerra. Garante que apenas UM scheduler executa de fato,
            // mas SEM bloquear deploys novos quando o anterior travou/morreu.
            String donoAnterior = System.getProperty(SYS_PROP_SCHEDULER_OWNER);
            System.setProperty(SYS_PROP_SCHEDULER_OWNER, INSTANCE_ID);

            if (donoAnterior != null && !donoAnterior.isEmpty()) {
                LOGGER.info("[ReconciliacaoScheduler] Tomando posse - owner anterior=" + donoAnterior
                        + " (se ainda estiver vivo, ira se auto-encerrar no proximo ciclo)");
            }

            scheduler.scheduleAtFixedRate(
                    new ReconciliacaoTask(),
                    INITIAL_DELAY_MINUTES,
                    PERIOD_MINUTES,
                    TimeUnit.MINUTES
            );
            iniciado = true;
            LOGGER.info("[ReconciliacaoScheduler] Iniciado - owner=" + INSTANCE_ID
                    + ", delay=" + INITIAL_DELAY_MINUTES
                    + "min, periodo=" + PERIOD_MINUTES + "min, lookback=" + DIAS_LOOKBACK
                    + "d, grace=" + GRACE_MINUTOS + "min");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ReconciliacaoScheduler] Falha ao iniciar scheduler", e);
        }
    }

    /**
     * Forca uma execucao imediata da reconciliacao (util para testes ou
     * triggers manuais via outras rotas). Roda no thread chamador.
     */
    public static int executarAgora() {
        return new ReconciliacaoTask().reconciliar();
    }

    public static void shutdown() {
        LOGGER.info("[ReconciliacaoScheduler] Finalizando scheduler");
        scheduler.shutdown();
    }

    /**
     * Task de reconciliacao. Abre uma sessao JAPE com transacao e chama
     * CancelamentoHelper.reconciliarOrfaos. Toda exception e capturada
     * para nao matar o thread - o proximo ciclo tenta de novo.
     */
    private static class ReconciliacaoTask implements Runnable {

        @Override
        public void run() {
            long inicio = System.currentTimeMillis();
            try {
                // Auto-suicidio: se outro deploy tomou posse depois de mim,
                // este scheduler antigo deve sair de cena imediatamente.
                String donoAtual = System.getProperty(SYS_PROP_SCHEDULER_OWNER);
                if (donoAtual != null && !INSTANCE_ID.equals(donoAtual)) {
                    LOGGER.info("[ReconciliacaoScheduler] Esta instancia (" + INSTANCE_ID
                            + ") perdeu posse para " + donoAtual + " - auto-encerrando para nao duplicar trabalho");
                    scheduler.shutdownNow();
                    return;
                }
                // Heartbeat SEMPRE em INFO - confirma que o scheduler esta vivo
                // mesmo quando nao acha orfaos (sem isso e impossivel saber se
                // o thread daemon esta rodando entre redeploys/restarts do servidor).
                LOGGER.info("[ReconciliacaoScheduler] >>> Iniciando ciclo (owner=" + INSTANCE_ID + ")");
                int cancelados = reconciliar();
                long duracao = System.currentTimeMillis() - inicio;
                if (cancelados > 0) {
                    LOGGER.info("[ReconciliacaoScheduler] <<< Ciclo concluido em " + duracao
                            + "ms - " + cancelados + " adiantamento(s) orfao(s) cancelado(s)");
                } else {
                    LOGGER.info("[ReconciliacaoScheduler] <<< Ciclo concluido em " + duracao
                            + "ms - nenhum orfao detectado");
                }
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[ReconciliacaoScheduler] Erro nao tratado no ciclo - proximo ciclo seguira normalmente", t);
            }
        }

        int reconciliar() {
            JapeSession.SessionHandle session = null;
            Object prevUsu = null;
            boolean usuApplied = false;
            try {
                session = JapeSession.open();
                // Identifica como usuario sistema (CODUSU=376 - ADIANTAPIX)
                try {
                    prevUsu = JapeSessionContext.getProperty("usuario_logado");
                    JapeSessionContext.putProperty("usuario_logado", CODUSU_SISTEMA);
                    usuApplied = true;
                } catch (Exception ignore) {}

                final int[] resultado = new int[1];
                session.execWithTX(() -> {
                    // PARTE 1 (V12): cancela adiantamentos ORFAOS (NUNOTA deletada/cancelada/etc)
                    int cancelados = CancelamentoHelper.reconciliarOrfaos(DIAS_LOOKBACK, GRACE_MINUTOS);

                    // PARTE 2 (V16 SAFETY NET): completa DELETE FISICO de adiantamentos
                    // que ficaram em SOFT CANCEL (porque o polling de 30s no clique deu
                    // TIMEOUT) cujo boleto Itau JA foi cancelado pelo worker async
                    // (TGFRAF SEQ_BAIXA STATUS='E'). Garante que o adiantamento some
                    // da tela em ate (PERIOD_MINUTES + tempo do worker) minutos.
                    int deletadosPosBaixa = CancelamentoHelper.completarDeletePosBaixaItau();

                    resultado[0] = cancelados + deletadosPosBaixa;
                });
                return resultado[0];
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[ReconciliacaoScheduler] Erro na reconciliacao", e);
                return 0;
            } finally {
                if (usuApplied) {
                    try {
                        if (prevUsu != null) JapeSessionContext.putProperty("usuario_logado", prevUsu);
                        else JapeSessionContext.removeProperty("usuario_logado");
                    } catch (Exception ignore) {}
                }
                if (session != null) {
                    try { JapeSession.close(session); } catch (Exception ignore) {}
                }
            }
        }
    }
}
