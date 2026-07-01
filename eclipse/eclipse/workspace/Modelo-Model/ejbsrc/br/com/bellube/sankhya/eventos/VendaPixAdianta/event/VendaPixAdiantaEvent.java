package br.com.bellube.sankhya.eventos.VendaPixAdianta.event;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AsyncAdiantamentoProcessor;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.ReconciliacaoScheduler;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.CancelamentoHelper;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.sankhya.extensions.eventoprogramavel.EventoProgramavelJava;
import br.com.sankhya.jape.util.JapeSessionContext;
import br.com.sankhya.jape.event.PersistenceEvent;
import br.com.sankhya.jape.event.TransactionContext;
import br.com.sankhya.jape.vo.DynamicVO;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Evento programavel para TGFCAB que cria adiantamentos automaticamente em vendas PIX confirmadas.
 * Arquitetura nao bloqueante: erros sao logados e nunca impedem a confirmacao da nota.
 */
public class VendaPixAdiantaEvent implements EventoProgramavelJava {

    private static final Logger LOGGER = Logger.getLogger("VendaPixAdiantaEvent");

    /** Marcador de versao - facilita identificar se o JAR atual em produção
     * eh o ultimo compilado. Aparece logo no startup. Atualizar a cada release. */
    private static final String BUILD_VERSION = "2026-06-26-BOTAO-PIX-AUTHINFO-V16.3";

    static {
        // Log de startup SEMPRE em INFO - facilita confirmar deploy
        LOGGER.info("[VendaPixAdianta] *** Classloader carregou VendaPixAdiantaEvent - BUILD=" + BUILD_VERSION + " ***");

        // Forca o carregamento e inicializacao do ReconciliacaoScheduler
        // (static block dele dispara o ScheduledExecutorService embedded).
        // Como o Sankhya carrega esta classe no startup quando registra o
        // evento, garante que o scheduler suba junto.
        try {
            Class.forName(ReconciliacaoScheduler.class.getName());
            LOGGER.info("[VendaPixAdianta] ReconciliacaoScheduler classloader carregado com sucesso (BUILD=" + BUILD_VERSION + ")");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[VendaPixAdianta] Falha ao iniciar ReconciliacaoScheduler", t);
        }
    }

