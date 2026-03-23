package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoConstants.*;

/**
 * Repository for Boleto database operations.
 * Handles all SQL queries related to boleto and financial records.
 * 
 * Single Responsibility: Database access only
 * 
 * @author VendaPixAdianta Module
 * @version 5.0.0 - Professional Refactoring
 */
public class BoletoRepository {

    private static final Logger LOGGER = Logger.getLogger(BoletoRepository.class.getName());

    /**
     * Busca o NUMNOTA do adiantamento gerado a partir de uma venda PIX.
     * 
     * Utiliza o campo AD_NUNOTAADIANT que é preenchido pelo AdiantamentoService
     * quando cria os títulos de adiantamento (receita e despesa).
     * 
     * @param nunotaVenda NUNOTA da venda PIX original
     * @return NUMNOTA do boleto de adiantamento, ou null se não encontrado
     */
    public BigDecimal buscarNumnotaAdiantamento(BigDecimal nunotaVenda) {
        if (nunotaVenda == null) {
            LOGGER.warning(LOG_PREFIX + "buscarNumnotaAdiantamento chamado com NUNOTA null");
            return null;
        }

        // Query corrigida: busca diretamente pela ligação AD_NUNOTAADIANT
        // que é criada pelo AdiantamentoService ao gerar o adiantamento PIX
        String sql = "SELECT TOP 1 " + COL_NUMNOTA + " " +
                "FROM " + TABLE_TGFFIN + " " +
                "WHERE " + COL_AD_NUNOTAADIANT + " = ? " +
                "AND " + COL_RECDESP + " = " + RECDESP_RECEITA + " " +
                "ORDER BY " + COL_NUFIN + " DESC";

        LOGGER.info(LOG_PREFIX + "Buscando adiantamento para NUNOTA=" + nunotaVenda + 
                " usando campo AD_NUNOTAADIANT");

        BigDecimal result = executeSingleBigDecimalQuery(sql, nunotaVenda);
        
        if (result != null) {
            LOGGER.info(LOG_PREFIX + "Adiantamento encontrado: NUMNOTA=" + result + 
                    " para venda NUNOTA=" + nunotaVenda);
            return result;
        } else {
            LOGGER.info(LOG_PREFIX + "Nenhum adiantamento encontrado para NUNOTA=" + nunotaVenda +
                    " (verifique se AD_NUNOTAADIANT esta preenchido na TGFFIN). Tentando fallback por NUNOTA.");
        }

        // Fallback: tenta localizar boleto diretamente pelos titulos da propria nota
        String sqlFallback = "SELECT TOP 1 " + COL_NUMNOTA + " " +
                "FROM " + TABLE_TGFFIN + " " +
                "WHERE " + COL_NUNOTA + " = ? " +
                "AND " + COL_RECDESP + " = " + RECDESP_RECEITA + " " +
                "ORDER BY " + COL_NUFIN + " DESC";

        BigDecimal fallback = executeSingleBigDecimalQuery(sqlFallback, nunotaVenda);
        if (fallback != null) {
            LOGGER.warning(LOG_PREFIX + "Fallback ativado: usando NUMNOTA=" + fallback +
                    " encontrado diretamente na TGFFIN para NUNOTA=" + nunotaVenda);
        } else {
            LOGGER.info(LOG_PREFIX + "Fallback nao localizou titulos de receita para NUNOTA=" + nunotaVenda);
        }

        return fallback;
    }

    /**
     * Busca o NUFIN correspondente a um NUMNOTA.
     * 
     * @param numnota NUMNOTA do boleto
     * @return NUFIN correspondente, ou null se não encontrado
     */
    public BigDecimal buscarNufinPorNumnota(BigDecimal numnota) {
        if (numnota == null) {
            return null;
        }

        String sql = "SELECT TOP 1 " + COL_NUFIN + " " +
                "FROM " + TABLE_TGFFIN + " " +
                "WHERE " + COL_NUMNOTA + " = ? " +
                "AND " + COL_RECDESP + " = " + RECDESP_RECEITA + " " +
                "ORDER BY " + COL_NUFIN + " DESC";

        return executeSingleBigDecimalQuery(sql, numnota);
    }

