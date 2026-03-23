package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

/**
 * Helper de configuracao para adiantamentos PIX.
 * Carrega parametros da tabela AD_TGFCAA com cache TTL de 5 minutos.
 */
public final class ConfiguracaoHelper {

    private static final Logger LOGGER = Logger.getLogger(ConfiguracaoHelper.class.getName());

    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

    private static final ConcurrentHashMap<BigDecimal, CacheEntry> cachePorEmpresa = new ConcurrentHashMap<>();

    private static final class CacheEntry {
        final List<ConfiguracaoAdianta> configs;
        final long timestamp;

        CacheEntry(List<ConfiguracaoAdianta> configs) {
            this.configs = configs;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static class ConfiguracaoAdianta {
        BigDecimal seq;
        BigDecimal codTipOper;
        BigDecimal codTipTit;
        BigDecimal codCtaBcoInt;
        BigDecimal codNat;
        BigDecimal codCenCus;
        BigDecimal codProj;
        BigDecimal codemp;
    }

    private ConfiguracaoHelper() {}

    // --- Getters publicos ---

    public static BigDecimal getCodTopAdiantamento(BigDecimal codemp) {
        return selecionarConfiguracao(codemp, null).codTipOper;
    }

    public static BigDecimal getCodNatAdiantamento(BigDecimal codemp) {
        return selecionarConfiguracao(codemp, null).codNat;
    }

    public static BigDecimal getCodTipTitAdiantamento(BigDecimal codemp) {
        return selecionarConfiguracao(codemp, null).codTipTit;
    }

    public static BigDecimal getCodCencusAdiantamento(BigDecimal codemp) {
        return selecionarConfiguracao(codemp, null).codCenCus;
    }

    public static BigDecimal getCodCtaBcoIntAdiantamento(BigDecimal codemp) {
        return selecionarConfiguracao(codemp, null).codCtaBcoInt;
    }

    public static BigDecimal getCodProjAdiantamento(BigDecimal codemp) {
        BigDecimal v = selecionarConfiguracao(codemp, null).codProj;
        return v != null ? v : BigDecimal.ZERO;
    }

    public static BigDecimal getDiasVencimento() {
        return new BigDecimal("10");
    }

    // Conveniencia para chamadas sem CODEMP (assume empresa 1)
    public static BigDecimal getCodTopAdiantamento() { return getCodTopAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getCodNatAdiantamento() { return getCodNatAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getCodTipTitAdiantamento() { return getCodTipTitAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getCodCencusAdiantamento() { return getCodCencusAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getCodCtaBcoIntAdiantamento() { return getCodCtaBcoIntAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getCodProjAdiantamento() { return getCodProjAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getTipoTituloPix() { return getCodTipTitAdiantamento(BigDecimal.ONE); }
    public static BigDecimal getTipoVendaPix() { return new BigDecimal("290"); }
    public static BigDecimal getCodTopAdiantamentoRec() { return getCodTopAdiantamento(); }

    public static void clearCache() {
        cachePorEmpresa.clear();
    }

    public static String getCacheInfo() {
        return "Empresas em cache: " + cachePorEmpresa.size();
    }

    // --- Validacao AD_GERAADIANT ---

    public static boolean isGeraAdiantamentoHabilitado(BigDecimal codTipVenda, BigDecimal codTipOper) {
        try {
            return verifyGeraAdiantamento(codTipVenda, codTipOper).success;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ConfiguracaoHelper] Falha ao validar AD_GERAADIANT", e);
            return false;
        }
    }

    public static AdiantamentoValidationResult verifyGeraAdiantamento(BigDecimal codTipVenda, BigDecimal codTipOper) throws Exception {
        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        boolean tpvS = false, topS = false, tpvRecord = false, topRecord = false;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            if (codTipVenda != null) {
                ps = jdbc.getPreparedStatement("SELECT TOP 1 AD_GERAADIANT FROM TGFTPV WITH (NOLOCK) WHERE CODTIPVENDA = ? ORDER BY DHALTER DESC");
                ps.setBigDecimal(1, codTipVenda);
                rs = ps.executeQuery();
                if (rs.next()) {
                    tpvRecord = true;
                    String v = rs.getString(1);
                    tpvS = "S".equalsIgnoreCase(v != null ? v.trim() : "");
                }
                rs.close(); rs = null;
                ps.close(); ps = null;
            }
            if (codTipOper != null) {
                ps = jdbc.getPreparedStatement("SELECT TOP 1 AD_GERAADIANT FROM TGFTOP WITH (NOLOCK) WHERE CODTIPOPER = ? ORDER BY DHALTER DESC");
                ps.setBigDecimal(1, codTipOper);
                rs = ps.executeQuery();
                if (rs.next()) {
                    topRecord = true;
                    String topFlag = rs.getString(1);
                    topS = "S".equalsIgnoreCase(topFlag != null ? topFlag.trim() : "");
                }
            }
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (ps != null) try { ps.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }

        return new AdiantamentoValidationResult(codTipVenda, codTipOper, tpvS, topS, tpvRecord, topRecord, tpvS && topS);
    }

    public static final class AdiantamentoValidationResult {
        public final BigDecimal codTipVenda;
        public final BigDecimal codTipOper;
        public final boolean tpvFlag;
        public final boolean topFlag;
        public final boolean tpvRecordFound;
        public final boolean topRecordFound;
        public final boolean success;

        AdiantamentoValidationResult(BigDecimal codTipVenda, BigDecimal codTipOper,
                                     boolean tpvFlag, boolean topFlag,
                                     boolean tpvRecordFound, boolean topRecordFound, boolean success) {
            this.codTipVenda = codTipVenda;
            this.codTipOper = codTipOper;
            this.tpvFlag = tpvFlag;
            this.topFlag = topFlag;
            this.tpvRecordFound = tpvRecordFound;
            this.topRecordFound = topRecordFound;
            this.success = success;
        }

        public String describe() {
            return "CODTIPVENDA=" + codTipVenda
                    + " (registro=" + tpvRecordFound + " flag=" + (tpvFlag ? "S" : "N") + ")"
                    + ", CODTIPOPER=" + codTipOper
                    + " (registro=" + topRecordFound + " flag=" + (topFlag ? "S" : "N") + ")";
        }
    }

    // --- Internals ---

    private static List<ConfiguracaoAdianta> carregarConfiguracoes(BigDecimal codemp) {
        if (codemp == null) throw new IllegalArgumentException("CODEMP nao pode ser nulo");

        CacheEntry cached = cachePorEmpresa.get(codemp);
        if (cached != null && !cached.isExpired()) {
            return cached.configs;
        }

        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        List<ConfiguracaoAdianta> lista = new ArrayList<>();
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(
                    "SELECT SEQ, CODTIPOPER, CODTIPTIT, CODCTABCOINT, CODNAT, CODCENCUS, CODPROJ, CODEMP "
                    + "FROM AD_TGFCAA WHERE CODEMP = ? ORDER BY SEQ");
            stmt.setBigDecimal(1, codemp);
            rs = stmt.executeQuery();
            while (rs.next()) {
                ConfiguracaoAdianta c = new ConfiguracaoAdianta();
                c.seq = rs.getBigDecimal("SEQ");
                c.codTipOper = rs.getBigDecimal("CODTIPOPER");
                c.codTipTit = rs.getBigDecimal("CODTIPTIT");
                c.codCtaBcoInt = rs.getBigDecimal("CODCTABCOINT");
                c.codNat = rs.getBigDecimal("CODNAT");
                c.codCenCus = rs.getBigDecimal("CODCENCUS");
                c.codProj = rs.getBigDecimal("CODPROJ");
                c.codemp = rs.getBigDecimal("CODEMP");
                lista.add(c);
            }
            List<ConfiguracaoAdianta> resultado = Collections.unmodifiableList(lista);
            cachePorEmpresa.put(codemp, new CacheEntry(resultado));
            return resultado;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ConfiguracaoHelper] Erro ao carregar AD_TGFCAA para CODEMP=" + codemp, e);
            throw new IllegalStateException("Falha ao carregar configuracao para CODEMP=" + codemp, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    private static ConfiguracaoAdianta selecionarConfiguracao(BigDecimal codemp, BigDecimal codcencus) {
        List<ConfiguracaoAdianta> confs = carregarConfiguracoes(codemp);
        if (confs.isEmpty()) {
            throw new IllegalStateException("Nenhuma configuracao AD_TGFCAA encontrada para CODEMP=" + codemp);
        }

        ConfiguracaoAdianta melhor = null;
        for (ConfiguracaoAdianta c : confs) {
            if (c.codemp == null || c.codemp.compareTo(codemp) != 0) continue;
            if (codcencus != null && c.codCenCus != null && c.codCenCus.compareTo(codcencus) == 0) {
                melhor = c;
                break;
            }
            if (melhor == null) melhor = c;
        }

        if (melhor == null) {
            throw new IllegalStateException("Configuracao nao encontrada para CODEMP=" + codemp);
        }

        validarContaPertenceEmpresa(melhor.codCtaBcoInt, codemp);
        return melhor;
    }

    private static void validarContaPertenceEmpresa(BigDecimal codCtaBcoInt, BigDecimal codemp) {
        if (codCtaBcoInt == null) {
            throw new IllegalStateException("CODCTABCOINT nao configurado para CODEMP=" + codemp);
        }

        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement("SELECT CODEMP FROM TSICTA WHERE CODCTABCOINT = ?");
            stmt.setBigDecimal(1, codCtaBcoInt);
            rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal emp = rs.getBigDecimal("CODEMP");
                if (emp == null || emp.compareTo(codemp) != 0) {
                    throw new IllegalStateException("Conta " + codCtaBcoInt + " nao pertence a empresa " + codemp);
                }
            } else {
                throw new IllegalStateException("Conta " + codCtaBcoInt + " inexistente em TSICTA");
            }
        } catch (IllegalStateException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao validar CODEMP/CODCTA: " + e.getMessage(), e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    // --- Suporte a testes ---

    public static void setTestConfiguracoes(BigDecimal codemp, List<Map<String, BigDecimal>> rows) {
        if (rows == null) {
            cachePorEmpresa.put(codemp, new CacheEntry(Collections.emptyList()));
            return;
        }
        List<ConfiguracaoAdianta> list = new ArrayList<>();
        for (Map<String, BigDecimal> m : rows) {
            ConfiguracaoAdianta c = new ConfiguracaoAdianta();
            c.seq = m.get("SEQ");
            c.codTipOper = m.get("CODTIPOPER");
            c.codTipTit = m.get("CODTIPTIT");
            c.codCtaBcoInt = m.get("CODCTABCOINT");
            c.codNat = m.get("CODNAT");
            c.codCenCus = m.get("CODCENCUS");
            c.codProj = m.get("CODPROJ");
            c.codemp = codemp;
            list.add(c);
        }
        cachePorEmpresa.put(codemp, new CacheEntry(Collections.unmodifiableList(list)));
    }

    private static Map<BigDecimal, BigDecimal> testContaEmpMap;

    public static void setTestContaEmpresaMap(Map<BigDecimal, BigDecimal> map) {
        testContaEmpMap = map;
    }
}
