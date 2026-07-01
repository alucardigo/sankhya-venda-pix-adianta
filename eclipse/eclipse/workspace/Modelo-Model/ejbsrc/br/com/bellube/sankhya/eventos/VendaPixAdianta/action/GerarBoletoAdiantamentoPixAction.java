package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AsyncAdiantamentoProcessor;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.util.JapeSessionContext;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Botao de acao manual: FORCA a geracao de adiantamento PIX + boleto Itau para
 * uma nota (TGFCAB) ja confirmada que NAO passou pelo fluxo automatico do
 * {@code VendaPixAdiantaEvent.afterUpdate}.
 *
 * <h3>Por que esse botao existe</h3>
 *
 * <p>O fluxo automatico so dispara a geracao quando o evento {@code afterUpdate}
 * roda <b>com o campo NUMNOTA no conjunto de campos alterados</b> (atribuicao da
 * pre-nota no faturamento) E a nota qualifica como PIX
 * ({@code TGFTPV.AD_GERAADIANT='S'} do CODTIPVENDA <b>e</b>
 * {@code TGFTOP.AD_GERAADIANT='S'} do CODTIPOPER).
 *
 * <p>Quando um pedido PIX e lancado com uma TOP que ainda nao tinha a flag
 * habilitada (caso real: NUNOTA=4936126, CODPARC=1152, TOP=448), o evento
 * registra "Nota nao qualifica para fluxo PIX" e nao cria nada. Depois que a
 * flag e habilitada, o evento <b>nao re-dispara sozinho</b> (um UPDATE direto
 * no banco nao aciona evento JAPE, e a nota ja esta confirmada). Este botao
 * resolve isso chamando exatamente o mesmo pipeline de producao
 * ({@link AsyncAdiantamentoProcessor#submitTask}) para a NUNOTA selecionada,
 * sem precisar reabrir/refaturar a nota.
 *
 * <h3>Seguranca</h3>
 * <ul>
 *   <li><b>Gate respeitado:</b> o {@code AdiantamentoService} re-valida
 *       AD_GERAADIANT (CODTIPVENDA + CODTIPOPER) ao processar a task. Se a nota
 *       nao qualificar, nada e criado. Este botao tambem checa o gate ANTES e
 *       avisa o operador, evitando clique silencioso sem efeito.</li>
 *   <li><b>Anti-duplicidade:</b> checa se ja existe adiantamento ativo
 *       (TGFFIN RECDESP=1 PROVISAO='N' AD_NUNOTAADIANT=NUNOTA) e a fila async
 *       tem guard proprio (pendingNunotas). Clicar 2x nao gera adiantamento
 *       dobrado.</li>
 * </ul>
 *
 * <h3>Configuracao no Sankhya</h3>
 * <p>Dicionario de Dados &gt; TGFCAB &gt; Acoes &gt; novo botao "Gerar Boleto PIX":
 * Tipo "Action Java", classe
 * {@code br.com.bellube.sankhya.eventos.VendaPixAdianta.action.GerarBoletoAdiantamentoPixAction}.
 */
public class GerarBoletoAdiantamentoPixAction implements AcaoRotinaJava {

    private static final Logger LOGGER = Logger.getLogger(GerarBoletoAdiantamentoPixAction.class.getName());

    @Override
    public void doAction(ContextoAcao contexto) throws Exception {
        Registro[] linhas = contexto != null ? contexto.getLinhas() : null;
        if (linhas == null || linhas.length == 0) {
            contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                    "ATENCAO\n\nSelecione pelo menos uma nota antes de executar o botao."));
            return;
        }

        // CRITICO (V16.3): capturar o authInfo da sessao do operador (thread HTTP
        // autenticada). O worker async roda em outra thread SEM autenticacao; o
        // FinanceiroListener nativo do Sankhya exige authInfo ao salvar TGFFIN
        // (salvarParcelamento -> beforeUpdate -> getAuthenticationInfo ->
        // getRequiredProperty("authInfo")). Sem isso a criacao do adiantamento
        // estoura com RETRIES_EXHAUSTED. O evento afterUpdate ja faz exatamente
        // isto (criarTaskFromVenda passa o authInfo via construtor de 7 args).
        Object authInfo = null;
        try { authInfo = JapeSessionContext.getProperty("authInfo"); } catch (Exception ignore) {}
        if (authInfo == null) {
            LOGGER.warning("[GerarBoletoPix] authInfo AUSENTE na sessao do operador - "
                    + "a criacao do adiantamento provavelmente falhara (FinanceiroListener exige autenticacao).");
        }

        int totalNotas = linhas.length;
        int disparadas = 0;
        int jaTinham = 0;
        int naoQualifica = 0;
        int erros = 0;
        StringBuilder detalhe = new StringBuilder();

        for (Registro linha : linhas) {
            BigDecimal nunota = toBigDecimal(linha.getCampo("NUNOTA"));
            if (nunota == null) {
                erros++;
                detalhe.append("- Linha sem NUNOTA, ignorada\n");
                continue;
            }

            try {
                DadosNota dn = carregarDadosNota(nunota);
                if (dn == null) {
                    erros++;
                    detalhe.append("- NUNOTA=").append(nunota).append(": nota nao encontrada em TGFCAB\n");
                    continue;
                }

                // 1) Anti-duplicidade: ja existe adiantamento ativo?
                if (jaExisteAdiantamentoAtivo(nunota)) {
                    jaTinham++;
                    detalhe.append("- NUNOTA=").append(nunota)
                           .append(": ja possui adiantamento ativo - nada a fazer\n");
                    continue;
                }

                // 2) Gate AD_GERAADIANT (CODTIPVENDA + CODTIPOPER) - feedback claro
                ConfiguracaoHelper.AdiantamentoValidationResult val =
                        ConfiguracaoHelper.verifyGeraAdiantamento(dn.codTipVenda, dn.codTipOper);
                if (!val.success) {
                    naoQualifica++;
                    detalhe.append("- NUNOTA=").append(nunota)
                           .append(": nao qualifica para PIX (").append(val.describe())
                           .append("). Habilite AD_GERAADIANT='S' na TOP/TPV correspondente.\n");
                    LOGGER.info("[GerarBoletoPix] NUNOTA=" + nunota + " nao qualifica - " + val.describe());
                    continue;
                }

                // 3) Valor significativo
                if (dn.vlrnota == null || dn.vlrnota.compareTo(BigDecimal.ZERO) <= 0) {
                    erros++;
                    detalhe.append("- NUNOTA=").append(nunota)
                           .append(": VLRNOTA invalido (").append(dn.vlrnota).append(")\n");
                    continue;
                }

                // 4) Dispara o MESMO pipeline de producao (async -> adiantamento -> boleto Itau).
                //    Construtor de 7 args COM authInfo - sem ele o FinanceiroListener
                //    nativo estoura ao salvar o financeiro no worker async.
                AdiantamentoTask task = new AdiantamentoTask(
                        nunota, dn.codparc, dn.vlrnota, dn.dtneg, dn.codemp, dn.codcencus, authInfo);
                AsyncAdiantamentoProcessor.submitTask(task);
                disparadas++;
                LOGGER.info("[GerarBoletoPix] Geracao DISPARADA manualmente - NUNOTA=" + nunota
                        + " CODPARC=" + dn.codparc + " VLR=" + dn.vlrnota + " CODEMP=" + dn.codemp);
                detalhe.append("- NUNOTA=").append(nunota)
                       .append(": geracao disparada - boleto em ~1-2 min\n");

            } catch (Exception e) {
                erros++;
                LOGGER.log(Level.SEVERE, "[GerarBoletoPix] Erro ao processar NUNOTA=" + nunota, e);
                detalhe.append("- NUNOTA=").append(nunota).append(": ERRO - ").append(e.getMessage()).append("\n");
            }
        }

        // Mensagem final (simples)
        StringBuilder msg = new StringBuilder();
        if (disparadas > 0 && erros == 0 && naoQualifica == 0 && jaTinham == 0 && totalNotas == 1) {
            msg.append("Geracao disparada. O boleto sera registrado no Itau em ~1-2 minutos.\n")
               .append("Atualize a tela 'Acompanhamento de Boletos' e use 'Imprimir Boleto Adiantamento'.");
        } else {
            msg.append("Resultado:\n")
               .append("- Geracoes disparadas: ").append(disparadas).append("\n");
            if (jaTinham > 0)     msg.append("- Ja tinham adiantamento: ").append(jaTinham).append("\n");
            if (naoQualifica > 0) msg.append("- Nao qualificam (TOP/TPV): ").append(naoQualifica).append("\n");
            if (erros > 0)        msg.append("- Com erro: ").append(erros).append("\n");
            if (detalhe.length() > 0) msg.append("\n").append(detalhe);
            if (disparadas > 0)   msg.append("\nBoleto(s) em ~1-2 min - atualize 'Acompanhamento de Boletos'.");
        }

        contexto.setMensagemRetorno(TextUtils.asciiSanitize(msg.toString()));
    }

    /** Verifica se ja existe adiantamento ativo (RECDESP=1 PROVISAO='N') para a NUNOTA. */
    private boolean jaExisteAdiantamentoAtivo(BigDecimal nunota) {
        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement(
                    "SELECT TOP 1 1 FROM TGFFIN WITH (NOLOCK) "
                  + "WHERE AD_NUNOTAADIANT = ? AND RECDESP = 1 AND PROVISAO = 'N'");
            ps.setBigDecimal(1, nunota);
            rs = ps.executeQuery();
            return rs.next();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[GerarBoletoPix] erro check duplicidade NUNOTA=" + nunota, e);
            // Conservador: em duvida, NAO bloqueia (deixa o guard async/service decidir)
            return false;
        } finally {
            fechar(rs, ps, jdbc);
        }
    }

    /** Carrega os campos da TGFCAB necessarios para montar a AdiantamentoTask. */
    private DadosNota carregarDadosNota(BigDecimal nunota) {
        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement(
                    "SELECT CODPARC, VLRNOTA, DTNEG, CODEMP, CODCENCUS, CODTIPVENDA, CODTIPOPER "
                  + "FROM TGFCAB WITH (NOLOCK) WHERE NUNOTA = ?");
            ps.setBigDecimal(1, nunota);
            rs = ps.executeQuery();
            if (!rs.next()) return null;
            DadosNota dn = new DadosNota();
            dn.codparc     = rs.getBigDecimal("CODPARC");
            dn.vlrnota     = rs.getBigDecimal("VLRNOTA");
            dn.dtneg       = rs.getTimestamp("DTNEG");
            dn.codemp      = rs.getBigDecimal("CODEMP");
            dn.codcencus   = rs.getBigDecimal("CODCENCUS");
            dn.codTipVenda = rs.getBigDecimal("CODTIPVENDA");
            dn.codTipOper  = rs.getBigDecimal("CODTIPOPER");
            return dn;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[GerarBoletoPix] erro ao carregar TGFCAB NUNOTA=" + nunota, e);
            return null;
        } finally {
            fechar(rs, ps, jdbc);
        }
    }

    private static final class DadosNota {
        BigDecimal codparc;
        BigDecimal vlrnota;
        Timestamp  dtneg;
        BigDecimal codemp;
        BigDecimal codcencus;
        BigDecimal codTipVenda;
        BigDecimal codTipOper;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        try { return new BigDecimal(value.toString()); }
        catch (Exception ignore) { return null; }
    }

    private void fechar(ResultSet rs, PreparedStatement ps, JdbcWrapper jdbc) {
        if (rs != null) try { rs.close(); } catch (Exception ignore) {}
        if (ps != null) try { ps.close(); } catch (Exception ignore) {}
        if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
    }
}
