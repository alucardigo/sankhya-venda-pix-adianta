package br.com.bellube.sankhya.eventos.VendaPixAdianta.service;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoRepository;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;
import br.com.sankhya.modelcore.comercial.BoletoHelper;
import br.com.sankhya.modelcore.comercial.BoletoHelper.ConfiguracaoBoleto;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servico responsavel pela geracao automatica de boletos apos criacao de adiantamentos PIX.
 *
 * Utiliza a API interna do Sankhya (BoletoHelper) para gerar nosso numero,
 * registrar no banco (Itau via TradeMaster/VendaMais) e produzir o PDF.
 *
 * Chamado pelo AsyncAdiantamentoProcessor apos criacao bem-sucedida do adiantamento.
 */
public class BoletoAutoService {

    private static final Logger LOGGER = Logger.getLogger(BoletoAutoService.class.getName());
    private static final String LOG_PREFIX = "[BOLETO-AUTO] ";

    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 30_000; // 30s entre retries (registro bancario demora)

    private static final int AGRUPAMENTO_POR_NUFIN = 4;
    private static final int SAIDA_PDF = 0; // SAIDA_BOLETO_VISUALIZACAO_PDF

    private final BoletoRepository repository;

    public BoletoAutoService() {
        this.repository = new BoletoRepository();
    }

    public BoletoAutoService(BoletoRepository repository) {
        this.repository = repository;
    }

    /**
     * Gera boleto automaticamente para o adiantamento recem-criado.
     *
     * @param nunotaVenda    NUNOTA da venda PIX original
     * @param numNotaAdiant  NUMNOTA do adiantamento criado
     * @param codemp         Codigo da empresa
     * @return BoletoResult com sucesso/falha e PDF
     */
    public BoletoResult gerarBoletoAutomatico(BigDecimal nunotaVenda, BigDecimal numNotaAdiant, BigDecimal codemp) {
        LOGGER.info(LOG_PREFIX + "Iniciando geracao automatica - NUNOTA=" + nunotaVenda
                + " NUMNOTA_ADIANT=" + numNotaAdiant + " CODEMP=" + codemp);

        try {
            // 1. Buscar NUFIN do titulo de receita
            BigDecimal nufin = buscarNufinReceita(nunotaVenda);
            if (nufin == null) {
                String msg = "NUFIN de receita nao encontrado para NUNOTA=" + nunotaVenda;
                LOGGER.warning(LOG_PREFIX + msg);
                return BoletoResult.failure(msg);
            }
            LOGGER.info(LOG_PREFIX + "NUFIN encontrado: " + nufin);

            // 2. Buscar CODCTABCOINT (conta bancaria)
            BigDecimal codCtaBcoInt = repository.obterCodCtaBcoInt(codemp);
            if (codCtaBcoInt == null) {
                String msg = "CODCTABCOINT nao configurado na AD_TGFCAA para CODEMP=" + codemp;
                LOGGER.warning(LOG_PREFIX + msg);
                return BoletoResult.failure(nufin, msg);
            }
            LOGGER.info(LOG_PREFIX + "CODCTABCOINT=" + codCtaBcoInt);

            // 3. Buscar modelo de boleto
            BigDecimal modelo = repository.obterCodigoRelatorio(codCtaBcoInt);
            if (modelo == null) {
                String msg = "Modelo de boleto (MODBOLETA) nao configurado na TSICTA para CODCTABCOINT=" + codCtaBcoInt;
                LOGGER.warning(LOG_PREFIX + msg);
                return BoletoResult.failure(nufin, msg);
            }
            LOGGER.info(LOG_PREFIX + "Modelo boleto=" + modelo);

            // 4. Gerar boleto com retry
            return gerarComRetry(nufin, codCtaBcoInt, modelo, nunotaVenda);

        } catch (Exception e) {
            String msg = "Erro inesperado ao gerar boleto para NUNOTA=" + nunotaVenda + ": " + e.getMessage();
            LOGGER.log(Level.SEVERE, LOG_PREFIX + msg, e);
            return BoletoResult.failure(msg);
        }
    }

