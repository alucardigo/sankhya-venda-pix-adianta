package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;


/**
 * Utilitário para logging de auditoria do processamento de adiantamentos PIX.
 * 
 * Implementa logging detalhado e informativo com:
 * - Performance metrics com categorização de tempo
 * - Contexto detalhado dos dados processados
 * - Logging estruturado com informações de thread
 * - Mensagens formatadas com timestamp e contexto
 * - Tratamento de erros estruturado e completo
 */
public final class AuditLogger {

    private static final Logger LOGGER = Logger.getLogger(AuditLogger.class.getName());
    
    // Constantes para status
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_PROCESSING = "PROCESSING";
    
    // Flag para logging detalhado
    private static final boolean LOG_DETALHADO = true;
    
    // Prefixo para identificação das mensagens
    private static final String LOG_PREFIX = "[VENDAPIX-ADI]";
    
    /**
     * Construtor privado para classe utilitária
     */
    private AuditLogger() {}
    
    /**
     * Método de conveniência para testes - registra sucesso simples.
     * Para compatibilidade com testes existentes.
     *
     * @param nunota Número da nota fiscal
     */
    public static void logSuccess(BigDecimal nunota) {
        ProcessingContext context = new ProcessingContext(nunota);
        logSuccessDetailed(context);
    }
    
    /**
     * Método de conveniência para testes - registra erro simples.
     * Para compatibilidade com testes existentes.
     *
     * @param nunota Número da nota fiscal
     * @param message Mensagem de erro
     * @param throwable Exceção (pode ser null)
     */
    public static void logError(BigDecimal nunota, String message, Throwable throwable) {
        ProcessingContext context = new ProcessingContext(nunota);
        logErrorDetailed(context, message, throwable);
    }
    
    /**
     * Método de logging detalhado para console
     * @param mensagem - Mensagem para log
     */
    private static void log(String mensagem) {
        if (LOG_DETALHADO) {
            System.out.println(LOG_PREFIX + " " + mensagem);
        }
    }
    
    /**
     * Classe para contexto de processamento detalhado
     */
    public static class ProcessingContext {
        public BigDecimal nunota;
        public BigDecimal codparc;
        public BigDecimal vlrnota;
        public String dtneg;
        public String etapaAtual;
        public long tempoInicio;
        public Map<String, Object> configValues = new HashMap<>();
        public Map<String, Object> detalhesAdicionais = new HashMap<>();
        // Aliases for test compatibility
        public Map<String, Object> details = detalhesAdicionais;
        public Map<String, Object> config = configValues;
        
        public ProcessingContext(BigDecimal nunota) {
            this.nunota = nunota;
            this.tempoInicio = System.currentTimeMillis();
        }
        
        public void addConfigValue(String key, Object value) {
            configValues.put(key, value);
        }
        
        public void addDetail(String key, Object value) {
            detalhesAdicionais.put(key, value);
        }
        
        public long getTempoDecorrido() {
            return System.currentTimeMillis() - tempoInicio;
        }
        
        public String getCategorizacaoTempo() {
            long tempo = getTempoDecorrido();
            if (tempo > 10000) return "LENTO";
            if (tempo > 5000) return "MODERADO";
            return "RÁPIDO";
        }
    }

    /**
     * Registra início do processamento com contexto detalhado
     * @param context Contexto de processamento
     */
    public static void logProcessingStart(ProcessingContext context) {
        // CRÍTICO: Verificar se context é null antes de acessar campos
        if (context == null) {
            LOGGER.warning("Tentativa de log de início com contexto nulo - ignorada");
            return;
        }

        log("INÍCIO processamento NUNOTA: " + context.nunota +
            " | CODPARC: " + context.codparc +
            " | VALOR: " + context.vlrnota +
            " | DATA NEGOCIAÇÃO: " + context.dtneg +
            " | Thread: " + Thread.currentThread().getName());
    }
    
    /**
     * Registra etapa do processamento com contexto
     * @param context Contexto de processamento
     * @param etapa Nome da etapa
     * @param detalhes Detalhes da etapa
     */
    public static void logProcessingStep(ProcessingContext context, String etapa, String detalhes) {
        if (context.nunota == null) return;
        
        context.etapaAtual = etapa;
        long tempoDecorrido = context.getTempoDecorrido();
        
        log("ETAPA [" + etapa + "] NUNOTA: " + context.nunota + 
            " | Tempo: " + tempoDecorrido + "ms (" + context.getCategorizacaoTempo() + ")" +
            " | Detalhes: " + detalhes +
            " | Thread: " + Thread.currentThread().getName());
    }
    
