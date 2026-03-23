package br.com.bellube.sankhya.eventos.VendaPixAdianta.rules;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.com.sankhya.extensions.regrasnegocio.ContextoRegra;
import br.com.sankhya.extensions.regrasnegocio.RegraNegocioJava;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

/**
 * Regra de negocio que impede a confirmacao de notas que possuem adiantamentos PIX
 * pendentes de baixa (receita nao paga).
 *
 * Evento de liberacao: 1004 (Pagamento Antecipado PIX)
 *
 * CORRECAO CRITICA: A deteccao de contexto PIX agora usa duas estrategias:
 *   1. Verificacao por flags AD_GERAADIANT (TGFTPV + TGFTOP) - abordagem original
 *   2. Verificacao por existencia REAL de adiantamento na TGFFIN (AD_NUNOTAADIANT) - fallback
 *
 * A estrategia 2 resolve o bug onde notas parcialmente faturadas trocam de CODTIPOPER
 * (ex: 326 -> 32) e a regra nao detectava o vinculo com adiantamento existente.
 */
public class RegraBaixaAdiantamentoConfirmacao implements RegraNegocioJava {

    private static final Logger LOGGER = Logger.getLogger(RegraBaixaAdiantamentoConfirmacao.class.getName());

    @Override
    public void executa(ContextoRegra contexto) throws Exception {
        BigDecimal nunota = contexto.getNunota();
        if (nunota == null) return;

        Set<BigDecimal> nunotasRelacionadas = coletarNunotasRelacionados(nunota);

        boolean ehContextoPIX = contextoEhAdiantamentoPIX(nunota, nunotasRelacionadas);

        if (!ehContextoPIX) {
            LOGGER.fine("[RegraBaixaAdiantamento] Nota " + nunota + " nao associada a adiantamento PIX");
            return;
        }

        BigDecimal codparc = obterCodParc(nunota);
        if (codparc == null) {
            LOGGER.warning("[RegraBaixaAdiantamento] CODPARC nulo para NUNOTA=" + nunota + " - bloqueando por seguranca");
            bloquearConfirmacao(contexto);
            return;
        }

        if (adiantamentoBaixadoParaAlgum(nunotasRelacionadas, codparc)) {
            LOGGER.info("[RegraBaixaAdiantamento] Adiantamento ja baixado para NUNOTA=" + nunota + " - confirmacao liberada");
            return;
        }

        LOGGER.info("[RegraBaixaAdiantamento] Bloqueando confirmacao - adiantamento nao baixado - NUNOTA=" + nunota + ", CODPARC=" + codparc);
        bloquearConfirmacao(contexto);
    }

    private void bloquearConfirmacao(ContextoRegra contexto) {
        contexto.setMensagem("Confirmacao impedida: baixa do adiantamento nao realizada. "
                + "O boleto do adiantamento PIX precisa ser pago antes da confirmacao.");
        contexto.setSucesso(false);
        contexto.setCodUsuLib(BigDecimal.ZERO);
    }

    /**
     * Detecta se a nota esta em contexto de adiantamento PIX usando duas estrategias:
     *
     * 1) Flags AD_GERAADIANT nas tabelas TGFTPV e TGFTOP (checagem por configuracao)
     * 2) Existencia real de adiantamento na TGFFIN com AD_NUNOTAADIANT (checagem por dado concreto)
     *
     * A estrategia 2 eh o fallback critico que resolve o bug de faturamento parcial.
     */
    private boolean contextoEhAdiantamentoPIX(BigDecimal nunotaBase, Set<BigDecimal> candidatos) {
        // Estrategia 1: verificar flags AD_GERAADIANT nas notas relacionadas
        for (BigDecimal n : candidatos) {
            if (n == null) continue;
            try {
                if (tipoNegociacaoGeraAdiantamento(n)) {
                    LOGGER.fine("[RegraBaixaAdiantamento] Contexto PIX detectado via AD_GERAADIANT (NUNOTA=" + n + ")");
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[RegraBaixaAdiantamento] Erro ao checar AD_GERAADIANT para NUNOTA=" + n, e);
            }
        }

        // Estrategia 2 (FALLBACK CRITICO): verificar se existe adiantamento REAL na TGFFIN
        // Isso cobre o caso de faturamento parcial onde o CODTIPOPER muda e as flags nao batem mais
        try {
            if (existeAdiantamentoNaFinanceiro(candidatos)) {
                LOGGER.info("[RegraBaixaAdiantamento] Contexto PIX detectado via TGFFIN (adiantamento real existe) para NUNOTA=" + nunotaBase);
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[RegraBaixaAdiantamento] Erro ao checar adiantamento na TGFFIN para NUNOTA=" + nunotaBase, e);
        }

        return false;
    }

    /**
     * Verifica se existe pelo menos um registro de adiantamento na TGFFIN
     * vinculado a qualquer uma das NUNOTAs relacionadas.
     * Isso detecta adiantamentos independente do CODTIPOPER atual da nota.
     */
    private boolean existeAdiantamentoNaFinanceiro(Set<BigDecimal> nunotas) throws Exception {
        if (nunotas == null || nunotas.isEmpty()) return false;

        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc.openSession();
            List<BigDecimal> lista = new ArrayList<>(nunotas);
            StringBuilder sql = new StringBuilder(
                    "SELECT TOP 1 1 FROM TGFFIN WHERE AD_NUNOTAADIANT IN (");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(") AND PROVISAO = 'N'");

            ps = jdbc.getPreparedStatement(sql.toString());
            for (int i = 0; i < lista.size(); i++) {
                ps.setBigDecimal(i + 1, lista.get(i));
            }
            rs = ps.executeQuery();
            return rs.next();
        } finally {
            fecharRecursos(rs, ps, jdbc);
        }
    }

    /**
     * Verifica se o adiantamento (receita) ja foi baixado para alguma das notas relacionadas.
     */
    private boolean adiantamentoBaixadoParaAlgum(Set<BigDecimal> nunotas, BigDecimal codparc) throws Exception {
        if (nunotas == null || nunotas.isEmpty() || codparc == null) return false;

        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc.openSession();
            List<BigDecimal> lista = new ArrayList<>(nunotas);
            StringBuilder sql = new StringBuilder(
                    "SELECT TOP 1 1 FROM TGFFIN WHERE AD_NUNOTAADIANT IN (");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(") AND CODPARC = ? AND RECDESP = 1 AND DHBAIXA IS NOT NULL AND PROVISAO = 'N'");

            ps = jdbc.getPreparedStatement(sql.toString());
            for (int i = 0; i < lista.size(); i++) {
                ps.setBigDecimal(i + 1, lista.get(i));
            }
            ps.setBigDecimal(lista.size() + 1, codparc);
            rs = ps.executeQuery();
            return rs.next();
        } finally {
            fecharRecursos(rs, ps, jdbc);
        }
    }