    /**
     * Verifica se o Nosso Número já foi gerado para um título.
     * 
     * @param nufin NUFIN do título
     * @return true se o Nosso Número existe e não é vazio
     */
    public boolean verificarNossoNumeroExiste(BigDecimal nufin) {
        if (nufin == null) {
            return false;
        }

        String sql = "SELECT " + COL_NOSSONUM + " " +
                "FROM " + TABLE_TGFFIN + " " +
                "WHERE " + COL_NUFIN + " = ? " +
                "AND " + COL_NOSSONUM + " IS NOT NULL";

        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(sql);
            stmt.setBigDecimal(1, nufin);
            rs = stmt.executeQuery();

            if (rs.next()) {
                String nossoNum = rs.getString(COL_NOSSONUM);
                return nossoNum != null && !nossoNum.trim().isEmpty();
            }

            return false;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao verificar Nosso Numero para NUFIN=" + nufin, e);
            return false;
        } finally {
            closeResources(rs, stmt, jdbc);
        }
    }

    /**
     * Busca o código da empresa (CODEMP) de uma nota.
     * 
     * @param nunota NUNOTA da nota
     * @return CODEMP, ou null se não encontrado
     */
    public BigDecimal buscarCodempPorNunota(BigDecimal nunota) {
        if (nunota == null) {
            return null;
        }

        String sql = "SELECT " + COL_CODEMP + " " +
                "FROM " + TABLE_TGFCAB + " " +
                "WHERE " + COL_NUNOTA + " = ?";

        return executeSingleBigDecimalQuery(sql, nunota);
    }

    /**
     * Obtém o código da conta bancária (CODCTABCOINT) configurada para uma empresa.
     * 
     * @param codEmp Código da empresa
     * @return CODCTABCOINT, ou null se não encontrado
     */
    public BigDecimal obterCodCtaBcoInt(BigDecimal codEmp) {
        if (codEmp == null) {
            return null;
        }

        String sql = "SELECT " + COL_CODCTABCOINT + " " +
                "FROM " + TABLE_AD_TGFCAA + " " +
                "WHERE " + COL_CODEMP + " = ?";

        return executeSingleBigDecimalQuery(sql, codEmp);
    }

    /**
     * Obtém o código do relatório/modelo de boleto configurado para uma conta bancária.
     * 
     * @param codCtaBcoInt Código da conta bancária interna
     * @return Código do modelo de boleto, ou valor padrão se não encontrado
     */
    public BigDecimal obterCodigoRelatorio(BigDecimal codCtaBcoInt) {
        if (codCtaBcoInt == null) {
            LOGGER.warning(LOG_PREFIX + "CODCTABCOINT nulo para obtenção de modelo de boleto");
            return null;
        }

        String sqlTsicta = "SELECT " + COL_MODBOLETA + " " +
                "FROM " + TABLE_TSICTA + " " +
                "WHERE " + COL_CODCTABCOINT + " = ?";

        BigDecimal codigo = executeSingleBigDecimalQuery(sqlTsicta, codCtaBcoInt);

        if (codigo != null) {
            return codigo;
        }

        LOGGER.warning(LOG_PREFIX + "Modelo de boleto não configurado em TSICTA para CODCTABCOINT=" + codCtaBcoInt);
        return null;
    }

    /**
     * Obtém o CODCTABCOINT diretamente de um título financeiro.
     * 
     * @param nufin NUFIN do título
     * @return CODCTABCOINT, ou null se não encontrado
     */
    public BigDecimal obterCodCtaBcoIntDoTitulo(BigDecimal nufin) {
        if (nufin == null) {
            return null;
        }

        String sql = "SELECT " + COL_CODCTABCOINT + " " +
                "FROM " + TABLE_TGFFIN + " " +
                "WHERE " + COL_NUFIN + " = ?";

        BigDecimal result = executeSingleBigDecimalQuery(sql, nufin);

        if (result == null) {
            LOGGER.warning(LOG_PREFIX + "CODCTABCOINT não encontrado para NUFIN=" + nufin);
        }

        return result;
    }

    /**
     * Obtém valores configurados na TSIPAR.
     *
     * @param chave Parâmetro
     * @return Conteúdo ou null
     */
    public String buscarParametroSistema(String chave) {
        if (chave == null || chave.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT CONTEUDO FROM TSIPAR WHERE CHAVE = ?";

        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(sql);
            stmt.setString(1, chave.trim());
            rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("CONTEUDO");
            }
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao buscar TSIPAR " + chave, e);
            return null;
        } finally {
            closeResources(rs, stmt, jdbc);
        }
    }

    /**
     * Executa uma query SQL que retorna um único BigDecimal.
     * Utility method para reduzir duplicação de código.
     * 
     * @param sql Query SQL
     * @param params Parâmetros da query
     * @return Valor BigDecimal, ou null se não encontrado
     */
    private BigDecimal executeSingleBigDecimalQuery(String sql, BigDecimal... params) {
        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(sql);

            // Set parameters
            for (int i = 0; i < params.length; i++) {
                stmt.setBigDecimal(i + 1, params[i]);
            }

            rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getBigDecimal(1);
            }

            return null;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOG_PREFIX + "Erro ao executar query: " + sql, e);
            return null;
        } finally {
            closeResources(rs, stmt, jdbc);
        }
    }

    /**
     * Fecha recursos JDBC de forma segura.
     * 
     * @param rs ResultSet
     * @param stmt PreparedStatement
     * @param jdbc JdbcWrapper
     */
    private void closeResources(ResultSet rs, PreparedStatement stmt, JdbcWrapper jdbc) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, LOG_PREFIX + "Erro ao fechar ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, LOG_PREFIX + "Erro ao fechar PreparedStatement", e);
            }
        }

        if (jdbc != null) {
            try {
                jdbc.closeSession();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, LOG_PREFIX + "Erro ao fechar JdbcWrapper", e);
            }
        }
    }

    public BigDecimal reservarRemessaBancaria(BigDecimal codCtaBcoInt) {
        JdbcWrapper jdbc = null;
        PreparedStatement stmtSel = null;
        PreparedStatement stmtUpd = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            String sqlSel = "SELECT (REMBCO + 1) AS NEXTREM FROM " + TABLE_TSICTA + " WITH(UPDLOCK,HOLDLOCK) WHERE " + COL_CODCTABCOINT + " = ?";
            stmtSel = jdbc.getPreparedStatement(sqlSel);
            stmtSel.setBigDecimal(1, codCtaBcoInt);
            rs = stmtSel.executeQuery();
            if (!rs.next()) {
                return null;
            }
            BigDecimal next = rs.getBigDecimal(1);
            String sqlUpd = "UPDATE " + TABLE_TSICTA + " SET REMBCO = ? WHERE " + COL_CODCTABCOINT + " = ?";
            stmtUpd = jdbc.getPreparedStatement(sqlUpd);
            stmtUpd.setBigDecimal(1, next);
            stmtUpd.setBigDecimal(2, codCtaBcoInt);
            stmtUpd.executeUpdate();
            return next;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao reservar remessa bancária para CODCTABCOINT=" + codCtaBcoInt, e);
            return null;
        } finally {
            closeResources(rs, stmtSel, null);
            if (stmtUpd != null) {
                try { stmtUpd.close(); } catch (Exception ignore) {}
            }
            if (jdbc != null) {
                try { jdbc.closeSession(); } catch (Exception ignore) {}
            }
        }
    }
}