    /**
     * FASE 1: Gerar nosso numero e registrar titulo no TGFFIN.
     * O BoletoHelper gera o nosso numero e coloca o titulo na fila do ApiBancosHelper.
     * O ApiBancosHelper (job automatico ~1min) envia para API Itau e recebe confirmacao.
     *
     * FASE 2: PDF so pode ser gerado APOS o banco confirmar o registro.
     * Em vez de tentar gerar PDF aqui (que falha com "nao registrado junto ao banco"),
     * geramos apenas o nosso numero. O PDF fica disponivel via botao manual ou reimpressao
     * apos o ApiBancosHelper confirmar o registro (~1-2min).
     */
    private BoletoResult gerarComRetry(BigDecimal nufin, BigDecimal codCtaBcoInt,
                                        BigDecimal modelo, BigDecimal nunotaVenda) {
        Exception lastException = null;

        for (int tentativa = 1; tentativa <= MAX_RETRY; tentativa++) {
            try {
                LOGGER.info(LOG_PREFIX + "Tentativa " + tentativa + "/" + MAX_RETRY
                        + " - NUFIN=" + nufin);

                BoletoHelper boletoHelper = new BoletoHelper();
                ConfiguracaoBoleto config = montarConfig(nufin, codCtaBcoInt, modelo);
                boletoHelper.setConfigBoleto(config);
                boletoHelper.gerarBoletoPorNota(nufin);

                // Buscar nosso numero gerado (o principal objetivo)
                String nossoNumero = buscarNossoNumero(nufin);

                // Tentar obter PDF (pode falhar se banco ainda nao confirmou - OK)
                byte[] pdf = null;
                try {
                    pdf = boletoHelper.getBoletosPDF();
                } catch (Exception pdfEx) {
                    LOGGER.info(LOG_PREFIX + "PDF nao disponivel ainda (normal - aguardando registro bancario): "
                            + pdfEx.getMessage());
                }

                if (nossoNumero != null) {
                    LOGGER.info(LOG_PREFIX + "Boleto gerado com sucesso - NUFIN=" + nufin
                            + " NOSSONUM=" + nossoNumero
                            + " PDF=" + (pdf != null ? pdf.length + " bytes" : "pendente registro bancario"));
                    return BoletoResult.success(nufin, nossoNumero, pdf);
                }

                // Se nosso numero nao foi gerado, tentar novamente
                LOGGER.warning(LOG_PREFIX + "NOSSONUM nulo apos gerarBoletoPorNota - tentativa " + tentativa);

            } catch (Exception e) {
                lastException = e;
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();

                LOGGER.warning(LOG_PREFIX + "Tentativa " + tentativa + " falhou: " + errMsg);

                // Se o erro e "nao registrado junto ao banco" mas nosso numero JA existe, e sucesso parcial
                String nossoNumCheck = buscarNossoNumero(nufin);
                if (nossoNumCheck != null) {
                    LOGGER.info(LOG_PREFIX + "NOSSONUM=" + nossoNumCheck
                            + " ja gerado apesar do erro de PDF - considerando sucesso parcial");
                    return BoletoResult.success(nufin, nossoNumCheck, null);
                }

                // Verificar se e erro transiente (registro bancario)
                if (isTransient(e) && tentativa < MAX_RETRY) {
                    LOGGER.info(LOG_PREFIX + "Erro transiente detectado, aguardando " + RETRY_DELAY_MS + "ms...");
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return BoletoResult.failure(nufin, "Interrompido durante retry: " + errMsg);
                    }
                } else if (!isTransient(e)) {
                    break;
                }
            }
        }