    /**
     * Registra sucesso com contexto detalhado e métricas
     * @param context Contexto completo do processamento
     */
    public static void logSuccessDetailed(ProcessingContext context) {
        // CRÍTICO: Verificar se context é null antes de acessar campos
        if (context == null) {
            LOGGER.warning("Tentativa de log de sucesso com contexto nulo - ignorada");
            return;
        }

        if (context.nunota == null) {
            LOGGER.warning("Tentativa de log de sucesso com NUNOTA nula - ignorada");
            return;
        }
        
        long tempoTotal = context.getTempoDecorrido();
        String categorizacao = context.getCategorizacaoTempo();
        
        StringBuilder successMessage = new StringBuilder();
        successMessage.append("SUCESSO processamento NUNOTA: ").append(context.nunota);
        successMessage.append(" | Tempo total: ").append(tempoTotal).append("ms (").append(categorizacao).append(")");
        successMessage.append(" | CODPARC: ").append(context.codparc);
        successMessage.append(" | VALOR: ").append(context.vlrnota);
        successMessage.append(" | DATA NEGOCIAÇÃO: ").append(context.dtneg);
        successMessage.append(" | Thread: ").append(Thread.currentThread().getName());
        successMessage.append(" | Timestamp: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(new Date()));
        
        // Adicionar detalhes adicionais se existirem
        if (!context.detalhesAdicionais.isEmpty()) {
            successMessage.append(" | DETALHES: ");
            context.detalhesAdicionais.forEach((key, value) -> 
                successMessage.append(key).append("=").append(value).append("; "));
        }
        
        log(successMessage.toString());
    }

    /**
     * Registra uma falha no processamento de um adiantamento com informações detalhadas.
     * 
     * @param context Contexto do processamento
     * @param message Mensagem de erro descritiva
     * @param throwable Exceção que causou a falha (pode ser null)
     */
    public static void logErrorDetailed(ProcessingContext context, String message, Throwable throwable) {
        if (context.nunota == null) {
            LOGGER.warning("Tentativa de log de erro com NUNOTA nula - ignorada");
            return;
        }
        
        StringBuilder errorMessage = new StringBuilder();
        errorMessage.append("ERRO no processamento NUNOTA: ").append(context.nunota);
        errorMessage.append(" | CODPARC: ").append(context.codparc);
        errorMessage.append(" | VALOR: ").append(context.vlrnota);
        errorMessage.append(" | DATA NEGOCIAÇÃO: ").append(context.dtneg);
        errorMessage.append(" | Etapa: ").append(context.etapaAtual != null ? context.etapaAtual : "NÃO DEFINIDA");
        
        long tempoAteErro = context.getTempoDecorrido();
        errorMessage.append(" | Tempo até erro: ").append(tempoAteErro).append("ms (").append(context.getCategorizacaoTempo()).append(")");
        
        errorMessage.append(" | Mensagem: ").append(message);
        
        if (throwable != null) {
            errorMessage.append(" | Tipo exceção: ").append(throwable.getClass().getSimpleName());
            if (throwable.getMessage() != null) {
                errorMessage.append(" | Mensagem exceção: ").append(throwable.getMessage());
            }
        }
        
        // Adicionar detalhes adicionais se existirem
        if (!context.detalhesAdicionais.isEmpty()) {
            errorMessage.append(" | DETALHES: ");
            context.detalhesAdicionais.forEach((key, value) -> 
                errorMessage.append(key).append("=").append(value).append("; "));
        }
        
        errorMessage.append(" | Thread: ").append(Thread.currentThread().getName());
        errorMessage.append(" | Timestamp: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(new Date()));
        
        log(errorMessage.toString());
        
        // Log de stack trace em nível de debug
        if (throwable != null) {
            LOGGER.log(Level.FINE, "Stack trace da exceção NUNOTA " + context.nunota, throwable);
        }
    }

    /**
     * Formata e registra informações de configuração utilizadas
     * @param context Contexto do processamento
     */
    public static void logConfigurations(ProcessingContext context) {
        if (context.nunota == null || context.configValues.isEmpty()) return;
        
        StringBuilder configMessage = new StringBuilder();
        configMessage.append("CONFIGURAÇÕES NUNOTA: ").append(context.nunota);
        
        context.configValues.forEach((key, value) -> 
            configMessage.append(" | ").append(key).append("=").append(value));
        
        log(configMessage.toString());
    }
}