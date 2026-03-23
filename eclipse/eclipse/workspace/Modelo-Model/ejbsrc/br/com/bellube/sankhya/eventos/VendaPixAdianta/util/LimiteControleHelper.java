package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utilitário para controle de pagamento de boletos - RESERVADO PARA PHASE 3.
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠️  IMPORTANTE: ESTA CLASSE NÃO É USADA NA PHASE 1 (IMPLEMENTAÇÃO ATUAL)
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * PHASE 1 (ATUAL): Adiantamentos PIX são criados SEM verificação de limites
 *   - PIX = pagamento instantâneo = dinheiro já recebido
 *   - Não há risco de crédito em transações PIX
 *   - Adiantamentos são SEMPRE criados para vendas PIX confirmadas
 * 
 * PHASE 3 (FUTURA): Esta classe será usada para bloquear baixa de despesas
 *   - Verificar se o boleto do adiantamento foi pago
 *   - Se boleto NÃO pago: BLOQUEAR baixa da despesa do adiantamento
 *   - Se boleto pago: PERMITIR baixa da despesa do adiantamento
 *   
 *   Implementação futura requer:
 *   - Novo evento programável em TGFFIN (Financeiro) - não em TGFCAB
 *   - Interceptar tentativas de baixa de despesas (RECDESP = 2)
 *   - Verificar se despesa está relacionada a adiantamento PIX
 *   - Consultar status de pagamento do boleto
 *   - Bloquear ou permitir a operação conforme status
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * SEQUÊNCIA DE IMPLEMENTAÇÃO DAS 3 FASES:
 * ═══════════════════════════════════════════════════════════════════════════
 * 1. Phase 1: Criar adiantamento (✅ IMPLEMENTADO)
 * 2. Phase 2: Gerar boleto (⏳ PENDENTE)
 * 3. Phase 3: Controlar baixa da despesa (⏳ PENDENTE - usa esta classe)
 */
public class LimiteControleHelper {

    private static final Logger LOGGER = Logger.getLogger(LimiteControleHelper.class.getName());
    
    /**
     * Resultado da verificação de limite
     */
    public enum ResultadoLimite {
        LIBERADO("Faturamento liberado - limite de crédito OK"),
        BLOQUEADO_LIMITE_EXCEDIDO("Faturamento bloqueado - limite de crédito excedido"),
        ERRO_VERIFICACAO("Erro na verificação de limite");
        
        private final String mensagem;
        
        ResultadoLimite(String mensagem) {
            this.mensagem = mensagem;
        }
        
        public String getMensagem() {
            return mensagem;
        }
    }

    /**
     * Verifica se o faturamento pode ser liberado baseado em limites de crédito.
     * 
     * REFATORADO: Para vendas PIX (pagamento instantâneo), não verificamos receita baixada
     * pois o pagamento já foi realizado. Focamos apenas no limite de crédito do parceiro.
     *
     * @param nunota Número único da nota fiscal
     * @param vlrnota Valor da nota fiscal
     * @param codparc Código do parceiro
     * @return Resultado da verificação de limite
     */
    public static ResultadoLimite verificarLimiteFaturamento(BigDecimal nunota, BigDecimal vlrnota, BigDecimal codparc) {
        if (nunota == null || vlrnota == null || codparc == null) {
            LOGGER.warning("[LimiteControleHelper] Parâmetros inválidos para verificação de limite");
            return ResultadoLimite.ERRO_VERIFICACAO;
        }

        LOGGER.info("[LimiteControleHelper] Verificando limite de crédito - NUNOTA=" + nunota + 
                   ", VALOR=" + vlrnota + ", CODPARC=" + codparc);

        try {
            // Verificar limite de crédito do parceiro
            BigDecimal limiteCredito = obterLimiteLiberacao(codparc);
            
            if (limiteCredito != null && vlrnota.compareTo(limiteCredito) > 0) {
                LOGGER.warning("[LimiteControleHelper] Valor excede limite de crédito - VALOR=" + vlrnota + 
                              ", LIMITE=" + limiteCredito + " - bloqueando adiantamento - NUNOTA=" + nunota);
                return ResultadoLimite.BLOQUEADO_LIMITE_EXCEDIDO;
            }

            // Limite OK ou sem limite definido - liberar
            LOGGER.info("[LimiteControleHelper] Limite de crédito OK - adiantamento liberado - NUNOTA=" + nunota + 
                       (limiteCredito != null ? ", LIMITE=" + limiteCredito : " (sem limite definido)"));
            return ResultadoLimite.LIBERADO;

        } catch (Exception e) {
            String errorMsg = "[LimiteControleHelper] Erro na verificação de limite - NUNOTA=" + nunota + ": " + e.getMessage();
            LOGGER.log(Level.SEVERE, errorMsg, e);
            return ResultadoLimite.ERRO_VERIFICACAO;
        }
    }


    /**
     * Obtém o limite de liberação de um parceiro.
     *
     * @param codparc Código do parceiro
     * @return Limite de liberação ou null se não definido
     */
    private static BigDecimal obterLimiteLiberacao(BigDecimal codparc) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Buscar limite de liberação na tabela de parceiros (assumindo campo LIMLIB ou similar)
            // Nota: O nome do campo pode variar dependendo da configuração do Sankhya
            String sql = "SELECT LIMCRED as LIMITE_LIBERACAO FROM TGFPAR " +
                        "WHERE CODPARC = ? AND LIMCRED IS NOT NULL AND LIMCRED > 0";

            stmt = jdbc.getPreparedStatement(sql);
            stmt.setBigDecimal(1, codparc);
            rs = stmt.executeQuery();

            if (rs.next()) {
                BigDecimal limiteLiberacao = rs.getBigDecimal("LIMITE_LIBERACAO");
                LOGGER.info("[LimiteControleHelper] Limite de liberação encontrado: " + limiteLiberacao + 
                           " para CODPARC=" + codparc);
                return limiteLiberacao;
            } else {
                LOGGER.info("[LimiteControleHelper] Nenhum limite de liberação definido para CODPARC=" + codparc);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[LimiteControleHelper] Erro ao obter limite de liberação para CODPARC=" + codparc, e);
        } finally {
            if (rs != null) {
                try { rs.close(); } catch (Exception e) { /* ignore */ }
            }
            if (stmt != null) {
                try { stmt.close(); } catch (Exception e) { /* ignore */ }
            }
            if (jdbc != null) {
                try { jdbc.closeSession(); } catch (Exception e) { /* ignore */ }
            }
        }

        return null;  // Sem limite definido = sem restrição
    }

    /**
     * Verifica se o faturamento deve ser bloqueado baseado no resultado da verificação.
     *
     * @param resultado Resultado da verificação de limite
     * @return true se o faturamento deve ser bloqueado
     */
    public static boolean deveBloquearkFaturamento(ResultadoLimite resultado) {
        return resultado != ResultadoLimite.LIBERADO;
    }

    /**
     * Registra log detalhado da verificação de limite.
     *
     * @param nunota Número da nota
     * @param resultado Resultado da verificação
     */
    public static void registrarLogLimite(BigDecimal nunota, ResultadoLimite resultado) {
        String nivel = (resultado == ResultadoLimite.LIBERADO) ? "INFO" : "WARNING";
        String logMsg = "[LimiteControleHelper] [" + nivel + "] Verificação de limite - NUNOTA=" + nunota + 
                       " - Resultado: " + resultado.name() + " - " + resultado.getMensagem();
        
        if (resultado == ResultadoLimite.LIBERADO) {
            LOGGER.info(logMsg);
        } else {
            LOGGER.warning(logMsg);
        }
    }
}