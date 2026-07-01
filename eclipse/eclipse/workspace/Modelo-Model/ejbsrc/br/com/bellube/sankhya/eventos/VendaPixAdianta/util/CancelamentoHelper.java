package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utilitario para cancelamento (DESFAZER) de adiantamentos PIX.
 *
 * <h2>📌 LIÇÃO APRENDIDA NO PROJETO (V15) - LEIA ANTES DE EDITAR</h2>
 *
 * <p><b>NUMNOTA != NUMDUPL na TGFFIN.</b> Esse foi o erro que custou ~12
 * iterações sem resolver. Quem editar este arquivo no futuro: ATENÇÃO.
 *
 * <p>No dicionário do Sankhya, em adiantamentos (DESDOBDUPL='ZZ'):
 * <ul>
 *   <li><b>NUMNOTA</b> = número sequencial gerado pelo
 *       AdiantamentoEmprestimoHelper.buildDespesaAdiantamento (ex: 4206).
 *       É o número INTERNO da nota financeira do adiantamento.</li>
 *   <li><b>NUMDUPL</b> = número do <b>ACERTO de Adiantamento</b> (= NUACERTO
 *       em TGFFRE TIPACERTO='A'). É o número que aparece na TELA
 *       "Adiantamento/Empréstimo" do Sankhya na coluna "Adiant./Emp." (ex: 13947).</li>
 * </ul>
 *
 * <p>O método nativo {@code AdiantamentoEmprestimoHelper.desfazerAdiantamento(BigDecimal)}
 * espera <b>NUMDUPL</b>. Os DELETEs que removem fisicamente o adiantamento
 * (igual ao botão "Desfazer" da tela) também usam NUMDUPL:
 * <pre>
 *   DELETE FROM TGFFRE WHERE NUACERTO = NUMDUPL AND TIPACERTO = 'A'
 *   DELETE FROM TGFFIN WHERE DESDOBDUPL='ZZ' AND RECDESP&lt;&gt;0
 *                        AND DHBAIXA IS NULL AND NUMDUPL = ?
 * </pre>
 *
 * <p>Versões V9-V14 deste código sempre passavam NUMNOTA para esses DELETEs.
 * Resultado: <b>nunca encontravam nada</b>, caía no fallback UPDATE PROVISAO='S'
 * que NÃO removia o registro - o adiantamento ficava visível na tela. A correção
 * (V15) é descobrir NUMDUPL via SELECT antes de DELETAR:
 * <pre>
 *   SELECT NUMDUPL FROM TGFFIN WHERE NUMNOTA=? AND DESDOBDUPL='ZZ'
 * </pre>
 *
 * <h2>Fluxo do cancelamento (V16 - 2026-05-18)</h2>
 *
 * <ol>
 *   <li><b>Descobre NUMDUPL e qtdNossoNum</b> a partir do NUMNOTA recebido.</li>
 *   <li><b>PASSO 0:</b> DELETE TGFRAF TIPO='E' STATUS='A' (cancela pedido de
 *       registro pendente do boleto Itau - cobre race condition com
 *       BoletoAutoService que pode estar esperando resposta da API).</li>
 *   <li><b>BIFURCAÇÃO baseada em qtdNossoNum:</b>
 *     <ul>
 *       <li><b>CASO A (sem boleto, qtdNossoNum=0):</b> DELETE FÍSICO usando
 *           NUMDUPL - igual ao botão "Desfazer" da tela. Adiantamento SUMIRÁ.</li>
 *       <li><b>CASO B (com boleto, qtdNossoNum>0):</b>
 *         <ol type="a">
 *           <li>B0: UPDATE TGFFIN MONIOCOREM='N' (libera trigger)</li>
 *           <li>B1: INSERT TGFRAF TIPO='B' STATUS='A' OCOR='02' (enfileira baixa Itau)</li>
 *           <li>B2: UPDATE TGFFIN PROVISAO='S' AD_NUNOTAADIANT=NULL (soft cancel)</li>
 *           <li>B3: POLLING SINCRONO ate 30s aguardando TGFRAF STATUS='E' (worker
 *               async Sankhya confirmou cancelamento na API Itau)</li>
 *           <li>B4: Se confirmado -> DELETE FISICO completo (TGFRAF + TGFHBA +
 *               TGFFRE + TFPIRB + TGFFIN). Adiantamento SUMIRA igual Caso A.</li>
 *           <li>B5: Se TIMEOUT 30s -> mantem SOFT CANCEL. ReconciliacaoScheduler
 *               completa o DELETE em ate 5min via {@link #completarDeletePosBaixaItau}.</li>
 *         </ol>
 *       </li>
 *     </ul>
 *   </li>
 *   <li><b>Verificação final</b> via {@link #aindaTemAdiantamentoAtivo} - se
 *       ainda tem TGFFIN com PROVISAO='N', lanca exception.</li>
 * </ol>
 *
 * <h2>Triggers SQL Server importantes</h2>
 * <ul>
 *   <li>{@code TRG_INC_UPD_TGFFIN_MONIOCOREM}: bloqueia UPDATE PROVISAO='S'
 *       quando TGFRAF tem TIPO='E' NUREMESSA NOT NULL e MONIOCOREM='S'.
 *       Mensagem: "O título X esta em cobranca registrada".
 *       <b>Não dispara em DELETE</b>, apenas em UPDATE.</li>
 *   <li>{@code TRG_DLT_TGFFIN}: bloqueia DELETE quando PROVISAO='N' AND
 *       DHBAIXA NOT NULL ("nota ja foi baixada"). Por isso o DELETE inclui
 *       filtro {@code DHBAIXA IS NULL}.</li>
 *   <li>{@code TRG_UPT_TGFITE}: propaga TGFITE.PENDENTE='N' para
 *       TGFCAB.PENDENTE='N'. UPDATE via SP/trigger NÃO dispara JAPE event,
 *       por isso o botão precisa ser AcaoRotinaJava (não "Rotina BD").</li>
 * </ul>
 *
 * <h2>Worker async do Sankhya</h2>
 * <p>Para cancelamento de boleto Itau via API, o worker usa a query
 * {@code SaldoBancarioHelpper_queAPIBoletosCancelaMS.sql} (procura
 * TGFHBA STATUS='A' TIPOENVIO IN('X','B')) e
 * {@code ApiBancosHelpper_queAPIBoletosCancelBaixar.sql} (procura
 * TGFRAF STATUS='A' TIPO='B' WHERE FIN.NOSSONUM IS NOT NULL).
 *
 * <p>Configuração Itau (TGFCRAF onde CODCTABCOINT=141, validado em prod):
 * OCORRENCIA_ENTRADA='01' (registro), OCORRENCIA_BAIXA='02' (cancelamento),
 * IDAPIBANCO=2242.
 */
public class CancelamentoHelper {

    private static final Logger LOGGER = Logger.getLogger(CancelamentoHelper.class.getName());

    private CancelamentoHelper() {}

    public static boolean notaFoiCancelada(String statusAnterior, String statusAtual) {
        return !"C".equals(statusAnterior) && "C".equals(statusAtual);
    }

    /**
     * Cancela todos adiantamentos vinculados a uma NUNOTA.
     *
     * @return true se TODOS foram cancelados (ou nao havia nenhum).
     *         false se pelo menos um falhou - o caller deve LOGAR e nao mentir
     *         sobre o resultado.
     */
    public static boolean cancelarAdiantamentosPorNota(BigDecimal nunota) throws Exception {
        if (nunota == null) {
            LOGGER.warning("[CancelamentoHelper] NUNOTA nulo - cancelamento ignorado");
            return true;
        }

        List<BigDecimal> adiantamentos = buscarAdiantamentosRelacionados(nunota);
        if (adiantamentos.isEmpty()) {
            LOGGER.info("[CancelamentoHelper] Nenhum adiantamento para cancelar - NUNOTA=" + nunota);
            return true;
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

        boolean tudoOk = (cancelados == adiantamentos.size());
        if (tudoOk) {
            LOGGER.info("[CancelamentoHelper] Cancelamento OK - " + cancelados + "/" + adiantamentos.size()
                    + " adiantamentos cancelados para NUNOTA=" + nunota);
        } else {
            LOGGER.warning("[CancelamentoHelper] Cancelamento PARCIAL/FALHA - " + cancelados + "/"
                    + adiantamentos.size() + " adiantamentos cancelados para NUNOTA=" + nunota
                    + " - VERIFIQUE ERROS ACIMA");
        }
        return tudoOk;
    }

    public static boolean possuiAdiantamentosRelacionados(BigDecimal nunota) {
        if (nunota == null) return false;
        return !buscarAdiantamentosRelacionados(nunota).isEmpty();
    }

    /**
     * Verifica se existem adiantamentos vinculados a esta NUNOTA ou a qualquer
     * NUNOTA relacionada via TGFVAR (ex: nota original de faturamento parcial).
     * CORRECAO: Resolve o bug onde PENDENTE=N nao cancelava adiantamentos porque
     * o AD_NUNOTAADIANT aponta para a nota ORIGINAL, nao para a nota faturada.
     */
    public static boolean possuiAdiantamentosRelacionadosComVAR(BigDecimal nunota) {
        if (nunota == null) return false;

        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            // Busca adiantamentos vinculados a esta NUNOTA OU a NUNOTAs relacionadas via TGFVAR
            stmt = jdbc.getPreparedStatement(
                    "SELECT TOP 1 1 FROM TGFFIN WITH (NOLOCK) "
                  + "WHERE AD_NUNOTAADIANT IN ("
                  + "  SELECT ? "
                  + "  UNION "
                  + "  SELECT NUNOTA FROM TGFVAR WITH (NOLOCK) WHERE NUNOTAORIG = ? "
                  + "  UNION "
                  + "  SELECT NUNOTAORIG FROM TGFVAR WITH (NOLOCK) WHERE NUNOTA = ?"
                  + ") AND PROVISAO = 'N'");
            stmt.setBigDecimal(1, nunota);
            stmt.setBigDecimal(2, nunota);
            stmt.setBigDecimal(3, nunota);
            rs = stmt.executeQuery();
            boolean existe = rs.next();
            LOGGER.fine("[CancelamentoHelper] possuiAdiantamentosRelacionadosComVAR NUNOTA=" + nunota + " resultado=" + existe);
            return existe;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao verificar adiantamentos com VAR para NUNOTA=" + nunota, e);
            return false;
        } finally {
            fechar(rs, stmt, jdbc);
        }
    }

    public static boolean existeLigacaoVarPorNunotaOrig(BigDecimal nunota) {
        if (nunota == null) return false;

        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement("SELECT TOP 1 1 FROM TGFVAR WITH (NOLOCK) WHERE NUNOTAORIG = ?");
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

    /**
     * Busca adiantamentos relacionados considerando TGFVAR.
     * CORRECAO: Antes buscava apenas AD_NUNOTAADIANT = nunota_exata.
     * Agora busca tambem via notas relacionadas por TGFVAR (faturamento parcial).
     */
    private static List<BigDecimal> buscarAdiantamentosRelacionados(BigDecimal nunota) {
        List<BigDecimal> adiantamentos = new ArrayList<>();
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(
                    "SELECT DISTINCT F.NUMNOTA FROM TGFFIN F WITH (NOLOCK) "
                  + "WHERE F.AD_NUNOTAADIANT IN ("
                  + "  SELECT ? "
                  + "  UNION "
                  + "  SELECT NUNOTA FROM TGFVAR WITH (NOLOCK) WHERE NUNOTAORIG = ? "
                  + "  UNION "
                  + "  SELECT NUNOTAORIG FROM TGFVAR WITH (NOLOCK) WHERE NUNOTA = ?"
                  + ") AND F.PROVISAO = 'N' AND F.DHBAIXA IS NULL");
            stmt.setBigDecimal(1, nunota);
            stmt.setBigDecimal(2, nunota);
            stmt.setBigDecimal(3, nunota);
            rs = stmt.executeQuery();

            while (rs.next()) {
                adiantamentos.add(rs.getBigDecimal("NUMNOTA"));
            }
            LOGGER.info("[CancelamentoHelper] Adiantamentos encontrados para NUNOTA=" + nunota + ": " + adiantamentos.size());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao buscar adiantamentos para NUNOTA=" + nunota, e);
        } finally {
            fechar(rs, stmt, jdbc);
        }
        return adiantamentos;
    }

    /**
     * Cancela o adiantamento simulando o botao "Desfazer" da tela nativa
     * (Adiantamento/Emprestimo).
     *
     * <p>Estrategia em 2 niveis:
     * <ol>
     *   <li><b>Desfazer nativo</b> - delega para
     *       {@code br.com.sankhya.modelcore.financeiro.util.AdiantamentoEmprestimoHelper#desfazerAdiantamento(BigDecimal)}.
     *       Esse metodo eh exatamente o que o botao "Desfazer" da interface chama
     *       (servico {@code AdiantamentoEmprestimoSP.desfazerAdiantamento}). Ele
     *       executa em sequencia:
     *       <ul>
     *         <li>Valida que nao ha titulo renegociado/baixado
     *             (DESDOBDUPL='ZZ' AND (RECDESP=0 OR DHBAIXA IS NOT NULL))</li>
     *         <li>{@code DELETE FROM TGFFRE WHERE NUACERTO=? AND TIPACERTO='A'}</li>
     *         <li>{@code DELETE FROM TGFFIN WHERE DESDOBDUPL='ZZ' AND RECDESP&lt;&gt;0
     *             AND DHBAIXA IS NULL AND NUMDUPL=?} (cascata via triggers limpa
     *             TFPIRB/TGFRAF_EXC e libera o pedido)</li>
     *       </ul>
     *       <i>Mapeado a partir do log Monitor_Processos.log capturado em
     *       04/05/2026 (servico AdiantamentoEmprestimoSP.desfazerAdiantamento).</i>
     *   </li>
     *   <li><b>Fallback legado</b> (UPDATE PROVISAO='S') somente quando o
     *       desfazer nativo recusa a operacao por existirem parcelas baixadas
     *       ou renegociadas (codigo CORE_E00852). Nesses casos a baixa marca
     *       sinaliza ao operador que houve um cancelamento "soft" controlado.
     *   </li>
     * </ol>
     *
     * <p>Vantagens de delegar ao nativo:
     * <ul>
     *   <li>Comportamento identico ao botao "Desfazer" da tela (delete fisico
     *       limpa TGFFIN/TGFFRE/TFPIRB e libera triggers do pedido)</li>
     *   <li>Aproveita validacoes nativas (CORE_E00852)</li>
     *   <li>Acompanha automaticamente correcoes/melhorias do ERP em updates</li>
     * </ul>
     */
    /** Flag obrigatoria do contexto JAPE - sem ela as triggers/regras do
     * Sankhya rejeitam o DELETE de TGFFIN silenciosamente. Identica a flag
     * usada por AdiantamentoEmprestimoSPBean.desfazerAdiantamento (decompilado). */
    private static final String CONTEXT_FLAG_ADIANTAMENTO = "br.com.sankhya.com.AdiantamentoEmprestimo";

    /**
     * Cancela o adiantamento PIX seguindo a sequencia VALIDADA EM TRANSACAO
     * BEGIN TRAN/ROLLBACK no SANKHYA_TESTE em 14/05/2026 (NUMNOTA=3675 e 3676):
     *
     * <pre>
     *   PASSO 1: UPDATE TGFFIN SET MONIOCOREM='N'
     *            (desativa monitoramento -> trigger TRG_INC_UPD_TGFFIN_MONIOCOREM
     *             nao bloqueia o UPDATE PROVISAO subsequente, pois ela so dispara
     *             quando @TITULO_MONITORADO='S')
     *
     *   PASSO 2: INSERT INTO TGFRAF (NUFIN, SEQUENCIA=MAX+1, STATUS='A',
     *                                OCORRENCIA=TGFCRAF.OCORRENCIA_BAIXA,
     *                                TIPO='B', TIPOENVIO='B', CAMPO=' ')
     *            FILTRO: somente onde NOSSONUM IS NOT NULL (so se boleto registrado)
     *            EFEITO: o worker async do Sankhya (queAPIBoletosEnviaMS.sql)
     *                    pega RAF.STATUS='A' AND TIPOENVIO='B' e envia pra API
     *                    do banco (Itau, no caso CODCTABCOINT=141, IDAPIBANCO=2242).
     *                    O boleto eh BAIXADO/CANCELADO no banco efetivamente.
     *
     *   PASSO 3: UPDATE TGFFIN SET PROVISAO='S', NOSSONUM=NULL, AD_NUNOTAADIANT=NULL
     *            (cancela o adiantamento como provisao - mantem o registro
     *             para rastreabilidade contabil)
     * </pre>
     *
     * <p><b>Por que funciona (validado no BD):</b>
     * <ul>
     *   <li>PASSO 1 muda MONIOCOREM='S' -> 'N'. A trigger MONIOCOREM verifica
     *       no novo estado (INSERTED) se TITULO_MONITORADO='S'. Como ja eh 'N',
     *       a trigger nao entra no IF que bloqueia.</li>
     *   <li>PASSO 2 nao dispara nenhuma trigger (TGFRAF nao tem triggers).
     *       O CAMPO=' ' satisfaz a constraint NOT NULL.</li>
     *   <li>PASSO 3 funciona porque MONIOCOREM='N' impede o bloqueio "cobranca
     *       registrada". O ELSE da trigger faz DELETE TGFRAF preservando o que
     *       inserimos (a logica do ELSE so deleta se TITULO_MONITORADO='S').</li>
     * </ul>
     *
     * <p><b>Cobertura completa:</b>
     * <ul>
     *   <li>Boleto Itau registrado: PASSO 2 enfileira baixa para worker -> banco</li>
     *   <li>Sem boleto (NOSSONUM IS NULL): PASSO 2 nao insere nada (filtro), so
     *       cancela o adiantamento no Sankhya</li>
     *   <li>Adiantamento ja pago (DHBAIXA NOT NULL): trigger TRG_DLT_TGFFIN
     *       proibiria DELETE, mas como aqui usamos UPDATE PROVISAO='S' (nao
     *       DELETE), passa OK</li>
     * </ul>
     */
    private static void cancelarAdiantamento(BigDecimal numnota) throws Exception {
        Object flagAnterior = null;
        boolean flagAplicada = false;
        try {
            try {
                flagAnterior = br.com.sankhya.jape.core.JapeSession.getProperty(CONTEXT_FLAG_ADIANTAMENTO);
            } catch (Exception ignore) {}
            br.com.sankhya.jape.core.JapeSession.putProperty(CONTEXT_FLAG_ADIANTAMENTO, Boolean.TRUE);
            flagAplicada = true;

            logarEstadoAdiantamento(numnota, "ANTES");

            br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
            try {
                jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
                jdbc.openSession();

                // ============================================================
                // V15: descobrir o NUMDUPL (NUACERTO) e se ha NOSSONUM (boleto registrado)
                // CHAVE da V15: NUMDUPL eh o numero do ACERTO de adiantamento na TGFFIN
                // (ex: "Adiant./Emp.: 13947" da tela = NUMDUPL = NUACERTO em TGFFRE).
                // O metodo nativo desfazerAdiantamento() do Sankhya espera NUMDUPL, NAO
                // NUMNOTA. Versoes anteriores passavam NUMNOTA e por isso o nativo
                // nunca achava nada e caiamos no fallback (UPDATE PROVISAO=S),
                // que NAO removia o registro - adiantamento ficava visivel na tela.
                // ============================================================
                BigDecimal numdupl = null;
                int qtdNossoNum = 0;
                PreparedStatement sQ = null;
                ResultSet rsQ = null;
                try {
                    sQ = jdbc.getPreparedStatement(
                            "SELECT TOP 1 NUMDUPL, "
                          + "  (SELECT COUNT(*) FROM TGFFIN F2 WHERE F2.NUMNOTA = ? AND F2.DESDOBDUPL='ZZ' AND F2.NOSSONUM IS NOT NULL) AS QT_NOSSO "
                          + "FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
                    sQ.setBigDecimal(1, numnota);
                    sQ.setBigDecimal(2, numnota);
                    rsQ = sQ.executeQuery();
                    if (rsQ.next()) {
                        numdupl = rsQ.getBigDecimal("NUMDUPL");
                        qtdNossoNum = rsQ.getInt("QT_NOSSO");
                    }
                } finally {
                    if (rsQ != null) try { rsQ.close(); } catch (Exception ig) {}
                    if (sQ != null) try { sQ.close(); } catch (Exception ig) {}
                }
                if (numdupl == null) {
                    LOGGER.warning("[CancelamentoHelper] NUMNOTA=" + numnota
                            + " - NUMDUPL nao encontrado, abortando cancelamento");
                    return;
                }
                LOGGER.info("[CancelamentoHelper] V15: NUMNOTA=" + numnota + " -> NUMDUPL=" + numdupl
                        + " | titulos com boleto (NOSSONUM): " + qtdNossoNum);

                // ============================================================
                // PASSO 0: CANCELAR pedido de REGISTRO pendente em TGFRAF.
                // Cobre race condition: se BoletoAutoService ainda esta
                // esperando resposta da API Itau (NOSSONUM ainda nao chegou),
                // tem TGFRAF TIPO='E' STATUS='A' pendente. DELETE impede o
                // worker de registrar o boleto apos o cancelamento.
                // ============================================================
                PreparedStatement s0 = jdbc.getPreparedStatement(
                        "DELETE FROM TGFRAF "
                      + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ') "
                      + "  AND TIPO = 'E' AND STATUS = 'A'");
                try {
                    s0.setBigDecimal(1, numnota);
                    int n0 = s0.executeUpdate();
                    if (n0 > 0) {
                        LOGGER.info("[CancelamentoHelper] V15 PASSO 0: " + n0
                                + " pedido(s) de REGISTRO pendente removidos de TGFRAF - NUMNOTA=" + numnota);
                    }
                } finally { try { s0.close(); } catch (Exception ig) {} }

                // ============================================================
                // PASSO 1 - DECISAO BIFURCADA pelo estado do boleto Itau:
                //
                // - BOLETO NAO REGISTRADO (qtdNossoNum == 0):
                //     -> Cenario do chefe (test 4861874): boleto nunca chegou ao
                //        Itau. Nada a cancelar no banco. Faz DELETE FISICO completo
                //        (igual botao "Desfazer" da tela). Adiantamento SUMIRA
                //        da tela "Adiantamento/Empresa".
                //
                // - BOLETO REGISTRADO (qtdNossoNum > 0):
                //     -> Boleto JA existe no Itau. Precisa cancelar via API banco
                //        (worker assincrono). Cancelamento eh SOFT (PROVISAO='S')
                //        para manter TGFFIN ativo enquanto worker processa baixa.
                //        Se DELETARMOS TGFFIN aqui, a TGFRAF TIPO='B' fica orfa
                //        (worker nao pega - filtro JOIN com TGFFIN nao retorna),
                //        boleto fica REGISTRADO no Itau e cliente pode pagar.
                //        Validado em BD em 15/05/2026 16:55: TGFRAF orfa (1)
                //        + TGFFIN deletado (0) -> worker NAO pega (0).
                // ============================================================
                if (qtdNossoNum == 0) {
                    // CASO A: SEM BOLETO -> DELETE FISICO (some da tela)
                    int linhasFre = 0, linhasIrb = 0, linhasFin = 0;

                    PreparedStatement s2a = jdbc.getPreparedStatement(
                            "DELETE FROM TGFFRE WHERE NUACERTO = ? AND TIPACERTO = 'A'");
                    try {
                        s2a.setBigDecimal(1, numdupl);
                        linhasFre = s2a.executeUpdate();
                    } finally { try { s2a.close(); } catch (Exception ig) {} }

                    PreparedStatement s2b = jdbc.getPreparedStatement(
                            "DELETE FROM TFPIRB "
                          + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WHERE NUMDUPL = ? AND DESDOBDUPL = 'ZZ')");
                    try {
                        s2b.setBigDecimal(1, numdupl);
                        linhasIrb = s2b.executeUpdate();
                    } catch (Exception ig) {
                        LOGGER.fine("[CancelamentoHelper] V15 TFPIRB ignorado: " + ig.getMessage());
                    } finally { try { s2b.close(); } catch (Exception ig) {} }

                    PreparedStatement s2c = jdbc.getPreparedStatement(
                            "DELETE FROM TGFFIN "
                          + "WHERE DESDOBDUPL = 'ZZ' AND RECDESP <> 0 AND DHBAIXA IS NULL AND NUMDUPL = ?");
                    try {
                        s2c.setBigDecimal(1, numdupl);
                        linhasFin = s2c.executeUpdate();
                    } finally { try { s2c.close(); } catch (Exception ig) {} }

                    LOGGER.info("[CancelamentoHelper] V15 CASO A (sem boleto) - DELETE FISICO: TGFFRE="
                            + linhasFre + ", TFPIRB=" + linhasIrb + ", TGFFIN=" + linhasFin
                            + " (NUMDUPL=" + numdupl + " NUMNOTA=" + numnota + ")"
                            + " - adiantamento SUMIRA da tela");

                    // Fallback se DELETE foi bloqueado por trigger (ex: DHBAIXA preenchido)
                    if (linhasFin == 0) {
                        LOGGER.warning("[CancelamentoHelper] V15 CASO A: DELETE TGFFIN nao removeu "
                                + "(DHBAIXA preenchido ou outra constraint). Aplicando UPDATE PROVISAO='S' "
                                + "- NUMDUPL=" + numdupl);
                        PreparedStatement s3 = jdbc.getPreparedStatement(
                                "UPDATE TGFFIN SET PROVISAO = 'S', AD_NUNOTAADIANT = NULL "
                              + "WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
                        try {
                            s3.setBigDecimal(1, numnota);
                            int n3 = s3.executeUpdate();
                            LOGGER.info("[CancelamentoHelper] V15 CASO A fallback: PROVISAO='S' em " + n3
                                    + " linha(s) - NUMNOTA=" + numnota);
                        } finally { try { s3.close(); } catch (Exception ig) {} }
                    }
                } else {
                    // ============================================================
                    // CASO B (V16 - POLLING SINCRONO + DELETE POS-BAIXA):
                    // COM BOLETO REGISTRADO -> SOFT CANCEL + AGUARDA WORKER + DELETE FISICO
                    //
                    // Pre-requisitos (do log 4862122, 17:22:14-17:22:28):
                    //   - V15.1 enfileirou TGFRAF TIPO='B' STATUS='A'
                    //   - Worker async Sankhya pegou em ~14s e mandou pra Itau
                    //   - Itau respondeu "Boleto Processado com sucesso!" status=-1
                    //   - TGFRAF mudou STATUS de 'A' para 'E'
                    //
                    // V16 (aprovado pelo chefe 18/05/2026):
                    //   B0..B2: mesmo SOFT CANCEL da V15.1
                    //   B3: POLLING SINCRONO de ate 30s aguardando worker async
                    //       confirmar baixa (TGFRAF SEQ_BAIXA.STATUS='E')
                    //   B4: Se confirmado -> DELETE FISICO completo (filhos + TGFFIN)
                    //   B5: Se TIMEOUT 30s -> mantem SOFT CANCEL; safety net no
                    //       ReconciliacaoScheduler completa o DELETE em ate 5min
                    //
                    // POR QUE 30s: log mostrou worker processando em 14s. 30s eh
                    //   margem 2x. Se demorar mais, eh provavel problema de rede
                    //   com Itau e nao adianta esperar mais sincrono.
                    // ============================================================

                    // B0: limpar MONIOCOREM (desativa trigger MONIOCOREM)
                    PreparedStatement sB0 = jdbc.getPreparedStatement(
                            "UPDATE TGFFIN SET MONIOCOREM = 'N' "
                          + "WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
                    int nB0 = 0;
                    try {
                        sB0.setBigDecimal(1, numnota);
                        nB0 = sB0.executeUpdate();
                    } finally { try { sB0.close(); } catch (Exception ig) {} }

                    // B1: enfileirar baixa do boleto via TGFRAF (TIPO='B' STATUS='A')
                    PreparedStatement sB1 = jdbc.getPreparedStatement(
                            "INSERT INTO TGFRAF (NUFIN, SEQUENCIA, CODUSU, DTALTER, STATUS, OCORRENCIA, TIPO, TIPOENVIO, CAMPO) "
                          + "SELECT F.NUFIN, "
                          + "       ISNULL((SELECT MAX(SEQUENCIA) FROM TGFRAF WHERE NUFIN = F.NUFIN), 0) + 1, "
                          + "       0, GETDATE(), 'A', "
                          + "       (SELECT OCORRENCIA_BAIXA FROM TGFCRAF WHERE CODCTABCOINT = F.CODCTABCOINT), "
                          + "       'B', 'B', ' ' "
                          + "FROM TGFFIN F "
                          + "WHERE F.NUMNOTA = ? AND F.DESDOBDUPL = 'ZZ' AND F.RECDESP = 1 "
                          + "  AND F.NOSSONUM IS NOT NULL");
                    int nB1 = 0;
                    try {
                        sB1.setBigDecimal(1, numnota);
                        nB1 = sB1.executeUpdate();
                    } finally { try { sB1.close(); } catch (Exception ig) {} }

                    // B2: SOFT CANCEL no Sankhya (PROVISAO='S', AD_NUNOTAADIANT=NULL)
                    PreparedStatement sB2 = jdbc.getPreparedStatement(
                            "UPDATE TGFFIN SET PROVISAO = 'S', AD_NUNOTAADIANT = NULL "
                          + "WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
                    int nB2 = 0;
                    try {
                        sB2.setBigDecimal(1, numnota);
                        nB2 = sB2.executeUpdate();
                    } finally { try { sB2.close(); } catch (Exception ig) {} }

                    LOGGER.info("[CancelamentoHelper] V16 CASO B passo 1/2 - SOFT CANCEL aplicado: "
                            + "B0 MONIOCOREM='N' em " + nB0 + " linha(s), "
                            + "B1 " + nB1 + " pedido(s) BAIXA Itau enfileirado(s), "
                            + "B2 PROVISAO='S' em " + nB2 + " linha(s) "
                            + "(NUMDUPL=" + numdupl + " NUMNOTA=" + numnota + "). "
                            + "Aguardando worker async cancelar boleto Itau (polling 30s)...");

                    // ============================================================
                    // B3: COMMIT IMPLICITO (libera linhas para o worker async)
                    //     + POLLING SINCRONO ate 30s aguardando STATUS='E'
                    //
                    // CRITICO: closeSession() + delay antes de polling para garantir
                    //   que o worker async (que roda em outra sessao JDBC) consiga
                    //   ler o TGFRAF TIPO='B' STATUS='A' que acabamos de inserir.
                    //   O worker tem ciclo de ~1 minuto, o primeiro poll comeca em 5s
                    //   para dar tempo dele ler o INSERT.
                    // ============================================================
                    // Fecha esta sessao para forcar commit visivel ao worker async
                    try { jdbc.closeSession(); } catch (Exception ig) {}
                    jdbc = null;

                    boolean baixaConfirmada = aguardarBaixaConfirmadaItau(numnota, 30);

                    if (baixaConfirmada) {
                        // ====================================================
                        // B4: BAIXA CONFIRMADA NA ITAU -> DELETE FISICO COMPLETO
                        //
                        // Agora podemos remover o TGFFIN sem deixar boleto orfao,
                        // porque o worker JA processou o pedido de baixa pra Itau.
                        // Trigger TRG_DLT_TGFFIN permite DELETE pois:
                        //   - PROVISAO='S' (B2 ja aplicou)
                        //   - DHBAIXA IS NULL
                        //   - Sem TCBINT, TGFLIV, TGFCOM, TGMTRA, TGFMCX
                        //
                        // Ordem dos DELETEs (filhos primeiro):
                        //   1. TGFRAF (registro autorizacao financeiro)
                        //   2. TGFHBA (historico boletos API)
                        //   3. TGFFRE (vinculos acerto)
                        //   4. TFPIRB (IRRF, se houver)
                        //   5. TGFFIN (financeiro principal)
                        // ====================================================
                        int linhasFin = deletarAdiantamentoCompleto(numnota, numdupl);
                        if (linhasFin > 0) {
                            LOGGER.warning("[CancelamentoHelper] V16 CASO B - SUCESSO TOTAL: "
                                    + "boleto cancelado na Itau + DELETE FISICO (" + linhasFin
                                    + " linha TGFFIN removida) "
                                    + "(NUMDUPL=" + numdupl + " NUMNOTA=" + numnota + "). "
                                    + "Adiantamento SUMIU da tela.");
                        } else {
                            LOGGER.warning("[CancelamentoHelper] V16 CASO B - DELETE FISICO NAO REMOVEU TGFFIN "
                                    + "apos baixa confirmada. Pode ser concorrencia ou trigger TRG_DLT_TGFFIN "
                                    + "bloqueando. SOFT CANCEL ja foi aplicado (PROVISAO='S'). "
                                    + "ReconciliacaoScheduler tentara de novo. "
                                    + "(NUMDUPL=" + numdupl + " NUMNOTA=" + numnota + ")");
                        }
                    } else {
                        // ====================================================
                        // B5: TIMEOUT 30s sem confirmacao -> mantem SOFT CANCEL
                        //     SAFETY NET: ReconciliacaoScheduler completa em <=5min
                        // ====================================================
                        LOGGER.warning("[CancelamentoHelper] V16 CASO B - TIMEOUT 30s aguardando baixa Itau "
                                + "para NUMNOTA=" + numnota + " NUMDUPL=" + numdupl + ". "
                                + "SOFT CANCEL aplicado (PROVISAO='S', baixa enfileirada). "
                                + "Adiantamento ainda visivel na tela; ReconciliacaoScheduler completara "
                                + "o DELETE FISICO em ate 5 minutos quando worker async confirmar baixa.");
                    }
                }
            } finally {
                if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ig) {}
            }

            logarEstadoAdiantamento(numnota, "DEPOIS");

            // VERIFICACAO FINAL: o adiantamento foi REALMENTE cancelado?
            // Aceita tanto DELETE (linhas sumiram) quanto PROVISAO='S'.
            if (aindaTemAdiantamentoAtivo(numnota)) {
                throw new Exception("[CancelamentoHelper] FALHA: NUMNOTA=" + numnota
                        + " ainda tem TGFFIN com PROVISAO='N' apos V15 - veja DIAG-* acima");
            }
        } finally {
            if (flagAplicada) {
                try {
                    if (flagAnterior != null) {
                        br.com.sankhya.jape.core.JapeSession.putProperty(CONTEXT_FLAG_ADIANTAMENTO, flagAnterior);
                    } else {
                        br.com.sankhya.jape.core.JapeSession.removeProperty(CONTEXT_FLAG_ADIANTAMENTO);
                    }
                } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Verifica se ainda existe alguma linha TGFFIN com PROVISAO='N' para o
     * adiantamento - isso significa que o cancelamento NAO foi efetivo.
     *
     * @return true se ainda tem adiantamento ativo (cancelamento falhou)
     */
    private static boolean aindaTemAdiantamentoAtivo(BigDecimal numnota) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(
                    "SELECT TOP 1 1 FROM TGFFIN WITH (NOLOCK) "
                  + "WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ' AND PROVISAO = 'N'");
            stmt.setBigDecimal(1, numnota);
            rs = stmt.executeQuery();
            return rs.next();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] aindaTemAdiantamentoAtivo erro NUMNOTA=" + numnota, e);
            // Em caso de erro de consulta, assume conservadoramente que ainda tem
            return true;
        } finally {
            fechar(rs, stmt, jdbc);
        }
    }

    /**
     * Loga o estado atual de TGFFIN e TFPHBA para um adiantamento. Essencial
     * para diagnosticar problemas de cancelamento - mostra exatamente quais
     * linhas existem, com que valores em RECDESP, DHBAIXA, NOSSONUM,
     * AD_NUNOTAADIANT, DESDOBDUPL, DESDOBRAMENTO, PROVISAO, NUFIN.
     *
     * @param numnota  o NUMNOTA do adiantamento
     * @param momento  rotulo para identificar quando foi logado (ANTES/DEPOIS)
     */
    private static void logarEstadoAdiantamento(BigDecimal numnota, String momento) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // 1) Estado das linhas em TGFFIN - SELECT minimalista (so colunas garantidas)
            // Evita erro 'Nome de coluna NUMTIT invalido' que matava o diagnostico inteiro.
            stmt = jdbc.getPreparedStatement(
                    "SELECT NUFIN, RECDESP, PROVISAO, DHBAIXA, NOSSONUM, "
                  + "AD_NUNOTAADIANT, DESDOBDUPL, DESDOBRAMENTO "
                  + "FROM TGFFIN WITH (NOLOCK) WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
            stmt.setBigDecimal(1, numnota);
            rs = stmt.executeQuery();
            int count = 0;
            while (rs.next()) {
                count++;
                LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                        + " linha#" + count
                        + " NUFIN=" + rs.getBigDecimal("NUFIN")
                        + " RECDESP=" + rs.getBigDecimal("RECDESP")
                        + " PROVISAO=" + rs.getString("PROVISAO")
                        + " DHBAIXA=" + rs.getTimestamp("DHBAIXA")
                        + " NOSSONUM=" + rs.getString("NOSSONUM")
                        + " AD_NUNOTAADIANT=" + rs.getBigDecimal("AD_NUNOTAADIANT")
                        + " DESDOBRAMENTO=" + rs.getString("DESDOBRAMENTO"));
            }
            if (count == 0) {
                LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                        + " - NENHUMA linha TGFFIN encontrada (adiantamento ja foi deletado ou nunca existiu)");
            }
            rs.close();
            stmt.close();

            // 2) Estado em TGFRAF (registro autorizacao financeiro - CHAVE para a trigger
            //    TRG_INC_UPD_TGFFIN_MONIOCOREM bloquear ou nao).
            //    Se houver TIPO='E' com NUREMESSA NOT NULL, a trigger BLOQUEIA UPDATE/DELETE.
            try {
                stmt = jdbc.getPreparedStatement(
                        "SELECT NUFIN, SEQUENCIA, NUREMESSA, TIPO, STATUS, OCORRENCIA "
                      + "FROM TGFRAF WITH (NOLOCK) "
                      + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ') "
                      + "ORDER BY NUFIN, SEQUENCIA");
                stmt.setBigDecimal(1, numnota);
                rs = stmt.executeQuery();
                int countRaf = 0;
                while (rs.next()) {
                    countRaf++;
                    LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                            + " TGFRAF#" + countRaf
                            + " NUFIN=" + rs.getBigDecimal("NUFIN")
                            + " SEQ=" + rs.getBigDecimal("SEQUENCIA")
                            + " NUREMESSA=" + rs.getBigDecimal("NUREMESSA")
                            + " TIPO=" + rs.getString("TIPO")
                            + " STATUS=" + rs.getString("STATUS")
                            + " OCORRENCIA=" + rs.getString("OCORRENCIA"));
                }
                if (countRaf == 0) {
                    LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                            + " - TGFRAF limpo (trigger nao vai bloquear)");
                }
            } catch (Exception ig) {
                LOGGER.fine("[CancelamentoHelper] DIAG-" + momento + " TGFRAF erro: " + ig.getMessage());
            }
            if (rs != null) { try { rs.close(); } catch (Exception ig) {} rs = null; }
            if (stmt != null) { try { stmt.close(); } catch (Exception ig) {} stmt = null; }

            // 3) Estado em TGFHBA (historico boletos API banco)
            try {
                stmt = jdbc.getPreparedStatement(
                        "SELECT ID, NUFIN, NOSSONUM, STATUS, STATUSENVIO, TIPOENVIO, STATUSBANCO, IDAPIBANCO "
                      + "FROM TGFHBA WITH (NOLOCK) "
                      + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ') "
                      + "ORDER BY ID");
                stmt.setBigDecimal(1, numnota);
                rs = stmt.executeQuery();
                int countHba = 0;
                while (rs.next()) {
                    countHba++;
                    LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                            + " TGFHBA#" + countHba
                            + " ID=" + rs.getBigDecimal("ID")
                            + " NUFIN=" + rs.getBigDecimal("NUFIN")
                            + " NOSSONUM=" + rs.getString("NOSSONUM")
                            + " STATUS=" + rs.getString("STATUS")
                            + " STATUSENVIO=" + rs.getString("STATUSENVIO")
                            + " TIPOENVIO=" + rs.getString("TIPOENVIO")
                            + " STATUSBANCO=" + rs.getString("STATUSBANCO")
                            + " IDAPIBANCO=" + rs.getBigDecimal("IDAPIBANCO"));
                }
                if (countHba == 0) {
                    LOGGER.info("[CancelamentoHelper] DIAG-" + momento + " NUMNOTA=" + numnota
                            + " - TGFHBA limpo (sem boletos API)");
                }
            } catch (Exception ig) {
                LOGGER.fine("[CancelamentoHelper] DIAG-" + momento + " TGFHBA erro: " + ig.getMessage());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] DIAG-" + momento
                    + " erro ao consultar estado para NUMNOTA=" + numnota, e);
        } finally {
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (stmt != null) try { stmt.close(); } catch (Exception ignore) {}
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    /**
     * Limpa registros que fazem a trigger {@code TRG_INC_UPD_TGFFIN_MONIOCOREM}
     * bloquear UPDATE/DELETE em TGFFIN com a mensagem "O titulo X esta em
     * cobranca registrada, portanto nao pode ser transformado em provisao".
     *
     * <p><b>Sequencia VALIDADA no ambiente SANKHYA_TESTE (sandbox)
     * em 14/05/2026 com BEGIN TRAN/ROLLBACK sobre NUMNOTA=3675 (adiantamento
     * com boleto Itau registrado, MONIOCOREM='S', TGFRAF TIPO='E'):</b>
     *
     * <ol>
     *   <li>{@code DELETE FROM TGFRAF WHERE NUFIN IN (...) AND TIPO='E'} -
     *       remove o registro "Entrada na cobranca registrada" que a trigger
     *       inspeciona. <i>Validado: sem isso, UPDATE PROVISAO='S' lanca
     *       erro "cobranca registrada". Com isso, passa.</i></li>
     *   <li>{@code UPDATE TGFFIN SET NOSSONUM=NULL, MONIOCOREM='N'} - limpa
     *       campos de cobranca e desativa monitoramento. <i>Validado em
     *       transacao - passa sem disparar trigger.</i></li>
     * </ol>
     *
     * <p><b>O que NAO faz (limitacao honesta):</b> NAO cancela o boleto na
     * API do banco Itau. O boleto continua REGISTRADO no banco - se o cliente
     * receber e tentar pagar, ainda pode. Operador precisa cancelar manual
     * na tela "Gerenciar Cobranca" do Sankhya. Implementacao do cancelamento
     * via API exige investigacao adicional (id do boleto em TGFHBA tem
     * coluna ID que NAO eh IDENTITY, sem sequence, nao foi possivel validar
     * o worker async).
     *
     * @return quantidade de linhas TGFFIN afetadas pelo UPDATE
     */
    private static int limparCobrancaRegistrada(BigDecimal numnota) throws Exception {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // ==============================================================
            // 1) DESBLOQUEAR TRIGGER: DELETE FROM TGFRAF onde TIPO='E'
            //    A trigger TRG_INC_UPD_TGFFIN_MONIOCOREM verifica:
            //      IF EXISTS (TGFRAF R WHERE R.NUFIN=@NUFIN
            //                 AND EXISTS(TGFCRAF C ...)
            //                 AND NUREMESSA IS NOT NULL
            //                 AND TIPO='E'
            //                 AND SEQUENCIA = MAX SEQ)
            //         BLOQUEIA
            //    Sem deletar, qualquer UPDATE PROVISAO='S' falha.
            //    Validado em transacao: DELETE OK em TGFRAF (sem triggers).
            // ==============================================================
            PreparedStatement sRaf = null;
            try {
                sRaf = jdbc.getPreparedStatement(
                        "DELETE FROM TGFRAF "
                      + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ') "
                      + "  AND TIPO = 'E'");
                sRaf.setBigDecimal(1, numnota);
                int nRaf = sRaf.executeUpdate();
                LOGGER.info("[CancelamentoHelper] limparCobranca: " + nRaf
                        + " linha(s) TGFRAF (TIPO='E') removidas para desbloquear trigger - NUMNOTA=" + numnota);
            } catch (Exception eRaf) {
                LOGGER.log(Level.WARNING, "[CancelamentoHelper] limparCobranca: falha em DELETE TGFRAF", eRaf);
            } finally {
                if (sRaf != null) try { sRaf.close(); } catch (Exception ig) {}
            }

            // ==============================================================
            // 2) UPDATE TGFFIN: NOSSONUM=NULL, MONIOCOREM='N'
            //    Validado em transacao - nao dispara trigger MONIOCOREM porque
            //    PROVISAO nao muda (continua 'N'). MONIOCOREM='N' desativa
            //    monitoramento para nao aparecer em remessa futura.
            //    Coluna NUMTIT removida (NAO EXISTE em TGFFIN nesta instancia).
            // ==============================================================
            int totalUpdate = 0;
            PreparedStatement sUpd = null;
            try {
                sUpd = jdbc.getPreparedStatement(
                        "UPDATE TGFFIN SET NOSSONUM = NULL, MONIOCOREM = 'N' "
                      + "WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
                sUpd.setBigDecimal(1, numnota);
                totalUpdate = sUpd.executeUpdate();
                LOGGER.info("[CancelamentoHelper] limparCobranca: " + totalUpdate
                        + " linha(s) TGFFIN com NOSSONUM/MONIOCOREM zerados - NUMNOTA=" + numnota);
            } catch (Exception eUpd) {
                LOGGER.log(Level.WARNING, "[CancelamentoHelper] limparCobranca: falha no UPDATE TGFFIN NOSSONUM/MONIOCOREM", eUpd);
            } finally {
                if (sUpd != null) try { sUpd.close(); } catch (Exception ig) {}
            }

            // ==============================================================
            // 3) AVISO IMPORTANTE: boleto continua REGISTRADO no banco Itau.
            //    Operador precisa cancelar manualmente em "Gerenciar Cobranca".
            //    Logamos como WARNING para chamar atencao do operador.
            // ==============================================================
            LOGGER.warning("[CancelamentoHelper] ATENCAO: adiantamento NUMNOTA=" + numnota
                    + " sera cancelado no Sankhya. Se boleto ja foi REGISTRADO no banco (Itau/outro),"
                    + " operador deve cancelar manualmente em 'Gerenciar Cobranca' para evitar que"
                    + " o cliente receba o boleto e possa pagar valor sem nota.");

            return totalUpdate;
        } finally {
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }


    /**
     * DELETE direto e incondicional do adiantamento em todas as tabelas envolvidas.
     * Ultima cartada quando todas as outras estrategias falharam.
     *
     * <p>Faz tudo o que o desfazer nativo faria, mas SEM a validacao CORE_E00852
     * e SEM as triggers do Sankhya (pois antes ja limpamos cobranca registrada).
     *
     * <p>Ordem dos DELETEs:
     * <ol>
     *   <li>TGFFRE (vinculos de acerto)</li>
     *   <li>TFPIRB (IRRF)</li>
     *   <li>TGFFIN (registros do financeiro)</li>
     * </ol>
     *
     * @return quantidade de linhas TGFFIN deletadas
     */
    private static int deletarAdiantamentoForcado(BigDecimal numnota) throws Exception {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            String subSelectNufins = "(SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ')";

            // Ordem dos DELETEs (filhos antes de TGFFIN):
            // TGFRAF (registro autorizado financeiro) - antes para nao reativar trigger
            // TGFHBA (historico boletos API) - antes para nao deixar lixo
            // TGFFRE (vinculos despesa<->receita)
            // TFPIRB (IRRF)
            // TGFFIN (registros do financeiro - despesa + receita)
            String[] tabelasDependentes = new String[] {
                    "DELETE FROM TGFRAF WHERE NUFIN IN " + subSelectNufins,
                    "DELETE FROM TGFHBA WHERE NUFIN IN " + subSelectNufins,
                    "DELETE FROM TGFFRE WHERE NUFIN IN " + subSelectNufins,
                    "DELETE FROM TFPIRB WHERE NUFIN IN " + subSelectNufins
            };
            for (String sql : tabelasDependentes) {
                PreparedStatement s = null;
                try {
                    s = jdbc.getPreparedStatement(sql);
                    s.setBigDecimal(1, numnota);
                    int n = s.executeUpdate();
                    if (n > 0) {
                        LOGGER.info("[CancelamentoHelper] deletarForcado " + sql.substring(0, sql.indexOf(" WHERE"))
                                + " - " + n + " linhas removidas");
                    }
                } catch (Exception ig) {
                    LOGGER.fine("[CancelamentoHelper] deletarForcado ignorado: " + sql.substring(0, sql.indexOf(" WHERE"))
                            + " (tabela inexistente?) - " + ig.getMessage());
                } finally {
                    if (s != null) try { s.close(); } catch (Exception ex) {}
                }
            }

            // DELETE TGFFIN principal (despesa + receita do adiantamento)
            PreparedStatement s3 = jdbc.getPreparedStatement(
                    "DELETE FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ'");
            try {
                s3.setBigDecimal(1, numnota);
                int n3 = s3.executeUpdate();
                LOGGER.info("[CancelamentoHelper] deletarForcado TGFFIN - " + n3 + " linhas removidas (DESPESA+RECEITA)");
                return n3;
            } finally {
                try { s3.close(); } catch (Exception ig) {}
            }
        } finally {
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
        }
    }

    /**
     * Cancelamento legado por UPDATE - usado SOMENTE como ultima opcao quando
     * todas as tentativas anteriores (desfazer nativo, reverter baixa + desfazer)
     * falharam.
     *
     * <p>Marca os titulos como provisao='S' para sinalizar cancelamento sem
     * apagar fisicamente, preservando rastreabilidade contabil quando ha
     * baixas envolvidas.
     *
     * <p>IMPORTANTE: nao usa colunas opcionais (CODHISTBAIXA etc.) que
     * variam entre versoes do dicionario TGFFIN - o log de 13/05/2026
     * mostrou 'Nome de coluna CODHISTBAIXA invalido' nesta instancia.
     */
    private static void cancelarAdiantamentoLegado(BigDecimal numnota) throws Exception {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // SQL minimalista: SOMENTE PROVISAO/DHBAIXA/AD_NUNOTAADIANT
            // (colunas garantidas em todas versoes do Sankhya).
            // Nao inclui WHERE DHBAIXA IS NULL para tambem cobrir cenario
            // onde o BoletoAutoService ja baixou a receita.
            stmt = jdbc.getPreparedStatement(
                    "UPDATE TGFFIN SET "
                    + "PROVISAO = 'S', "
                    + "DHBAIXA = GETDATE(), "
                    + "AD_NUNOTAADIANT = NULL "
                    + "WHERE NUMNOTA = ? AND PROVISAO = 'N'");
            stmt.setBigDecimal(1, numnota);

            int linhas = stmt.executeUpdate();
            if (linhas > 0) {
                LOGGER.info("[CancelamentoHelper] Adiantamento cancelado via fallback UPDATE: NUMNOTA="
                        + numnota + " (" + linhas + " titulos marcados PROVISAO='S')");
            } else {
                LOGGER.warning("[CancelamentoHelper] Nenhum titulo cancelavel via fallback UPDATE para NUMNOTA="
                        + numnota + " - adiantamento ja deve estar PROVISAO='S' ou inexistente");
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

    // ================================================================
    // V16 - HELPERS para CASO B (boleto registrado): polling + delete fisico
    // ================================================================

    /**
     * POLLING SINCRONO ate {@code timeoutSegundos} aguardando o worker async do
     * Sankhya processar o pedido de baixa do boleto na Itau e atualizar a
     * TGFRAF para STATUS='E' (Enviado/Sucesso).
     *
     * <p><b>Como funciona:</b> a cada 2 segundos consulta TGFRAF buscando a
     * SEQUENCIA mais recente de TIPO='B' para os NUFINs do adiantamento.
     * Quando o STATUS muda de 'A' (Aguardando) para 'E' (Enviado), retorna
     * true imediatamente. Se ao final do timeout ainda estiver 'A', retorna
     * false e o caller deve cair no fallback SOFT CANCEL.
     *
     * <p><b>Primeira espera de 5s:</b> da tempo do worker async (ciclo de
     * ~1 min em prod) ler o INSERT que acabamos de fazer. O log do teste
     * 4862122 mostrou que o worker processou em 14s. 30s eh margem 2x.
     *
     * @param numnota         NUMNOTA do adiantamento
     * @param timeoutSegundos tempo maximo de espera (recomendado 30s)
     * @return true se baixa confirmada (TGFRAF SEQ_BAIXA STATUS='E'),
     *         false se timeout ou erro
     */
    private static boolean aguardarBaixaConfirmadaItau(BigDecimal numnota, int timeoutSegundos) {
        long fim = System.currentTimeMillis() + (timeoutSegundos * 1000L);
        int tentativa = 0;
        // Espera inicial pra dar tempo do worker async ler o INSERT
        try { Thread.sleep(5000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        while (System.currentTimeMillis() < fim) {
            tentativa++;
            String statusUltimaBaixa = consultarStatusUltimaBaixa(numnota);
            if ("E".equals(statusUltimaBaixa)) {
                LOGGER.info("[CancelamentoHelper] V16 POLLING tentativa #" + tentativa
                        + " - BAIXA CONFIRMADA pela Itau (TGFRAF STATUS='E') para NUMNOTA=" + numnota);
                return true;
            }
            LOGGER.fine("[CancelamentoHelper] V16 POLLING tentativa #" + tentativa
                    + " - status atual=" + statusUltimaBaixa + " (aguardando 'E')");
            try { Thread.sleep(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LOGGER.warning("[CancelamentoHelper] V16 POLLING TIMEOUT apos " + timeoutSegundos
                + "s (" + tentativa + " tentativas) - baixa Itau ainda nao confirmada para NUMNOTA=" + numnota);
        return false;
    }

    /**
     * Consulta o STATUS da TGFRAF SEQUENCIA mais recente com TIPO='B'
     * (pedido de baixa) para os NUFINs do adiantamento.
     *
     * @return "E" se confirmada, "A" se ainda aguardando, "?" se erro/inexistente
     */
    private static String consultarStatusUltimaBaixa(BigDecimal numnota) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            stmt = jdbc.getPreparedStatement(
                    "SELECT TOP 1 STATUS FROM TGFRAF WITH (NOLOCK) "
                  + "WHERE NUFIN IN (SELECT NUFIN FROM TGFFIN WITH (NOLOCK) "
                  + "                WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ') "
                  + "  AND TIPO = 'B' "
                  + "ORDER BY DTALTER DESC, SEQUENCIA DESC");
            stmt.setBigDecimal(1, numnota);
            rs = stmt.executeQuery();
            if (rs.next()) {
                String st = rs.getString("STATUS");
                return st != null ? st : "?";
            }
            return "?";
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[CancelamentoHelper] consultarStatusUltimaBaixa erro NUMNOTA=" + numnota, e);
            return "?";
        } finally {
            fechar(rs, stmt, jdbc);
        }
    }

    /**
     * DELETE FISICO completo do adiantamento (pos baixa Itau confirmada).
     * Executa na ORDEM (filhos antes do pai) para satisfazer FKs implicitas
     * e a trigger TRG_DLT_TGFFIN:
     *
     * <ol>
     *   <li>DELETE TGFRAF WHERE NUFIN IN (...) - autorizacao financeiro</li>
     *   <li>DELETE TGFHBA WHERE NUFIN IN (...) - historico boletos API</li>
     *   <li>DELETE TGFFRE WHERE NUACERTO=NUMDUPL AND TIPACERTO='A'</li>
     *   <li>DELETE TFPIRB WHERE NUFIN IN (...) - IRRF (pode nao existir)</li>
     *   <li>DELETE TGFFIN WHERE NUMDUPL=? AND DESDOBDUPL='ZZ' AND DHBAIXA IS NULL</li>
     * </ol>
     *
     * <p>WHERE DHBAIXA IS NULL eh essencial: trigger TRG_DLT_TGFFIN bloqueia
     * DELETE quando PROVISAO='N' AND DHBAIXA NOT NULL. Como ja aplicamos
     * PROVISAO='S' em B2, o filtro DHBAIXA IS NULL evita problema caso o
     * worker async tenha tambem baixado (DHBAIXA=GETDATE()) - nesse caso
     * deixamos o registro intacto para investigacao manual.
     *
     * @param numnota NUMNOTA do adiantamento (para WHERE em filhos via subselect)
     * @param numdupl NUMDUPL do adiantamento (para WHERE em TGFFRE e TGFFIN)
     * @return quantidade de linhas TGFFIN principal removidas
     */
    private static int deletarAdiantamentoCompleto(BigDecimal numnota, BigDecimal numdupl) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            String subSelectNufins = "(SELECT NUFIN FROM TGFFIN WHERE NUMNOTA = ? AND DESDOBDUPL = 'ZZ')";

            // 1) DELETE TGFRAF (filhos diretos do NUFIN)
            int nRaf = executeUpdateSafe(jdbc,
                    "DELETE FROM TGFRAF WHERE NUFIN IN " + subSelectNufins, numnota, "TGFRAF");

            // 2) DELETE TGFHBA (historico boletos API)
            int nHba = executeUpdateSafe(jdbc,
                    "DELETE FROM TGFHBA WHERE NUFIN IN " + subSelectNufins, numnota, "TGFHBA");

            // 3) DELETE TGFFRE (vinculos de acerto via NUACERTO=NUMDUPL)
            int nFre = 0;
            PreparedStatement sFre = null;
            try {
                sFre = jdbc.getPreparedStatement(
                        "DELETE FROM TGFFRE WHERE NUACERTO = ? AND TIPACERTO = 'A'");
                sFre.setBigDecimal(1, numdupl);
                nFre = sFre.executeUpdate();
            } catch (Exception ig) {
                LOGGER.fine("[CancelamentoHelper] V16 DELETE TGFFRE ignorado: " + ig.getMessage());
            } finally { if (sFre != null) try { sFre.close(); } catch (Exception ig) {} }

            // 4) DELETE TFPIRB (IRRF, pode nao existir nessa instancia)
            int nIrb = executeUpdateSafe(jdbc,
                    "DELETE FROM TFPIRB WHERE NUFIN IN " + subSelectNufins, numnota, "TFPIRB");

            // 5) DELETE TGFFIN principal (despesa + receita do adiantamento)
            int nFin = 0;
            PreparedStatement sFin = null;
            try {
                sFin = jdbc.getPreparedStatement(
                        "DELETE FROM TGFFIN "
                      + "WHERE NUMDUPL = ? AND DESDOBDUPL = 'ZZ' AND DHBAIXA IS NULL");
                sFin.setBigDecimal(1, numdupl);
                nFin = sFin.executeUpdate();
            } finally { if (sFin != null) try { sFin.close(); } catch (Exception ig) {} }

            LOGGER.info("[CancelamentoHelper] V16 deletarAdiantamentoCompleto NUMNOTA=" + numnota
                    + " NUMDUPL=" + numdupl + " - TGFRAF=" + nRaf + " TGFHBA=" + nHba
                    + " TGFFRE=" + nFre + " TFPIRB=" + nIrb + " TGFFIN=" + nFin);
            return nFin;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[CancelamentoHelper] V16 deletarAdiantamentoCompleto FALHOU "
                    + "NUMNOTA=" + numnota + " NUMDUPL=" + numdupl, e);
            return 0;
        } finally {
            if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ig) {}
        }
    }

    /**
     * Helper para executar DELETE ignorando erros de tabela inexistente.
     * Util para TGFRAF/TGFHBA/TFPIRB onde a tabela pode nao existir em
     * algumas instancias do Sankhya.
     */
    private static int executeUpdateSafe(br.com.sankhya.jape.dao.JdbcWrapper jdbc,
                                          String sql, BigDecimal param, String tabela) {
        PreparedStatement s = null;
        try {
            s = jdbc.getPreparedStatement(sql);
            s.setBigDecimal(1, param);
            int n = s.executeUpdate();
            if (n > 0) {
                LOGGER.info("[CancelamentoHelper] V16 " + tabela + " - " + n + " linha(s) removida(s)");
            }
            return n;
        } catch (Exception ig) {
            LOGGER.fine("[CancelamentoHelper] V16 " + tabela + " DELETE ignorado: " + ig.getMessage());
            return 0;
        } finally { if (s != null) try { s.close(); } catch (Exception ig) {} }
    }

    /**
     * SAFETY NET para o ReconciliacaoScheduler (V16).
     *
     * <p>Busca adiantamentos no estado "SOFT CANCEL aplicado + baixa confirmada"
     * (PROVISAO='S', AD_NUNOTAADIANT IS NULL, DESDOBDUPL='ZZ', DHBAIXA NULL,
     * NOSSONUM NOT NULL, com TGFRAF TIPO='B' STATUS='E') e completa o DELETE
     * FISICO. Cobre os casos em que o polling sincrono do clique deu TIMEOUT.
     *
     * @return quantidade de adiantamentos completamente removidos
     */
    public static int completarDeletePosBaixaItau() {
        java.util.List<BigDecimal[]> pendentes = new ArrayList<>(); // pares [NUMNOTA, NUMDUPL]
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Adiantamentos em estado "SOFT CANCEL aplicado + baixa confirmada".
            // Limitado aos ultimos 7 dias para nao varrer historico inteiro.
            String sql =
                "SELECT DISTINCT F.NUMNOTA, F.NUMDUPL "
              + "FROM TGFFIN F WITH (NOLOCK) "
              + "WHERE F.DESDOBDUPL = 'ZZ' "
              + "  AND F.PROVISAO = 'S' "
              + "  AND F.AD_NUNOTAADIANT IS NULL "
              + "  AND F.DHBAIXA IS NULL "
              + "  AND F.NOSSONUM IS NOT NULL "
              + "  AND F.DHMOV >= DATEADD(DAY, -7, GETDATE()) "
              + "  AND EXISTS (SELECT 1 FROM TGFRAF R WITH (NOLOCK) "
              + "              WHERE R.NUFIN = F.NUFIN "
              + "                AND R.TIPO = 'B' "
              + "                AND R.STATUS = 'E')";
            stmt = jdbc.getPreparedStatement(sql);
            rs = stmt.executeQuery();
            while (rs.next()) {
                BigDecimal numnota = rs.getBigDecimal("NUMNOTA");
                BigDecimal numdupl = rs.getBigDecimal("NUMDUPL");
                if (numnota != null && numdupl != null) {
                    pendentes.add(new BigDecimal[]{numnota, numdupl});
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] V16 completarDeletePosBaixaItau - erro na busca", e);
            return 0;
        } finally {
            fechar(rs, stmt, jdbc);
        }

        if (pendentes.isEmpty()) {
            LOGGER.fine("[CancelamentoHelper] V16 completarDeletePosBaixaItau - nenhum pendente");
            return 0;
        }

        LOGGER.info("[CancelamentoHelper] V16 completarDeletePosBaixaItau: " + pendentes.size()
                + " adiantamento(s) com baixa Itau confirmada - completando DELETE FISICO");
        int sucesso = 0;
        for (BigDecimal[] par : pendentes) {
            BigDecimal numnota = par[0];
            BigDecimal numdupl = par[1];
            try {
                int n = deletarAdiantamentoCompleto(numnota, numdupl);
                if (n > 0) {
                    sucesso++;
                    LOGGER.info("[CancelamentoHelper] V16 SAFETY NET DELETE OK - NUMNOTA=" + numnota
                            + " NUMDUPL=" + numdupl + " (removeu " + n + " linha TGFFIN)");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[CancelamentoHelper] V16 SAFETY NET DELETE falhou NUMNOTA="
                        + numnota, e);
            }
        }
        LOGGER.info("[CancelamentoHelper] V16 completarDeletePosBaixaItau concluido: "
                + sucesso + "/" + pendentes.size() + " removidos");
        return sucesso;
    }

    /**
     * Reconciliacao - SAFETY NET.
     * Localiza adiantamentos ATIVOS (PROVISAO='N', DHBAIXA NULL) cuja NUNOTA original:
     *   - foi DELETADA (nao existe em TGFCAB), OU
     *   - estah CANCELADA (STATUSNOTA='C'), OU
     *   - estah com PENDENTE='N' e sem NUMNOTA (pedido abortado nao faturado), OU
     *   - e PEDIDO PIX confirmado (ST=L, P=N) COM NUMNOTA, mas sem TGFVAR apos GRACE PERIOD
     *     (pedido foi confirmado mas nunca virou venda - fluxo comum na empresa que gera orfaos)
     * e que NAO possuem vinculo TGFVAR (nao viraram venda).
     *
     * GRACE PERIOD: minimo de horas desde DHMOV do adiantamento para evitar falso positivo
     * durante o fluxo normal de confirmacao (janela onde TGFVAR ainda pode ser criado).
     *
     * Para cada NUNOTA origem encontrada, dispara o cancelamento padrao.
     *
     * Retorna a quantidade de NUNOTAs reconciliadas (cancelamentos disparados).
     */
    public static int reconciliarOrfaos(int diasLookback) {
        // Compat backward (chamada antiga). Default antigo era 24h = 1440 minutos.
        return reconciliarOrfaos(diasLookback, 1440);
    }

    /**
     * @param diasLookback  janela maxima em dias (ex: 30)
     * @param graceMinutos  grace period em MINUTOS (era HORAS pre-V12).
     *                      Mais responsivo para detectar cancelamento de procedure
     *                      customizada como AD_MARCA_NAO_PENDENTE que faz UPDATE
     *                      direto em TGFITE/TGFCAB sem disparar JAPE event.
     */
    public static int reconciliarOrfaos(int diasLookback, int graceMinutos) {
        java.util.List<BigDecimal> nunotasOrfas = new ArrayList<>();
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Query unificada cobrindo 4 casos de orfaos + grace period + ausencia TGFVAR.
            // Criterios de orfaoria (pelo menos UM tem que ser verdade):
            //   (a) NOTA DELETADA
            //   (b) NOTA CANCELADA (STATUSNOTA='C')
            //   (c) NAO PENDENTE e SEM NUMNOTA (aborto sem faturar)
            //   (d) PEDIDO PIX confirmado (TIPMOV=P, ST=L, P=N) com NUMNOTA>0, apos grace period
            //       (cenario dos 12 casos reportados: pedido foi confirmado mas nunca
            //        gerou TGFVAR como origem - adiantamento fica eternamente orfao)
            //   (e) PEDIDO com TGFCAB.PENDENTE='N' mas FATURADO indiretamente (TGFVAR
            //       criado como destino, nao origem - pedido foi consolidado em outra nota)
            // O grace eh em MINUTOS (V12) - antes era em horas. Permite detectar
            // cancelamentos via SP customizada em ate (PERIOD_MINUTES + grace) min.
            String sql =
                  "SELECT DISTINCT F.AD_NUNOTAADIANT AS NUNOTA_ORIG "
                + "FROM TGFFIN F WITH (NOLOCK) "
                + "LEFT JOIN TGFCAB C WITH (NOLOCK) ON C.NUNOTA = F.AD_NUNOTAADIANT "
                + "WHERE F.AD_NUNOTAADIANT IS NOT NULL "
                + "  AND F.PROVISAO = 'N' "
                + "  AND F.DHBAIXA IS NULL "
                + "  AND F.DHMOV >= DATEADD(DAY, -?, GETDATE()) "
                + "  AND F.DHMOV <  DATEADD(MINUTE, -?, GETDATE()) "  // grace em MINUTOS (V12)
                + "  AND ( "
                + "        C.NUNOTA IS NULL "                                                            // (a)
                + "     OR C.STATUSNOTA = 'C' "                                                          // (b)
                + "     OR (C.PENDENTE = 'N' AND (C.NUMNOTA IS NULL OR C.NUMNOTA = 0)) "                 // (c)
                + "     OR (C.TIPMOV = 'P' AND C.STATUSNOTA = 'L' AND C.PENDENTE = 'N' "                 // (d)
                + "         AND C.NUMNOTA IS NOT NULL AND C.NUMNOTA > 0) "
                + "      ) "
                + "  AND NOT EXISTS (SELECT 1 FROM TGFVAR V WITH (NOLOCK) WHERE V.NUNOTAORIG = F.AD_NUNOTAADIANT)";
            stmt = jdbc.getPreparedStatement(sql);
            stmt.setInt(1, diasLookback);
            stmt.setInt(2, graceMinutos);
            rs = stmt.executeQuery();
            while (rs.next()) {
                BigDecimal n = rs.getBigDecimal("NUNOTA_ORIG");
                if (n != null) nunotasOrfas.add(n);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CancelamentoHelper] Erro ao buscar orfaos - reconciliacao abortada", e);
            return 0;
        } finally {
            fechar(rs, stmt, jdbc);
        }

        if (nunotasOrfas.isEmpty()) {
            LOGGER.fine("[CancelamentoHelper] Reconciliacao: nenhum orfao encontrado (lookback=" + diasLookback
                    + " dias, grace=" + graceMinutos + "min)");
            return 0;
        }

        LOGGER.info("[CancelamentoHelper] Reconciliacao: " + nunotasOrfas.size()
                + " NUNOTAs orfas detectadas (lookback=" + diasLookback + " dias, grace=" + graceMinutos
                + "min) - iniciando cancelamentos");
        int sucesso = 0;
        for (BigDecimal nunota : nunotasOrfas) {
            try {
                boolean tudoOk = cancelarAdiantamentosPorNota(nunota);
                if (tudoOk) sucesso++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[CancelamentoHelper] Falha ao reconciliar NUNOTA=" + nunota, e);
            }
        }
        LOGGER.info("[CancelamentoHelper] Reconciliacao concluida: " + sucesso + "/" + nunotasOrfas.size() + " cancelamentos OK");
        return sucesso;
    }
}
