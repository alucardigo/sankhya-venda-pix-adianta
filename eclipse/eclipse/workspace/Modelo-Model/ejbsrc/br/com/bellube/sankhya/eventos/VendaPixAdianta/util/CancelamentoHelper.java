package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utilitario para cancelamento de adiantamentos quando a nota eh cancelada
 * ou perde o vinculo com a TGFVAR.
 */
public class CancelamentoHelper {

    private static final Logger LOGGER = Logger.getLogger(CancelamentoHelper.class.getName());

    private CancelamentoHelper() {}

    public static boolean notaFoiCancelada(String statusAnterior, String statusAtual) {
        return !"C".equals(statusAnterior) && "C".equals(statusAtual);
    }

    public static void cancelarAdiantamentosPorNota(BigDecimal nunota) throws Exception {
        if (nunota == null) {
            LOGGER.warning("[CancelamentoHelper] NUNOTA nulo - cancelamento ignorado");
            return;
        }

        List<BigDecimal> adiantamentos = buscarAdiantamentosRelacionados(nunota);
        if (adiantamentos.isEmpty()) {
            LOGGER.info("[CancelamentoHelper] Nenhum adiantamento para cancelar - NUNOTA=" + nunota);
            return;
        }

        int cancelados = 0;
        for (BigDecimal numnota : adiantamentos) {
            try {
                cancelarAdiantamento(numnota);
                cancelados++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao cancelar NUMNOTA=" + numnota, e);
            }
        }

        LOGGER.info("[CancelamentoHelper] Cancelamento concluido - " + cancelados + "/" + adiantamentos.size()
                + " adiantamentos cancelados para NUNOTA=" + nunota);
    }

    public static boolean possuiAdiantamentosRelacionados(BigDecimal nunota) {
        if (nunota == null) return false;
        return !buscarAdiantamentosRelacionados(nunota).isEmpty();
    }

    public static boolean existeLigacaoVarPorNunotaOrig(BigDecimal nunota) {
        if (nunota == null) return false;

        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement("SELECT TOP 1 1 FROM TGFVAR WHERE NUNOTAORIG = ?");
            stmt.setBigDecimal(1, nunota);
            rs = stmt.executeQuery();
            boolean existe = rs.next();
            LOGGER.fine("[CancelamentoHelper] TGFVAR NUNOTAORIG=" + nunota + " existe=" + existe);
            return existe;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao verificar TGFVAR para NUNOTAORIG=" + nunota, e);
            return false;
        } finally {
            fechar(rs, stmt, jdbc);
        }
    }

    private static List<BigDecimal> buscarAdiantamentosRelacionados(BigDecimal nunota) {
        List<BigDecimal> adiantamentos = new ArrayList<>();
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(
                    "SELECT DISTINCT NUMNOTA FROM TGFFIN WHERE AD_NUNOTAADIANT = ? AND PROVISAO = 'N'");
            stmt.setBigDecimal(1, nunota);
            rs = stmt.executeQuery();

            while (rs.next()) {
                adiantamentos.add(rs.getBigDecimal("NUMNOTA"));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao buscar adiantamentos para NUNOTA=" + nunota, e);
        } finally {
            fechar(rs, stmt, jdbc);
        }
        return adiantamentos;
    }

    private static void cancelarAdiantamento(BigDecimal numnota) throws Exception {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            stmt = jdbc.getPreparedStatement(
                    "UPDATE TGFFIN SET "
                    + "PROVISAO = 'S', "
                    + "DHBAIXA = GETDATE(), "
                    + "CODHISTBAIXA = 999, "
                    + "AD_NUNOTAADIANT = NULL "
                    + "WHERE NUMNOTA = ? AND PROVISAO = 'N' AND DHBAIXA IS NULL");
            stmt.setBigDecimal(1, numnota);

            int linhas = stmt.executeUpdate();
            if (linhas > 0) {
                LOGGER.info("[CancelamentoHelper] Adiantamento cancelado: NUMNOTA=" + numnota + " (" + linhas + " titulos)");
            } else {
                LOGGER.fine("[CancelamentoHelper] Nenhum titulo cancelavel para NUMNOTA=" + numnota);
            }
        } finally {
            if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    private static void fechar(ResultSet rs, PreparedStatement stmt, br.com.sankhya.jape.dao.JdbcWrapper jdbc) {
        if (rs != null) try { rs.close(); } catch (Exception ignore) {}
        if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
        if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
    }
}
