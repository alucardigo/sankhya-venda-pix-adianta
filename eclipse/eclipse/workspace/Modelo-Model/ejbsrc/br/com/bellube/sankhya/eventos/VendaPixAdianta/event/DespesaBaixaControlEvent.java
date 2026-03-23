package br.com.bellube.sankhya.eventos.VendaPixAdianta.event;

import br.com.sankhya.extensions.eventoprogramavel.EventoProgramavelJava;
import br.com.sankhya.jape.event.PersistenceEvent;
import br.com.sankhya.jape.event.TransactionContext;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.modelcore.MGEModelException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Evento programável para controle de baixa de despesas de adiantamentos PIX - PHASE 3.
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * PHASE 3: CONTROLE DE PAGAMENTO - ✅ IMPLEMENTAÇÃO COMPLETA
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * FUNCIONALIDADE:
 *   Este evento intercepta tentativas de baixa de despesas (RECDESP=-1) que estao
 *   relacionadas a adiantamentos PIX (campo AD_NUNOTAADIANT preenchido).
 *   
 *   REGRA DE NEGÓCIO:
 *   - Se a RECEITA correspondente do adiantamento NÃO foi paga (DHBAIXA is NULL):
 *     → BLOQUEIA a baixa da despesa com mensagem de erro clara
 *   - Se a RECEITA correspondente do adiantamento foi paga (DHBAIXA preenchida):
 *     → PERMITE a baixa da despesa normalmente
 * 
 * TABELA MONITORADA:
 *   - TGFFIN (Financeiro) - beforeUpdate e beforeDelete
 * 
 * CAMPOS VERIFICADOS:
 *   - RECDESP (1=Receita, -1=Despesa)
 *   - AD_NUNOTAADIANT (Referência à venda PIX original)
 *   - DHBAIXA (Data/hora da baixa - null = não pago)
 *   - NUMNOTA (Agrupador de títulos do adiantamento)
 * 
 * ARQUITETURA:
 *   Phase 1: Cria adiantamento (RECEITA + DESPESA) automaticamente
 *   Phase 2: Envia email com instruções para gerar boleto
 *   Phase 3: Controla baixa da despesa baseado em pagamento do boleto (ESTA CLASSE)
 * 
 * CONFIGURAÇÃO:
 *   Este evento deve ser registrado em TGFFIN com os seguintes gatilhos:
 *   - beforeUpdate: Intercepta tentativas de baixa (mudança de DHBAIXA)
 *   - beforeDelete: Intercepta tentativas de exclusão de despesa
 * 
 * IMPORTANTE:
 *   - Este evento NÃO interfere com receitas (RECDESP=1)
 *   - Só atua em despesas que têm AD_NUNOTAADIANT preenchido
 *   - Baixa de despesas normais (sem AD_NUNOTAADIANT) não é afetada
 */
public class DespesaBaixaControlEvent implements EventoProgramavelJava {

    private static final Logger LOGGER = Logger.getLogger(DespesaBaixaControlEvent.class.getName());