    /**
     * Coleta todas as NUNOTAs relacionadas via TGFVAR (variantes/faturamento parcial).
     */
    private Set<BigDecimal> coletarNunotasRelacionados(BigDecimal nunotaBase) {
        Set<BigDecimal> conjunto = new HashSet<>();
        conjunto.add(nunotaBase);

        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement(
                    "SELECT DISTINCT NUNOTA, NUNOTAORIG FROM TGFVAR WHERE NUNOTA = ? OR NUNOTAORIG = ?");
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
            LOGGER.log(Level.WARNING, "[RegraBaixaAdiantamento] Erro ao coletar NUNOTAs relacionadas para " + nunotaBase, e);
        } finally {
            fecharRecursos(rs, ps, jdbc);
        }
        return conjunto;
    }

    /**
     * Verifica se a nota tem CODTIPVENDA e CODTIPOPER com AD_GERAADIANT='S'.
     * Consolidado em uma unica sessao JDBC para evitar N+1 queries.
     */
    private boolean tipoNegociacaoGeraAdiantamento(BigDecimal nunota) throws Exception {
        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc.openSession();

            // Query 1: obter CODTIPVENDA e CODTIPOPER da nota
            ps = jdbc.getPreparedStatement("SELECT CODTIPVENDA, CODTIPOPER FROM TGFCAB WHERE NUNOTA = ?");
            ps.setBigDecimal(1, nunota);
            rs = ps.executeQuery();

            BigDecimal codTipVenda = null;
            BigDecimal codTipOper = null;
            if (rs.next()) {
                codTipVenda = rs.getBigDecimal(1);
                codTipOper = rs.getBigDecimal(2);
            }
            rs.close(); rs = null;
            ps.close(); ps = null;

            if (codTipVenda == null || codTipOper == null) return false;

            // Query 2: checar AD_GERAADIANT na TGFTPV
            boolean geraPorTpv = false;
            ps = jdbc.getPreparedStatement(
                    "SELECT TOP 1 AD_GERAADIANT FROM TGFTPV WHERE CODTIPVENDA = ? ORDER BY DHALTER DESC");
            ps.setBigDecimal(1, codTipVenda);
            rs = ps.executeQuery();
            if (rs.next()) {
                String flag = rs.getString(1);
                geraPorTpv = "S".equalsIgnoreCase(flag != null ? flag.trim() : "");
            }
            rs.close(); rs = null;
            ps.close(); ps = null;

            if (!geraPorTpv) return false;

            // Query 3: checar AD_GERAADIANT na TGFTOP
            boolean geraPorTop = false;
            ps = jdbc.getPreparedStatement(
                    "SELECT TOP 1 AD_GERAADIANT FROM TGFTOP WHERE CODTIPOPER = ? ORDER BY DHALTER DESC");
            ps.setBigDecimal(1, codTipOper);
            rs = ps.executeQuery();
            if (rs.next()) {
                String topFlag = rs.getString(1);
                geraPorTop = "S".equalsIgnoreCase(topFlag != null ? topFlag.trim() : "");
            }

            return geraPorTpv && geraPorTop;
        } finally {
            fecharRecursos(rs, ps, jdbc);
        }
    }

    private BigDecimal obterCodParc(BigDecimal nunota) {
        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement("SELECT CODPARC FROM TGFCAB WHERE NUNOTA = ?");
            ps.setBigDecimal(1, nunota);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getBigDecimal(1);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[RegraBaixaAdiantamento] Erro ao obter CODPARC para NUNOTA=" + nunota, e);
        } finally {
            fecharRecursos(rs, ps, jdbc);
        }
        return null;
    }

    private static void fecharRecursos(ResultSet rs, PreparedStatement ps, JdbcWrapper jdbc) {
        if (rs != null) try { rs.close(); } catch (Exception ignore) {}
        if (ps != null) try { ps.close(); } catch (Exception ignore) {}
        if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
    }
}
