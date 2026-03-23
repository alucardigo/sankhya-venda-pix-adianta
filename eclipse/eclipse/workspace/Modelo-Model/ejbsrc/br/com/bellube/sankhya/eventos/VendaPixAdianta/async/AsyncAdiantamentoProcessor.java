package br.com.bellube.sankhya.eventos.VendaPixAdianta.async;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.service.AdiantamentoService;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;
import br.com.sankhya.jape.util.JapeSessionContext;

import java.math.BigDecimal;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processador assincrono singleton para adiantamentos PIX.
 * Pool fixo de workers consome tasks de uma fila ilimitada.
 */
public class AsyncAdiantamentoProcessor {

    private static final Logger LOGGER = Logger.getLogger(AsyncAdiantamentoProcessor.class.getName());
    private static final int NUM_THREADS = 3;
    private static final BigDecimal CODUSU_SISTEMA = new BigDecimal("376");
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 10000;
    private static final Random RANDOM = new Random();

    private static final ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);
    private static final BlockingQueue<AdiantamentoTask> taskQueue = new LinkedBlockingQueue<>();
    private static final Set<BigDecimal> pendingNunotas = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private static final AtomicInteger totalProcessed = new AtomicInteger(0);
    private static final AtomicInteger totalErrors = new AtomicInteger(0);

    static {
        LOGGER.info("[VENDAPIX-ASYNC] Iniciando " + NUM_THREADS + " workers");
        for (int i = 0; i < NUM_THREADS; i++) {
            executorService.submit(new Worker(i + 1));
        }
    }

    private AsyncAdiantamentoProcessor() {}

    public static void submitTask(AdiantamentoTask task) {
        if (task == null || task.nunota() == null) {
            LOGGER.warning("[VENDAPIX-ASYNC] Task nula ignorada");
            return;
        }

        BigDecimal nunota = task.nunota();

        if (pendingNunotas.contains(nunota)) {
            LOGGER.info("[VENDAPIX-ASYNC] NUNOTA=" + nunota + " ja pendente - ignorada");
            return;
        }

        pendingNunotas.add(nunota);

        if (taskQueue.offer(task)) {
            totalSubmitted.incrementAndGet();
            LOGGER.info("[VENDAPIX-ASYNC] Submetida NUNOTA=" + nunota + " | Fila=" + taskQueue.size());
        } else {
            pendingNunotas.remove(nunota);
            totalErrors.incrementAndGet();
            LOGGER.severe("[VENDAPIX-ASYNC] Fila rejeitou NUNOTA=" + nunota + " - executando sincrono");
            processarSincrono(task, "QUEUE_FULL");
        }
    }

    public static boolean isTaskPending(BigDecimal nunota) {
        return nunota != null && pendingNunotas.contains(nunota);
    }

    public static String getSystemMetrics() {
        return String.format("Fila=%d Submetidos=%d Processados=%d Erros=%d",
                taskQueue.size(), totalSubmitted.get(), totalProcessed.get(), totalErrors.get());
    }

    public static void shutdown() {
        LOGGER.info("[VENDAPIX-ASYNC] Finalizando processador");
        executorService.shutdown();
    }

    // --- Worker ---

    private static class Worker implements Runnable {
        private final int id;

        Worker(int id) {
            this.id = id;
        }

        @Override
        public void run() {
            LOGGER.info("[VENDAPIX-ASYNC] Worker-" + id + " iniciado");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AdiantamentoTask task = taskQueue.take();
                    processarComRetry(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                    LOGGER.log(Level.SEVERE, "[VENDAPIX-ASYNC] Worker-" + id + " erro nao tratado", e);
                }
            }

            LOGGER.info("[VENDAPIX-ASYNC] Worker-" + id + " finalizado");
        }

        private void processarComRetry(AdiantamentoTask task) {
            long inicio = System.currentTimeMillis();
            BigDecimal nunota = task.nunota();
            br.com.sankhya.jape.core.JapeSession.SessionHandle session = null;

            try {
                Exception lastException = null;

                for (int tentativa = 1; tentativa <= MAX_RETRY_ATTEMPTS; tentativa++) {
                    try {
                        session = br.com.sankhya.jape.core.JapeSession.open();

                        ContextState ctx = aplicarContexto(task.authInfo());
                        try {
                            session.execWithTX(() -> {
                                new AdiantamentoService().criarAdiantamentoParaVenda(task);
                            });
                        } finally {
                            restaurarContexto(ctx);
                        }

                        totalProcessed.incrementAndGet();
                        long duracao = System.currentTimeMillis() - inicio;
                        LOGGER.info("[VENDAPIX-ASYNC] Worker-" + id + " OK NUNOTA=" + nunota + " em " + duracao + "ms");
                        return;

                    } catch (Exception e) {
                        lastException = e;
                        fecharSessao(session);
                        session = null;

                        if (!isTransient(e) || tentativa >= MAX_RETRY_ATTEMPTS) break;

                        long delay = calcularDelay(tentativa);
                        LOGGER.info("[VENDAPIX-ASYNC] Worker-" + id + " retry " + tentativa + " NUNOTA=" + nunota
                                + " aguardando " + delay + "ms");
                        Thread.sleep(delay);
                    }
                }

                // Esgotou retries - fallback sincrono
                totalErrors.incrementAndGet();
                LOGGER.log(Level.SEVERE, "[VENDAPIX-ASYNC] Worker-" + id + " esgotou retries NUNOTA=" + nunota, lastException);
                processarSincrono(task, "RETRIES_EXHAUSTED");

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                pendingNunotas.remove(nunota);
                fecharSessao(session);
            }
        }

        private boolean isTransient(Throwable t) {
            if (t == null) return false;
            String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
            return msg.contains("timeout") || msg.contains("connection") || msg.contains("deadlock")
                    || msg.contains("lock") || msg.contains("socket");
        }

        private long calcularDelay(int tentativa) {
            long delay = Math.min(BASE_RETRY_DELAY_MS * (1L << (tentativa - 1)), MAX_RETRY_DELAY_MS);
            long jitter = (long) (delay * 0.25 * (RANDOM.nextDouble() - 0.5));
            return Math.max(100, delay + jitter);
        }
    }

    // --- Contexto de sessao ---

    private static final class ContextState {
        final Object prevUsuario;
        final Object prevAuth;
        final boolean authApplied;

        ContextState(Object prevUsuario, Object prevAuth, boolean authApplied) {
            this.prevUsuario = prevUsuario;
            this.prevAuth = prevAuth;
            this.authApplied = authApplied;
        }
    }

    private static ContextState aplicarContexto(Object authInfo) {
        Object prevUsu = null;
        Object prevAuth = null;
        boolean authApplied = false;

        try { prevUsu = JapeSessionContext.getProperty("usuario_logado"); } catch (Exception ignore) {}
        try { prevAuth = JapeSessionContext.getProperty("authInfo"); } catch (Exception ignore) {}

        try { JapeSessionContext.putProperty("usuario_logado", CODUSU_SISTEMA); } catch (Exception ignore) {}

        if (authInfo != null) {
            try {
                JapeSessionContext.putProperty("authInfo", authInfo);
                authApplied = true;
            } catch (Exception ignore) {}
        }

        return new ContextState(prevUsu, prevAuth, authApplied);
    }

    private static void restaurarContexto(ContextState state) {
        if (state == null) return;
        try {
            if (state.prevUsuario != null) {
                JapeSessionContext.putProperty("usuario_logado", state.prevUsuario);
            } else {
                JapeSessionContext.removeProperty("usuario_logado");
            }
        } catch (Exception ignore) {}

        if (state.authApplied) {
            try {
                if (state.prevAuth != null) {
                    JapeSessionContext.putProperty("authInfo", state.prevAuth);
                } else {
                    JapeSessionContext.removeProperty("authInfo");
                }
            } catch (Exception ignore) {}
        }
    }

    // --- Fallback sincrono ---

    private static void processarSincrono(AdiantamentoTask task, String motivo) {
        if (task == null) return;

        br.com.sankhya.jape.core.JapeSession.SessionHandle session = null;
        ContextState ctx = null;
        try {
            LOGGER.info("[VENDAPIX-ASYNC] Fallback sincrono NUNOTA=" + task.nunota() + " motivo=" + motivo);
            session = br.com.sankhya.jape.core.JapeSession.open();
            ctx = aplicarContexto(task.authInfo());
            session.execWithTX(() -> new AdiantamentoService().criarAdiantamentoParaVenda(task));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VENDAPIX-ASYNC] Fallback falhou NUNOTA=" + task.nunota(), e);
            AuditLogger.logError(task.nunota(), "FALLBACK_SYNC_ERROR - " + motivo + ": " + e.getMessage(), e);
        } finally {
            restaurarContexto(ctx);
            fecharSessao(session);
            pendingNunotas.remove(task.nunota());
        }
    }

    private static void fecharSessao(br.com.sankhya.jape.core.JapeSession.SessionHandle session) {
        if (session == null) return;
        try {
            br.com.sankhya.jape.core.JapeSession.close(session);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[VENDAPIX-ASYNC] Erro ao fechar sessao JAPE", e);
        }
    }
}