        String msg = "Boleto nao gerado apos " + MAX_RETRY + " tentativas para NUFIN=" + nufin;
        if (lastException != null) {
            msg += " - Ultimo erro: " + lastException.getMessage();
        }
        LOGGER.severe(LOG_PREFIX + msg);
        return BoletoResult.failure(nufin, msg);
    }

    /**
     * Monta a ConfiguracaoBoleto com os parametros corretos.
     */
    private ConfiguracaoBoleto montarConfig(BigDecimal nufin, BigDecimal codCtaBcoInt, BigDecimal modelo) {
        ConfiguracaoBoleto config = new ConfiguracaoBoleto();

        // Gerar nosso numero (primeira emissao)
        config.setGerarNumeroBoleto(true);

        // NAO gerar PDF aqui - o banco precisa confirmar o registro primeiro (~1min via ApiBancosHelper)
        // PDF fica disponivel via botao manual apos confirmacao
        config.setVisualizaPDFBoleto(false);
        config.setTipoSaidaBoleto(SAIDA_PDF);

        // Agrupamento por NUFIN individual
        config.setAgrupamentoBoleto(AGRUPAMENTO_POR_NUFIN);

        // Titulo a gerar
        Collection<BigDecimal> titulos = new ArrayList<>(1);
        titulos.add(nufin);
        config.setFinanceirosSelecionados(titulos);

        // Conta bancaria e modelo
        config.setCodCtaBcoInt(codCtaBcoInt);
        config.setCodigoRelatorio(modelo);

        // Nao reimprimir (primeira emissao)
        config.setReimprimirBoleta(false);
        config.setReimpressao(false);

        // Sem alteracao de tipo titulo
        config.setAlterarTipo(false);
        config.setTipoTitulo(new BigDecimal(-1));

        // Sem filtros extras
        config.setBcoIgualConta(false);
        config.setEmpIgualConta(false);
        config.setDupRenegociadas(false);
        config.setRegistraConta(false);

        // Nao enviar email pelo BoletoHelper (fazemos via NotificacaoService)
        config.setEnviarEmail(false);
        config.setEnviarEmailParceiro(false);

        return config;
    }

    /**
     * Busca o NUFIN do titulo de receita do adiantamento.
     */
    private BigDecimal buscarNufinReceita(BigDecimal nunotaVenda) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement(
                    "SELECT TOP 1 NUFIN FROM TGFFIN WITH (NOLOCK) " +
                    "WHERE AD_NUNOTAADIANT = ? AND RECDESP = 1 AND PROVISAO = 'N' " +
                    "ORDER BY NUFIN DESC");
            ps.setBigDecimal(1, nunotaVenda);
            rs = ps.executeQuery();
            return rs.next() ? rs.getBigDecimal(1) : null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOG_PREFIX + "Erro ao buscar NUFIN receita para NUNOTA=" + nunotaVenda, e);
            return null;
        } finally {
            closeQuietly(rs, ps, jdbc);
        }
    }

    /**
     * Busca o NOSSONUM gerado apos a emissao do boleto.
     */
    private String buscarNossoNumero(BigDecimal nufin) {
        br.com.sankhya.jape.dao.JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            jdbc = br.com.sankhya.modelcore.util.EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            ps = jdbc.getPreparedStatement(
                    "SELECT NOSSONUM FROM TGFFIN WITH (NOLOCK) WHERE NUFIN = ?");
            ps.setBigDecimal(1, nufin);
            rs = ps.executeQuery();
            if (rs.next()) {
                String nn = rs.getString(1);
                return (nn != null && !nn.trim().isEmpty()) ? nn.trim() : null;
            }
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao buscar NOSSONUM para NUFIN=" + nufin, e);
            return null;
        } finally {
            closeQuietly(rs, ps, jdbc);
        }
    }

    /**
     * Verifica se o erro e transiente (registro bancario, timeout, conexao).
     */
    private boolean isTransient(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return msg.contains("registrado") // "Boleto(s) nao registrado(s) junto ao banco"
                || msg.contains("timeout")
                || msg.contains("connection")
                || msg.contains("tente novamente")
                || msg.contains("trademaster")
                || msg.contains("vendamais")
                || msg.contains("socket")
                || msg.contains("deadlock");
    }

    private void closeQuietly(ResultSet rs, PreparedStatement ps, br.com.sankhya.jape.dao.JdbcWrapper jdbc) {
        if (rs != null) try { rs.close(); } catch (Exception ignore) {}
        if (ps != null) try { ps.close(); } catch (Exception ignore) {}
        if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
    }
}
