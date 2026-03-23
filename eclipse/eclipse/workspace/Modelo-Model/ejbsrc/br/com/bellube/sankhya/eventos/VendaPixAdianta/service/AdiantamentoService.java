package br.com.bellube.sankhya.eventos.VendaPixAdianta.service;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.modelcore.financeiro.util.AdiantamentoEmprestimoHelper;
import br.com.sankhya.modelcore.financeiro.util.AdiantamentoEmprestimoHelper.DadosDespesa;
import br.com.sankhya.modelcore.financeiro.util.DadosParcelamento;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servico responsavel pela criacao de adiantamentos para vendas PIX.
 */
public class AdiantamentoService {

    private static final BigDecimal CODUSU_SISTEMA = new BigDecimal("376");
    private static final Logger LOGGER = Logger.getLogger(AdiantamentoService.class.getName());

    public BigDecimal criarAdiantamentoParaVenda(AdiantamentoTask task) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("Task nao pode ser nula");
        }

        BigDecimal valorNota = task.vlrnota();
        if (valorNota == null || valorNota.compareTo(BigDecimal.ZERO) <= 0) {
            LOGGER.info("[VendaPixAdianta] Valor nao significativo: " + valorNota + " - NUNOTA=" + task.nunota());
            return null;
        }

        if (!isGeraAdiantamentoHabilitado(task.nunota())) {
            LOGGER.info("[VendaPixAdianta] AD_GERAADIANT desabilitada para NUNOTA=" + task.nunota());
            return null;
        }

        LOGGER.info("[VendaPixAdianta] Criando adiantamento NUNOTA=" + task.nunota() + " VALOR=" + valorNota);

        AdiantamentoEmprestimoHelper helper = new AdiantamentoEmprestimoHelper();

        try {
            DadosDespesa dadosDespesa = montarDespesa(task);
            DynamicVO despesaVO = helper.buildDespesaAdiantamento(dadosDespesa, false);
            despesaVO.setProperty("AD_NUNOTAADIANT", task.nunota());

            Object numNotaDespesa = despesaVO.getProperty("NUMNOTA");

            DynamicVO receitaVO = montarReceita(task, dadosDespesa, numNotaDespesa, helper);

            Collection<DynamicVO> titulos = new ArrayList<>(2);
            titulos.add(despesaVO);
            titulos.add(receitaVO);

            helper.salvarParcelamento(titulos, CODUSU_SISTEMA);

            if (numNotaDespesa == null) {
                numNotaDespesa = extrairNumNota(despesaVO, receitaVO);
            }

            AuditLogger.logSuccess(task.nunota());

            if (numNotaDespesa == null) {
                throw new RuntimeException("NUMNOTA do adiantamento nao foi gerado apos salvar parcelamento");
            }

            BigDecimal numNotaReal = (numNotaDespesa instanceof BigDecimal)
                    ? (BigDecimal) numNotaDespesa
                    : new BigDecimal(numNotaDespesa.toString());

            LOGGER.info("[VendaPixAdianta] Adiantamento criado NUMNOTA=" + numNotaReal + " para NUNOTA=" + task.nunota());
            return numNotaReal;

        } catch (Exception e) {
            String msg = "Erro ao criar adiantamento NUNOTA=" + task.nunota() + ": " + e.getMessage();
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] " + msg, e);
            AuditLogger.logError(task.nunota(), msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private boolean isGeraAdiantamentoHabilitado(BigDecimal nunota) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement("SELECT CODTIPVENDA, CODTIPOPER FROM TGFCAB WITH (NOLOCK) WHERE NUNOTA = ?");
            ps.setBigDecimal(1, nunota);
            rs = ps.executeQuery();
            if (!rs.next()) return false;

            BigDecimal codTipVenda = rs.getBigDecimal(1);
            BigDecimal codTipOper = rs.getBigDecimal(2);
            rs.close(); rs = null;
            ps.close(); ps = null;

            return ConfiguracaoHelper.isGeraAdiantamentoHabilitado(codTipVenda, codTipOper);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[VendaPixAdianta] Erro ao validar AD_GERAADIANT para NUNOTA=" + nunota, e);
            return false;
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (ps != null) try { ps.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    private DadosDespesa montarDespesa(AdiantamentoTask task) throws Exception {
        DadosDespesa d = new DadosDespesa();
        d.codigoEmpresa = task.codemp();
        d.codigoParceiro = task.codparc();
        d.valor = task.vlrnota();

        Timestamp dtneg = task.dtneg();
        if (dtneg == null) {
            dtneg = new Timestamp(System.currentTimeMillis());
        }
        d.dataNegociacao = dtneg;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(dtneg.getTime());
        BigDecimal diasVenc = ConfiguracaoHelper.getDiasVencimento();
        cal.add(java.util.Calendar.DAY_OF_MONTH, diasVenc != null ? diasVenc.intValue() : 10);
        d.dataVencimento = new Timestamp(cal.getTimeInMillis());

        d.historico = "Adiantamento aut. ref. venda PIX NUNOTA: " + task.nunota();

        BigDecimal codemp = task.codemp();
        d.codigoTipoOperacao = ConfiguracaoHelper.getCodTopAdiantamento(codemp);
        d.codigoTipoTitulo = ConfiguracaoHelper.getCodTipTitAdiantamento(codemp);
        d.codigoNatureza = ConfiguracaoHelper.getCodNatAdiantamento(codemp);
        d.codigoCentroCusto = task.codcencus() != null ? task.codcencus() : ConfiguracaoHelper.getCodCencusAdiantamento(codemp);
        d.codigoConta = ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(codemp);
        d.codigoProjeto = ConfiguracaoHelper.getCodProjAdiantamento(codemp);
        return d;
    }

    private DynamicVO montarReceita(AdiantamentoTask task, DadosDespesa base, Object numNotaDespesa,
                                    AdiantamentoEmprestimoHelper helper) throws Exception {
        DadosDespesa r = new DadosDespesa();
        r.codigoEmpresa = base.codigoEmpresa;
        r.codigoParceiro = base.codigoParceiro;
        r.valor = base.valor;
        r.dataNegociacao = base.dataNegociacao;
        r.dataVencimento = base.dataVencimento;
        r.historico = "Receita ref. Adiantamento PIX NUNOTA: " + task.nunota();
        r.codigoTipoOperacao = base.codigoTipoOperacao;
        r.codigoTipoTitulo = base.codigoTipoTitulo;
        r.codigoNatureza = base.codigoNatureza;
        r.codigoCentroCusto = base.codigoCentroCusto;
        r.codigoConta = base.codigoConta;
        r.codigoProjeto = base.codigoProjeto;

        DynamicVO receitaVO = helper.buildDespesaAdiantamento(r, true);
        receitaVO.setProperty("AD_NUNOTAADIANT", task.nunota());
        receitaVO.setProperty("RECDESP", BigDecimal.ONE);
        receitaVO.setProperty("ORIGEM", "F");
        receitaVO.setProperty("PROVISAO", "N");
        receitaVO.setProperty("DESDOBRAMENTO", "1");
        receitaVO.setProperty("NUMNOTA", numNotaDespesa);
        receitaVO.setProperty("VLRJUROEMBUT", BigDecimal.ZERO);
        receitaVO.setProperty("VLRJURONEGOC", BigDecimal.ZERO);
        receitaVO.setProperty("VLRMULTA", BigDecimal.ZERO);
        receitaVO.setProperty("ORDEMCARGA", BigDecimal.ZERO);
        receitaVO.setProperty("CODCONTATO", null);
        receitaVO.setProperty("TIMIMOVEL", null);
        receitaVO.setProperty("CODFUNC", null);
        return receitaVO;
    }

    private Object extrairNumNota(DynamicVO despesaVO, DynamicVO receitaVO) {
        try {
            Object n = despesaVO.getProperty("NUMNOTA");
            if (n != null) return n;
            return receitaVO.getProperty("NUMNOTA");
        } catch (Throwable ignore) {
            return null;
        }
    }
}
