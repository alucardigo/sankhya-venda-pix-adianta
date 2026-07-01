package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.CancelamentoHelper;
import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Botão de ação personalizado (actionID=35) que substitui a chamada direta à
 * stored procedure {@code sankhya.AD_MARCA_NAO_PENDENTE} por um fluxo Java
 * SÍNCRONO que faz, na mesma transação:
 *
 * <ol>
 *   <li><b>Marca itens como NAO PENDENTE</b> - replica fielmente o que a SP
 *       AD_MARCA_NAO_PENDENTE faz: UPDATE TGFITE SET PENDENTE='N' +
 *       INSERT TGFHIP com histórico. Mantém compatibilidade com o que o
 *       operador esperava.</li>
 *   <li><b>Cancela o adiantamento PIX vinculado</b> via
 *       {@link CancelamentoHelper#cancelarAdiantamentosPorNota(BigDecimal)} -
 *       que executa a sequência V11 validada (UPDATE MONIOCOREM='N',
 *       INSERT TGFRAF baixa Itau, UPDATE PROVISAO='S').</li>
 * </ol>
 *
 * <h3>Por que substituir a "Rotina no Banco de Dados" por esta action Java?</h3>
 *
 * <p>A SP é executada via T-SQL direto. UPDATEs feitos por SP/trigger SQL Server
 * <b>NÃO disparam eventos JAPE</b> (afterUpdate). Logo, o
 * {@code VendaPixAdiantaEvent.afterUpdate} nunca era acionado quando o operador
 * clicava no botão "Marcar Como Não Pendente". Resultado: adiantamento e boleto
 * Itaú ficavam órfãos por até 24h (até o scheduler embedded varrer e detectar).
 *
 * <p>Com esta action Java, a sequência inteira roda dentro da mesma transação
 * JTA do clique, sem depender do scheduler ou de eventos. <b>Cancelamento
 * imediato.</b>
 *
 * <h3>Configuração no Sankhya</h3>
 *
 * <p>No "Dicionário de Dados" > TGFCAB > Ações > "Marcar Como Não Pendente"
 * (actionID=35):
 * <ul>
 *   <li>Mudar <b>Tipo</b> de "Rotina no Banco de Dados" para "Action Java"</li>
 *   <li>Apontar para
 *       {@code br.com.bellube.sankhya.eventos.VendaPixAdianta.action.MarcarNaoPendenteAdiantamentoAction}</li>
 *   <li>Manter parâmetro HISTORICO (Texto) - já é lido por esta action</li>
 * </ul>
 */
public class MarcarNaoPendenteAdiantamentoAction implements AcaoRotinaJava {

    private static final Logger LOGGER = Logger.getLogger(MarcarNaoPendenteAdiantamentoAction.class.getName());

    @Override
    public void doAction(ContextoAcao contexto) throws Exception {
        Registro[] linhas = contexto != null ? contexto.getLinhas() : null;
        if (linhas == null || linhas.length == 0) {
            contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                    "ATENÇÃO\n\nSelecione pelo menos uma nota antes de executar o botão."));
            return;
        }

        // Coleta o parâmetro HISTORICO (mesmo nome que a SP usa)
        String historico = "Marcado como nao pendente via botao Java";
        try {
            Object h = contexto.getParam("HISTORICO");
            if (h != null && !h.toString().trim().isEmpty()) {
                historico = h.toString();
            }
        } catch (Exception ignore) {}

        // Coleta CODUSU logado DIRETO do contexto da action (V16.1).
        // ContextoAcao.getUsuarioLogado() retorna o CODUSU REAL de quem clicou.
        // O codigo antigo usava JapeSession.getProperty("usuario_logado") que
        // retornava null em action button -> caia pro BigDecimal.ZERO -> TGFHIP
        // ficava com Usuario=0 (SUP, usuario padrao do sistema).
        BigDecimal codusu = obterCodusuLogado(contexto);

        int totalNotas = linhas.length;
        int notasOk = 0;
        int notasSemPendente = 0;
        int notasComErro = 0;
        StringBuilder errosDetalhe = new StringBuilder();

        for (Registro linha : linhas) {
            BigDecimal nunota = toBigDecimal(linha.getCampo("NUNOTA"));
            if (nunota == null) {
                notasComErro++;
                errosDetalhe.append("- Linha sem NUNOTA, ignorada\n");
                continue;
            }

            try {
                // PASSO 1: replicar comportamento de AD_MARCA_NAO_PENDENTE
                // (UPDATE TGFITE PENDENTE='N' + INSERT TGFHIP)
                int itensMarcados = marcarItensComoNaoPendente(nunota, codusu, historico);
                if (itensMarcados == 0) {
                    notasSemPendente++;
                    continue;
                }

                // PASSO 2: cancelar adiantamento PIX + pedir baixa do boleto no Itau
                boolean cancelOk = CancelamentoHelper.cancelarAdiantamentosPorNota(nunota);
                if (cancelOk) {
                    notasOk++;
                } else {
                    notasComErro++;
                    errosDetalhe.append("- NUNOTA=").append(nunota)
                            .append(": adiantamento nao foi totalmente desfeito - veja log\n");
                }
            } catch (Exception e) {
                notasComErro++;
                LOGGER.log(Level.SEVERE,
                        "[MarcarNaoPendente] Erro ao processar NUNOTA=" + nunota, e);
                errosDetalhe.append("- NUNOTA=").append(nunota)
                        .append(": ").append(e.getMessage()).append("\n");
            }
        }

        // Mensagem simplificada (V16.1). So mostra detalhe se houve algum erro.
        String msg;
        if (notasComErro > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("Operacao concluida com falhas (")
                    .append(notasComErro).append("/").append(totalNotas).append(").\n\n");
            sb.append(errosDetalhe);
            msg = sb.toString();
        } else if (notasSemPendente == totalNotas) {
            msg = "Nenhuma das notas selecionadas tinha itens pendentes.";
        } else {
            msg = "Operacao concluida com sucesso.";
        }

        contexto.setMensagemRetorno(TextUtils.asciiSanitize(msg));
    }

    /**
     * Replica fielmente AD_MARCA_NAO_PENDENTE (verificada via OBJECT_DEFINITION
     * em SANKHYA_TESTE em 15/05/2026):
     * <pre>
     *   UPDATE TGFITE SET PENDENTE='N'
     *   WHERE NUNOTA=? AND PENDENTE='S'
     *
     *   INSERT INTO TGFHIP (NUNOTA, SEQHIP, SEQUENCIA, CODUSU, DHALTER, STATUS, DESCRICAO)
     *   VALUES (?, SEQ, SEQ, ?, GETDATE(), 'N', ?)
     * </pre>
     *
     * @return quantidade de itens TGFITE marcados como PENDENTE='N'
     */
    private int marcarItensComoNaoPendente(BigDecimal nunota, BigDecimal codusu, String historico)
            throws Exception {
        JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        int itensMarcados = 0;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Coleta as sequências pendentes (replica DECLARE @PENDENTE_SEQUENCIAS da SP)
            List<Integer> sequencias = new ArrayList<>();
            stmt = jdbc.getPreparedStatement(
                    "SELECT SEQUENCIA FROM TGFITE WHERE NUNOTA = ? AND PENDENTE = 'S'");
            stmt.setBigDecimal(1, nunota);
            rs = stmt.executeQuery();
            while (rs.next()) sequencias.add(rs.getInt("SEQUENCIA"));
            rs.close(); rs = null;
            stmt.close(); stmt = null;

            if (sequencias.isEmpty()) return 0;

            // Para cada SEQUENCIA pendente: UPDATE TGFITE + INSERT TGFHIP
            for (Integer seq : sequencias) {
                PreparedStatement u = null;
                PreparedStatement i = null;
                try {
                    u = jdbc.getPreparedStatement(
                            "UPDATE TGFITE SET PENDENTE = 'N' "
                          + "WHERE NUNOTA = ? AND SEQUENCIA = ? AND PENDENTE = 'S'");
                    u.setBigDecimal(1, nunota);
                    u.setInt(2, seq);
                    int n = u.executeUpdate();
                    if (n > 0) itensMarcados++;
                } finally { if (u != null) try { u.close(); } catch (Exception ig) {} }

                try {
                    i = jdbc.getPreparedStatement(
                            "INSERT INTO TGFHIP (NUNOTA, SEQHIP, SEQUENCIA, CODUSU, DHALTER, STATUS, DESCRICAO) "
                          + "VALUES (?, ?, ?, ?, GETDATE(), 'N', ?)");
                    i.setBigDecimal(1, nunota);
                    i.setInt(2, seq);
                    i.setInt(3, seq);
                    i.setBigDecimal(4, codusu != null ? codusu : BigDecimal.ZERO);
                    i.setString(5, historico);
                    i.executeUpdate();
                } finally { if (i != null) try { i.close(); } catch (Exception ig) {} }
            }

            LOGGER.info("[MarcarNaoPendente] NUNOTA=" + nunota + " - "
                    + itensMarcados + " item(ns) marcado(s) PENDENTE='N' + " + sequencias.size()
                    + " linha(s) inserida(s) em TGFHIP");
            return itensMarcados;
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ig) {}
            if (stmt != null) try { stmt.close(); } catch (Exception ig) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ig) {}
        }
    }

    /**
     * V16.1: usa ContextoAcao.getUsuarioLogado() direto (BigDecimal).
     * Fallback para JapeSession.getProperty so se o contexto vier null
     * (nao acontece em uso normal mas defendemos contra NPE).
     */
    private BigDecimal obterCodusuLogado(ContextoAcao contexto) {
        if (contexto != null) {
            try {
                BigDecimal u = contexto.getUsuarioLogado();
                if (u != null) return u;
            } catch (Exception ignore) {}
        }
        try {
            Object u = br.com.sankhya.jape.core.JapeSession.getProperty("usuario_logado");
            if (u != null) return new BigDecimal(u.toString());
        } catch (Exception ignore) {}
        return BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        try { return new BigDecimal(value.toString()); }
        catch (Exception ignore) { return null; }
    }
}
