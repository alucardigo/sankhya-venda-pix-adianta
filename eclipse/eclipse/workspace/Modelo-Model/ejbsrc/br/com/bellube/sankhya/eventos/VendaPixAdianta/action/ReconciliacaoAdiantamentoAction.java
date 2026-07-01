package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.CancelamentoHelper;
import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Safety-net job: varre a TGFFIN atras de adiantamentos orfaos
 * (PROVISAO='N' + DHBAIXA NULL) cujo pedido original esta em estado
 * anomalo (deletado, cancelado ou nao pendente/nao faturado) e SEM
 * vinculo TGFVAR (nao virou venda confirmada).
 *
 * Para cada orfao encontrado dispara o mesmo caminho de cancelamento
 * usado pelo evento (CancelamentoHelper.cancelarAdiantamentosPorNota).
 *
 * Como usar:
 *   - Registrar como Acao de Rotina no Sankhya (menu ou agenda Cuckoo)
 *   - Recomendado executar a cada 15 minutos (ou horario de baixa carga)
 *   - Pode ser executado manualmente pelo usuario para saneamento.
 *
 * Parametros (opcionais, via contexto quando executado manualmente):
 *   - DIAS_LOOKBACK (inteiro): janela de busca em dias. Default=30.
 */
public class ReconciliacaoAdiantamentoAction implements AcaoRotinaJava {

    private static final Logger LOGGER = Logger.getLogger(ReconciliacaoAdiantamentoAction.class.getName());
    private static final int DIAS_DEFAULT = 30;
    private static final int GRACE_HORAS_DEFAULT = 24;

    @Override
    public void doAction(ContextoAcao contextoAcao) throws Exception {
        int dias = DIAS_DEFAULT;
        int graceHoras = GRACE_HORAS_DEFAULT;
        try {
            Object p = contextoAcao.getParam("DIAS_LOOKBACK");
            if (p != null) {
                int lookback = Integer.parseInt(p.toString());
                if (lookback > 0 && lookback <= 365) dias = lookback;
            }
        } catch (Exception ignore) { /* mantem default */ }
        try {
            Object g = contextoAcao.getParam("GRACE_HORAS");
            if (g != null) {
                int gh = Integer.parseInt(g.toString());
                if (gh >= 0 && gh <= 168) graceHoras = gh;
            }
        } catch (Exception ignore) { /* mantem default */ }

        long inicio = System.currentTimeMillis();
        LOGGER.info("[ReconciliacaoAdiantamento] Iniciando varredura - lookback=" + dias
                + " dias, grace=" + graceHoras + "h");

        int cancelados = 0;
        try {
            cancelados = CancelamentoHelper.reconciliarOrfaos(dias, graceHoras);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ReconciliacaoAdiantamento] Erro nao esperado na reconciliacao", e);
        }

        long duracao = System.currentTimeMillis() - inicio;
        String msg = "[ReconciliacaoAdiantamento] Concluido em " + duracao + "ms - "
                + cancelados + " adiantamento(s) orfao(s) cancelado(s) (lookback=" + dias
                + " dias, grace=" + graceHoras + "h)";
        LOGGER.info(msg);

        try { contextoAcao.mostraErro(msg); } catch (Throwable ignore) {}
    }
}