    @Override
    public void afterUpdate(PersistenceEvent event) throws Exception {
        if (!"CabecalhoNota".equals(event.getEntity().getName())) {
            return;
        }

        BigDecimal nunota = null;

        try {
            DynamicVO cabVO = (DynamicVO) event.getVo();
            if (cabVO == null) return;

            Set<String> camposAlterados = event.getModifingFields().keySet();
            boolean relevantFieldModified = camposAlterados.contains("NUMNOTA")
                    || camposAlterados.contains("STATUSNOTA")
                    || camposAlterados.contains("PENDENTE");

            if (!relevantFieldModified) return;

            nunota = cabVO.asBigDecimal("NUNOTA");
            BigDecimal codparc = cabVO.asBigDecimal("CODPARC");
            BigDecimal vlrnota = cabVO.asBigDecimal("VLRNOTA");
            String statusNota = cabVO.asString("STATUSNOTA");
            BigDecimal codempVenda = cabVO.asBigDecimal("CODEMP");
            String pendenteAtual = cabVO.asString("PENDENTE");

            LOGGER.info("[VendaPixAdianta] [afterUpdate] NUNOTA=" + nunota
                    + ", CODPARC=" + codparc + ", VLRNOTA=" + vlrnota
                    + ", STATUSNOTA=" + statusNota);

            // 1) Cancelamento: nota foi cancelada?
            if (camposAlterados.contains("STATUSNOTA")) {
                if (tratarCancelamento(event, statusNota, nunota)) return;
            }

            // 2) Mudanca de PENDENTE para N: verificar vinculo TGFVAR
            if (camposAlterados.contains("PENDENTE")) {
                if (tratarMudancaPendente(event, cabVO, pendenteAtual, nunota)) return;
            }

            // 3) Fallback universal: se PENDENTE atual eh 'N' e existem adiantamentos
            //    sem vinculo TGFVAR (nao virou venda) -> cancela, independente de
            //    isPixConfirmado. Cobre caso em que CODTIPVENDA/CODTIPOPER foram
            //    alterados antes do aborto e o pedido nao vira mais PIX.
            if ("N".equals(pendenteAtual)) {
                boolean existeLigacaoVar = CancelamentoHelper.existeLigacaoVarPorNunotaOrig(nunota);
                boolean possuiAdiants = CancelamentoHelper.possuiAdiantamentosRelacionadosComVAR(nunota);
                if (!existeLigacaoVar && possuiAdiants) {
                    LOGGER.info("[VendaPixAdianta] Fallback universal PENDENTE=N sem TGFVAR - cancelando - NUNOTA=" + nunota);
                    cancelarAdiantamentosSemVinculo(nunota, "fallback universal PENDENTE=N");
                    return;
                }
            }

            // 4) Validar se eh PIX
            if (!isPixConfirmado(cabVO)) {
                LOGGER.info("[VendaPixAdianta] Nota nao qualifica para fluxo PIX - NUNOTA=" + nunota);
                return;
            }

            // 5) Criar adiantamento: exige campo NUMNOTA alterado
            if (!camposAlterados.contains("NUMNOTA")) return;

            if (vlrnota == null || vlrnota.compareTo(BigDecimal.ZERO) <= 0) {
                LOGGER.info("[VendaPixAdianta] Valor nao significativo - NUNOTA=" + nunota + ", VLRNOTA=" + vlrnota);
                return;
            }

            if (AsyncAdiantamentoProcessor.isTaskPending(nunota)) {
                LOGGER.info("[VendaPixAdianta] Adiantamento ja pendente na fila - NUNOTA=" + nunota);
                return;
            }

            if (jaExisteAdiantamento(nunota, codparc, codempVenda)) {
                LOGGER.info("[VendaPixAdianta] Adiantamento ja existe - NUNOTA=" + nunota);
                return;
            }

            if (!validarConfiguracao(codempVenda, nunota)) return;

            LOGGER.info("[VendaPixAdianta] Enviando para processamento assincrono - NUNOTA=" + nunota + ", VALOR=" + vlrnota);
            AdiantamentoTask task = criarTaskFromVenda(cabVO);
            AsyncAdiantamentoProcessor.submitTask(task);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] Erro geral no afterUpdate - NUNOTA=" + nunota + ": " + e.getMessage(), e);
        }
    }

    private boolean tratarCancelamento(PersistenceEvent event, String statusNota, BigDecimal nunota) {
        Object statusAnt = event.getModifingFields().get("STATUSNOTA");
        String statusAnterior = statusAnt != null ? statusAnt.toString() : null;

        if (!CancelamentoHelper.notaFoiCancelada(statusAnterior, statusNota)) return false;

        LOGGER.info("[VendaPixAdianta] Nota cancelada - cancelando adiantamentos - NUNOTA=" + nunota);
        cancelarAdiantamentosSemVinculo(nunota, "cancelamento de nota");
        return true;
    }

    private boolean tratarMudancaPendente(PersistenceEvent event, DynamicVO cabVO, String pendenteAtual, BigDecimal nunota) {
        Object pendAnt = event.getModifingFields().get("PENDENTE");
        String pendenteAnterior = pendAnt != null ? pendAnt.toString() : null;

        boolean mudouParaNaoPendente = !"N".equals(pendenteAnterior) && "N".equals(pendenteAtual);
        if (!mudouParaNaoPendente) return false;

        // CORRECAO: Verificar SEMPRE se existem adiantamentos reais na TGFFIN,
        // independente de a nota qualificar como PIX por flags.
        // Isso cobre notas parcialmente faturadas onde CODTIPOPER/CODTIPVENDA mudaram.
        boolean possuiAdiantamentosReais = CancelamentoHelper.possuiAdiantamentosRelacionadosComVAR(nunota);

        if (!possuiAdiantamentosReais) {
            // Sem adiantamentos vinculados - nada a cancelar
            if (!isPixConfirmado(cabVO)) {
                LOGGER.fine("[VendaPixAdianta] Nota nao qualifica para PIX e sem adiantamentos ao mudar PENDENTE=N - NUNOTA=" + nunota);
            } else {
                LOGGER.info("[VendaPixAdianta] Nota PIX sem adiantamentos ao mudar PENDENTE=N - NUNOTA=" + nunota);
            }
            return true;
        }

        // Tem adiantamentos reais - verificar se deve cancelar
        boolean existeLigacaoVar = CancelamentoHelper.existeLigacaoVarPorNunotaOrig(nunota);
        if (!existeLigacaoVar) {
            LOGGER.info("[VendaPixAdianta] Cancelando adiantamentos (PENDENTE=N sem vinculo TGFVAR) - NUNOTA=" + nunota);
            cancelarAdiantamentosSemVinculo(nunota, "ausencia vinculo TGFVAR ao mudar PENDENTE=N");
        } else {
            LOGGER.info("[VendaPixAdianta] Vinculo TGFVAR encontrado para NUNOTAORIG=" + nunota + " - adiantamentos mantidos");
        }
        return true;
    }

    private boolean isPixConfirmado(DynamicVO cabVO) {
        BigDecimal nunota = cabVO.asBigDecimal("NUNOTA");
        BigDecimal codTipVenda = cabVO.asBigDecimal("CODTIPVENDA");
        BigDecimal codTipOper = cabVO.asBigDecimal("CODTIPOPER");

        if (codTipVenda == null) {
            LOGGER.info("[VendaPixAdianta] CODTIPVENDA ausente - NUNOTA=" + nunota);
            return false;
        }

        try {
            ConfiguracaoHelper.AdiantamentoValidationResult validation =
                    ConfiguracaoHelper.verifyGeraAdiantamento(codTipVenda, codTipOper);
            if (!validation.success) {
                LOGGER.info("[VendaPixAdianta] AD_GERAADIANT bloqueando - " + validation.describe() + " - NUNOTA=" + nunota);
                return false;
            }
            LOGGER.info("[VendaPixAdianta] AD_GERAADIANT validado - " + validation.describe() + " - NUNOTA=" + nunota);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] Erro ao consultar AD_GERAADIANT para NUNOTA=" + nunota, e);
            return false;
        }
    }

    private boolean validarConfiguracao(BigDecimal codempVenda, BigDecimal nunota) {
        try {
            ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(codempVenda);
            ConfiguracaoHelper.getCodTopAdiantamento(codempVenda);
            ConfiguracaoHelper.getCodTipTitAdiantamento(codempVenda);
            ConfiguracaoHelper.getCodNatAdiantamento(codempVenda);
            return true;
        } catch (Exception confErr) {
            LOGGER.log(Level.WARNING, "[VendaPixAdianta] Config ausente na AD_TGFCAA para CODEMP=" + codempVenda
                    + ", NUNOTA=" + nunota + ": " + confErr.getMessage(), confErr);
            return false;
        }
    }

    private void cancelarAdiantamentosSemVinculo(BigDecimal nunota, String motivo) {
        try {
            boolean tudoOk = CancelamentoHelper.cancelarAdiantamentosPorNota(nunota);
            if (tudoOk) {
                LOGGER.info("[VendaPixAdianta] Adiantamentos cancelados OK (" + motivo + ") - NUNOTA=" + nunota);
            } else {
                LOGGER.warning("[VendaPixAdianta] Adiantamentos NAO foram totalmente cancelados (" + motivo
                        + ") - NUNOTA=" + nunota + " - veja logs [CancelamentoHelper] acima");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[VendaPixAdianta] Falha ao cancelar adiantamentos (" + motivo + ") - NUNOTA=" + nunota, e);
        }
    }

    private boolean jaExisteAdiantamento(BigDecimal nunota, BigDecimal codparc, BigDecimal codemp) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        java.sql.PreparedStatement stmt = null;
        java.sql.ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            Set<BigDecimal> relacionados = coletarNunotasRelacionados(nunota);
            java.util.List<BigDecimal> lista = new java.util.ArrayList<>(relacionados);

            // OTIMIZADO: TOP 1 em vez de COUNT - para de buscar ao achar o primeiro
            StringBuilder sql = new StringBuilder(
                    "SELECT TOP 1 1 FROM TGFFIN WITH (NOLOCK) WHERE RECDESP = 1 AND PROVISAO = 'N' AND AD_NUNOTAADIANT IN (");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(")");
            if (codparc != null) sql.append(" AND CODPARC = ?");
            if (codemp != null) sql.append(" AND CODEMP = ?");

            stmt = jdbc.getPreparedStatement(sql.toString());
            int idx = 1;
            for (BigDecimal n : lista) {
                stmt.setBigDecimal(idx++, n);
            }
            if (codparc != null) stmt.setBigDecimal(idx++, codparc);
            if (codemp != null) stmt.setBigDecimal(idx++, codemp);
            rs = stmt.executeQuery();
            return rs.next();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] Erro ao verificar adiantamento existente para NUNOTA=" + nunota, e);
            return false;
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    private Set<BigDecimal> coletarNunotasRelacionados(BigDecimal nunotaBase) {
        Set<BigDecimal> conjunto = new java.util.HashSet<>();
        conjunto.add(nunotaBase);
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        java.sql.PreparedStatement ps = null;
        java.sql.ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement("SELECT DISTINCT NUNOTA, NUNOTAORIG FROM TGFVAR WITH (NOLOCK) WHERE NUNOTA = ? OR NUNOTAORIG = ?");
            ps.setBigDecimal(1, nunotaBase);
            ps.setBigDecimal(2, nunotaBase);
            rs = ps.executeQuery();
            while (rs.next()) {
                BigDecimal n1 = rs.getBigDecimal(1);
                BigDecimal n2 = rs.getBigDecimal(2);
                if (n1 != null) conjunto.add(n1);
                if (n2 != null) conjunto.add(n2);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[VendaPixAdianta] Erro ao coletar NUNOTAs relacionadas para " + nunotaBase, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (ps != null) try { ps.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
        return conjunto;
    }

    private AdiantamentoTask criarTaskFromVenda(DynamicVO cabVO) {
        Object authInfo = null;
        try {
            authInfo = JapeSessionContext.getProperty("authInfo");
        } catch (Exception ignore) {}

        return new AdiantamentoTask(
                cabVO.asBigDecimal("NUNOTA"),
                cabVO.asBigDecimal("CODPARC"),
                cabVO.asBigDecimal("VLRNOTA"),
                cabVO.asTimestamp("DTNEG"),
                cabVO.asBigDecimal("CODEMP"),
                cabVO.asBigDecimal("CODCENCUS"),
                authInfo
        );
    }

    @Override public void beforeInsert(PersistenceEvent event) throws Exception {}
    @Override public void beforeUpdate(PersistenceEvent event) throws Exception {}
    @Override public void beforeDelete(PersistenceEvent event) throws Exception {}
    @Override public void afterInsert(PersistenceEvent event) throws Exception {}
    @Override public void beforeCommit(TransactionContext ctx) throws Exception {}

    /**
     * Se o pedido (TGFCAB) for DELETADO com adiantamento(s) vivo(s) e sem TGFVAR,
     * cancela os adiantamentos relacionados para evitar orfaos em TGFFIN.
     */
    @Override
    public void afterDelete(PersistenceEvent event) throws Exception {
        if (!"CabecalhoNota".equals(event.getEntity().getName())) return;

        BigDecimal nunota = null;
        try {
            DynamicVO cabVO = (DynamicVO) event.getVo();
            if (cabVO == null) return;
            nunota = cabVO.asBigDecimal("NUNOTA");
            if (nunota == null) return;

            LOGGER.info("[VendaPixAdianta] [afterDelete] NUNOTA=" + nunota);

            boolean possuiAdiantamentos = CancelamentoHelper.possuiAdiantamentosRelacionadosComVAR(nunota);
            if (!possuiAdiantamentos) {
                LOGGER.info("[VendaPixAdianta] [afterDelete] Nenhum adiantamento associado - NUNOTA=" + nunota);
                return;
            }

            boolean existeLigacaoVar = CancelamentoHelper.existeLigacaoVarPorNunotaOrig(nunota);
            if (existeLigacaoVar) {
                LOGGER.info("[VendaPixAdianta] [afterDelete] Possui vinculo TGFVAR - adiantamentos mantidos - NUNOTA=" + nunota);
                return;
            }

            LOGGER.info("[VendaPixAdianta] [afterDelete] Pedido deletado sem TGFVAR - cancelando adiantamentos - NUNOTA=" + nunota);
            cancelarAdiantamentosSemVinculo(nunota, "afterDelete sem vinculo TGFVAR");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] Erro em afterDelete - NUNOTA=" + nunota + ": " + e.getMessage(), e);
        }
    }
}
