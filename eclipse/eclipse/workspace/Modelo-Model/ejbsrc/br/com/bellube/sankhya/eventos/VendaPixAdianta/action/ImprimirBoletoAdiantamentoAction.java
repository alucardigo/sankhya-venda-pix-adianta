package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.action.TextUtils;
import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

import static br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoConstants.*;

/**
 * Botão de ação para exibir diretamente o boleto do adiantamento PIX.
 * Implementação enxuta que reutiliza o repositório e o serviço de preview.
 */
public class ImprimirBoletoAdiantamentoAction implements AcaoRotinaJava {

    private static final Logger LOGGER = Logger.getLogger(ImprimirBoletoAdiantamentoAction.class.getName());

    private final BoletoRepository repository;
    private final BoletoPreviewService previewService;

    public ImprimirBoletoAdiantamentoAction() {
        this.repository = new BoletoRepository();
        this.previewService = new BoletoPreviewService(repository);
    }

    @Override
    public void doAction(ContextoAcao contexto) throws Exception {
        try {
            Registro[] linhas = contexto != null ? contexto.getLinhas() : null;
            if (linhas == null || linhas.length == 0) {
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "ATENÇÃO\n\nSelecione uma venda antes de executar o botão."));
                return;
            }
            if (linhas.length > 1) {
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "ATENÇÃO\n\nSelecione apenas uma venda por vez. Linhas selecionadas: " + linhas.length));
                return;
            }

            BigDecimal nunota = toBigDecimal(linhas[0].getCampo("NUNOTA"));
            if (nunota == null) {
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "ERRO\n\nNão foi possível identificar o NUNOTA da venda selecionada."));
                return;
            }

            BigDecimal numnota = repository.buscarNumnotaAdiantamento(nunota);
            if (numnota == null) {
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "INFORMAÇÃO\n\nAinda não existe boleto vinculado ao adiantamento desta venda."));
                return;
            }

            BigDecimal nufin = repository.buscarNufinPorNumnota(numnota);
            if (nufin == null) {
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "INFORMAÇÃO\n\nO título financeiro do boleto não foi encontrado. Verifique na TGFFIN."));
                return;
            }

            BoletoPreviewService.PreviewResult preview = previewService.generatePreview(contexto, nufin, numnota);
            if (!preview.success || preview.fileKey == null || preview.fileKey.isEmpty()) {
                String motivo = preview.errorMessage != null ? preview.errorMessage : "Serviço não retornou a chave do PDF.";
                contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                        "ERRO AO GERAR PDF\n\n" + motivo));
                return;
            }

            String link = buildViewerLink(preview.fileKey);
            contexto.setMensagemRetorno(TextUtils.asciiSanitize(buildSuccessMessage(nunota, numnota, nufin, link)));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOG_PREFIX + "Erro ao executar botão", e);
            contexto.setMensagemRetorno(TextUtils.asciiSanitize(
                    "ERRO\n\nNão foi possível abrir o boleto automaticamente.\nDetalhes: " + e.getMessage()));
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value != null) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException ignore) {
            }
        }
        return null;
    }

    private String buildViewerLink(String chave) {
        String baseUrl = SessionContextHelper.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        return baseUrl + VIEWER_PATH + "?chaveArquivo=" + chave + "&download=N";
    }

    private String buildSuccessMessage(BigDecimal nunotaVenda, BigDecimal numnotaBoleto, BigDecimal nufin, String link) {
        StringBuilder sb = new StringBuilder();
        sb.append("BOLETO DISPONÍVEL\n\n")
          .append("Venda (NUNOTA): ").append(nunotaVenda).append("\n")
          .append("Boleto (NUMNOTA): ").append(numnotaBoleto).append("\n")
          .append("Título (NUFIN): ").append(nufin).append("\n\n")
          .append("Clique ou copie o endereço abaixo para abrir o PDF:\n")
          .append(link);
        return sb.toString();
    }
}