    @Override
    public void beforeUpdate(PersistenceEvent event) throws Exception {
        // Verificar se é a entidade correta
        if (!"Financeiro".equals(event.getEntity().getName())) {
            return;
        }

        DynamicVO finVO = (DynamicVO) event.getVo();
        
        // Verificar se eh uma DESPESA (RECDESP = -1 no Sankhya)
        BigDecimal recdesp = finVO.asBigDecimal("RECDESP");
        if (recdesp == null || recdesp.intValue() != -1) {
            // Não é despesa, permitir operação
            return;
        }

        // Verificar se é um adiantamento PIX (tem AD_NUNOTAADIANT)
        BigDecimal nunotaAdiant = finVO.asBigDecimal("AD_NUNOTAADIANT");
        if (nunotaAdiant == null) {
            // Não é adiantamento PIX, permitir operação
            return;
        }

        // Verificar se está tentando BAIXAR a despesa
        // (DHBAIXA mudando de null para preenchido)
        if (!event.getModifingFields().containsKey("DHBAIXA")) {
            // Não está modificando DHBAIXA, permitir operação
            return;
        }

        Timestamp dhbaixaNova = finVO.asTimestamp("DHBAIXA");
        if (dhbaixaNova == null) {
            // Está removendo baixa (estorno), permitir operação
            return;
        }

        // AQUI: Está tentando BAIXAR uma DESPESA de adiantamento PIX
        // Precisamos verificar se a RECEITA correspondente foi paga

        BigDecimal numnota = finVO.asBigDecimal("NUMNOTA");
        BigDecimal nufin = finVO.asBigDecimal("NUFIN");
        
        LOGGER.info("[DespesaBaixaControl] Tentativa de baixa de despesa de adiantamento PIX - " +
                   "NUFIN=" + nufin + ", NUMNOTA=" + numnota + ", AD_NUNOTAADIANT=" + nunotaAdiant);

        // Verificar se a receita correspondente foi paga
        boolean receitaPaga = verificarReceitaPaga(numnota, nunotaAdiant);

        if (!receitaPaga) {
            // RECEITA NÃO PAGA - BLOQUEAR BAIXA DA DESPESA
            String mensagemErro = construirMensagemBloqueio(numnota, nunotaAdiant);
            LOGGER.warning("[DespesaBaixaControl] BLOQUEADO - Receita não paga - NUFIN=" + nufin + 
                          ", NUMNOTA=" + numnota);
            throw new MGEModelException(mensagemErro);
        }

        // RECEITA PAGA - PERMITIR BAIXA DA DESPESA
        LOGGER.info("[DespesaBaixaControl] PERMITIDO - Receita paga - NUFIN=" + nufin + 
                   ", NUMNOTA=" + numnota);
    }

    @Override
    public void beforeDelete(PersistenceEvent event) throws Exception {
        // Verificar se é a entidade correta
        if (!"Financeiro".equals(event.getEntity().getName())) {
            return;
        }

        DynamicVO finVO = (DynamicVO) event.getVo();
        
        // Verificar se eh uma DESPESA (RECDESP = -1 no Sankhya)
        BigDecimal recdesp = finVO.asBigDecimal("RECDESP");
        if (recdesp == null || recdesp.intValue() != -1) {
            return;
        }

        // Verificar se é um adiantamento PIX
        BigDecimal nunotaAdiant = finVO.asBigDecimal("AD_NUNOTAADIANT");
        if (nunotaAdiant == null) {
            return;
        }

        // Verificar se a despesa foi baixada
        Timestamp dhbaixa = finVO.asTimestamp("DHBAIXA");
        if (dhbaixa != null) {
            // Despesa já foi baixada, bloquear exclusão
            BigDecimal nufin = finVO.asBigDecimal("NUFIN");
            LOGGER.warning("[DespesaBaixaControl] Tentativa de exclusão de despesa baixada de adiantamento PIX - NUFIN=" + nufin);
            throw new MGEModelException(
                "EXCLUSÃO BLOQUEADA!\n\n" +
                "Esta despesa pertence a um adiantamento PIX e já foi baixada.\n" +
                "Não é possível excluir títulos baixados de adiantamentos PIX.\n\n" +
                "Para reverter, realize o estorno da baixa primeiro."
            );
        }

        LOGGER.info("[DespesaBaixaControl] Exclusão de despesa não baixada permitida - adiantamento PIX");
    }

    /**
     * Verifica se a receita correspondente ao adiantamento foi paga.
     *
     * @param numnota NUMNOTA do adiantamento
     * @param nunotaAdiant NUNOTA da venda PIX original
     * @return true se a receita foi paga, false caso contrário
     */
    private boolean verificarReceitaPaga(BigDecimal numnota, BigDecimal nunotaAdiant) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Buscar a RECEITA (RECDESP=1) do mesmo NUMNOTA e AD_NUNOTAADIANT
            String sql = "SELECT NUFIN, DHBAIXA FROM TGFFIN WITH (NOLOCK) " +
                        "WHERE NUMNOTA = ? " +
                        "AND AD_NUNOTAADIANT = ? " +
                        "AND RECDESP = 1";

            stmt = jdbc.getPreparedStatement(sql);
            stmt.setBigDecimal(1, numnota);
            stmt.setBigDecimal(2, nunotaAdiant);
            rs = stmt.executeQuery();

            if (rs.next()) {
                BigDecimal nufinReceita = rs.getBigDecimal("NUFIN");
                Timestamp dhbaixaReceita = rs.getTimestamp("DHBAIXA");

                if (dhbaixaReceita != null) {
                    LOGGER.info("[DespesaBaixaControl] Receita encontrada e PAGA - NUFIN=" + nufinReceita + 
                               ", DHBAIXA=" + dhbaixaReceita);
                    return true;
                } else {
                    LOGGER.warning("[DespesaBaixaControl] Receita encontrada mas NÃO PAGA - NUFIN=" + nufinReceita);
                    return false;
                }
            } else {
                LOGGER.warning("[DespesaBaixaControl] Receita NÃO ENCONTRADA para NUMNOTA=" + numnota + 
                              ", AD_NUNOTAADIANT=" + nunotaAdiant);
                return false;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[DespesaBaixaControl] Erro ao verificar receita paga", e);
            // Em caso de erro, bloquear por segurança
            return false;
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception e) { /* ignore */ }
            if (stmt != null) try { stmt.close(); } catch (Exception e) { /* ignore */ }
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * Constrói mensagem de erro detalhada para bloqueio de baixa.
     *
     * @param numnota NUMNOTA do adiantamento
     * @param nunotaAdiant NUNOTA da venda PIX original
     * @return Mensagem de erro formatada
     */
    private String construirMensagemBloqueio(BigDecimal numnota, BigDecimal nunotaAdiant) {
        StringBuilder msg = new StringBuilder();
        
        msg.append("⚠️ BAIXA DE DESPESA BLOQUEADA ⚠️\n\n");
        msg.append("═══════════════════════════════════════════════════\n\n");
        msg.append("Esta despesa pertence a um ADIANTAMENTO PIX e não pode ser baixada\n");
        msg.append("porque o BOLETO correspondente ainda NÃO FOI PAGO.\n\n");
        
        msg.append("DADOS DO ADIANTAMENTO:\n");
        msg.append("• NUMNOTA: ").append(numnota).append("\n");
        msg.append("• Venda PIX Original (NUNOTA): ").append(nunotaAdiant).append("\n\n");
        
        msg.append("═══════════════════════════════════════════════════\n\n");
        msg.append("COMO PROCEDER:\n\n");
        msg.append("1️⃣ Verifique se o boleto foi gerado no sistema\n");
        msg.append("   → Financeiro → Movimentação Financeira\n");
        msg.append("   → Filtrar por NUMNOTA = ").append(numnota).append("\n\n");
        
        msg.append("2️⃣ Se o boleto foi pago pelo cliente:\n");
        msg.append("   → Realize a BAIXA da RECEITA primeiro\n");
        msg.append("   → Após baixar a receita, a despesa poderá ser baixada\n\n");
        
        msg.append("3️⃣ Se o boleto ainda não foi pago:\n");
        msg.append("   → Aguarde o pagamento do cliente\n");
        msg.append("   → Ou cancele o adiantamento se necessário\n\n");
        
        msg.append("═══════════════════════════════════════════════════\n\n");
        msg.append("⚠️ IMPORTANTE: A despesa SÓ pode ser baixada APÓS a receita ser paga!\n\n");
        msg.append("Módulo: VendaPixAdianta - Phase 3 (Controle de Pagamento)");
        
        return msg.toString();
    }

    // Métodos não utilizados da interface
    @Override
    public void beforeInsert(PersistenceEvent event) throws Exception {}

    @Override
    public void afterInsert(PersistenceEvent event) throws Exception {}

    @Override
    public void afterUpdate(PersistenceEvent event) throws Exception {}

    @Override
    public void afterDelete(PersistenceEvent event) throws Exception {}

    @Override
    public void beforeCommit(TransactionContext ctx) throws Exception {}
}
