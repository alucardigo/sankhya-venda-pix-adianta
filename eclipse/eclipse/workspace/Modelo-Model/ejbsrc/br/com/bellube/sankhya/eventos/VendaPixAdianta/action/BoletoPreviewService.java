package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import static br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoConstants.*;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.action.SessionContextHelper.SessionInfo;
/**
 * Serviço responsável por solicitar a pré-visualização de boletos utilizando
 * o mesmo caminho HTTP que a UI padrão (HttpServiceBroker -> BoletoSP.buildPreVisualizacao).
 */
public class BoletoPreviewService {
    private static final Logger LOGGER = Logger.getLogger(BoletoPreviewService.class.getName());
    private final BoletoRepository repository;
    public BoletoPreviewService(BoletoRepository repository) {
        this.repository = repository;
    }
    public static class PreviewResult {
        public String fileKey;
        public boolean success;
        public String errorMessage;
        public static PreviewResult success(String fileKey) {
            PreviewResult r = new PreviewResult();
            r.fileKey = fileKey;
            r.success = true;
            return r;
        }
        public static PreviewResult failure(String error) {
            PreviewResult r = new PreviewResult();
            r.success = false;
            r.errorMessage = error;
            return r;
        }
    }
    public PreviewResult generatePreview(ContextoAcao contextoAcao, BigDecimal nufin, BigDecimal numnota) {
        if (nufin == null) {
            return PreviewResult.failure("NUFIN is required");
        }
        return generateViaHttp(contextoAcao, nufin);
    }
    private PreviewResult generateViaHttp(ContextoAcao contextoAcao, BigDecimal nufin) {
        try {
            if (contextoAcao == null) {
                return PreviewResult.failure("Contexto da ação não disponível");
            }
            BigDecimal codCtaBcoInt = repository.obterCodCtaBcoIntDoTitulo(nufin);
            if (codCtaBcoInt == null) {
                return PreviewResult.failure("Título sem CODCTABCOINT");
            }
            BigDecimal modelo = repository.obterCodigoRelatorio(codCtaBcoInt);
            if (modelo == null) {
                return PreviewResult.failure("Conta bancária sem modelo de boleto (TSICTA.MODBOLETA)");
            }
            repository.reservarRemessaBancaria(codCtaBcoInt);
            boolean hasNossoNumero = repository.verificarNossoNumeroExiste(nufin);
            String payloadJson = montarPayloadJson(
                    nufin,
                    codCtaBcoInt,
                    modelo,
                    !hasNossoNumero,          // gerar n� quando ainda n�o existe
                    hasNossoNumero,           // reimpress�o apenas se j� existe n�mero
                    hasNossoNumero ? "T" : TIPO_REIMPRESSAO_PRIMEIRA
            );
            SessionInfo session = SessionContextHelper.getCurrentSessionInfo();
            String primaryCookie = session != null ? session.cookieHeader : null;
            String primarySession = session != null ? (session.jsessionId != null && !session.jsessionId.isEmpty() ? session.jsessionId : session.token) : null;
            String response = enviarRequisicaoBoleto(payloadJson, session, primaryCookie, primarySession);
            if (response != null && !response.isEmpty()) {
                String key = extractFileKeyFromResponse(response);
                if (key != null && !key.isEmpty()) {
                    return PreviewResult.success(key);
                }
                String statusMessage = extractStatusMessage(response);
                if (statusMessage != null && !statusMessage.isEmpty()) {
                    return PreviewResult.failure(statusMessage);
                }
                // retry once with token-only cookie if licenci/sessao falha
                if (statusMessage != null && statusMessage.toLowerCase().contains("licenc") || statusMessage.toLowerCase().contains("sessao")) {
                    String fallbackCookie = SessionContextHelper.buildCookieHeader(session != null ? session.token : null, null);
                    String fallbackSession = session != null ? session.token : null;
                    String retryResponse = enviarRequisicaoBoleto(payloadJson, session, fallbackCookie, fallbackSession);
                    String retryKey = extractFileKeyFromResponse(retryResponse);
                    if (retryKey != null && !retryKey.isEmpty()) {
                        return PreviewResult.success(retryKey);
                    }
                    String retryStatus = extractStatusMessage(retryResponse);
                    if (retryStatus != null && !retryStatus.isEmpty()) {
                        return PreviewResult.failure(retryStatus);
                    }
                }
                LOGGER.warning(LOG_PREFIX + "Resposta da pré-visualização sem chave: " +
                        TextUtils.asciiSanitize(response.length() > 200 ? response.substring(0, 200) + "..." : response));
            }
            return PreviewResult.failure("Servico de boleto nao retornou a chave do PDF");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, LOG_PREFIX + "Falha ao enviar requisição de boleto", e);
            return PreviewResult.failure(e.getMessage());
        }
    }
    private String montarPayloadJson(BigDecimal nufin,
                                     BigDecimal codCtaBcoInt,
                                     BigDecimal modelo,
                                     boolean gerarNumeroBoleto,
                                     boolean reimprimir,
                                     String tipoReimpressao) {
        String codCtaStr = codCtaBcoInt.toPlainString();
        String modeloStr = modelo.toPlainString();
        String nufinStr = nufin.toPlainString();
        return "{\"serviceName\":\"" + SERVICE_BOLETO_PREVIEW + "\"," +
                "\"requestBody\":{\"configBoleto\":{" +
                "\"agrupamentoBoleto\":\"" + AGRUPAMENTO_BOLETO + "\"," +
                "\"ordenacaoParceiro\":" + ORDENACAO_PARCEIRO + "," +
                "\"dupRenegociadas\":false," +
                "\"gerarNumeroBoleto\":" + gerarNumeroBoleto + "," +
                "\"visualizaPDFBoleto\":true," +
                "\"codigoConta\":\"" + codCtaStr + "\"," +
                "\"alterarTipoTitulo\":false," +
                "\"tipoTitulo\":" + TIPO_TITULO_DEFAULT + "," +
                "\"bcoIgualConta\":false," +
                "\"empIgualConta\":false," +
                "\"reimprimir\":" + reimprimir + "," +
                "\"tipoReimpressao\":\"" + tipoReimpressao + "\"," +
                "\"registraConta\":false," +
                "\"codigoRelatorio\":" + modeloStr + "," +
                "\"codCtaBcoInt\":\"" + codCtaStr + "\"," +
                "\"telaImpressaoBoleto\":true," +
                "\"multiTransacional\":true," +
                "\"boleto\":{\"binicial\":\"\",\"bfinal\":\"\"}," +
                "\"titulo\":[{\"$\":\"" + nufinStr + "\"}]}}}";
    }
    private String enviarRequisicaoBoleto(String payloadJson, SessionInfo session, String cookie, String mgeSessionOverride) throws Exception {
        String baseUrl = session != null && session.hasBaseUrl() ? session.baseUrl : DEFAULT_BASE_URL;
        String mgeSession = (mgeSessionOverride != null && !mgeSessionOverride.isEmpty()) ? mgeSessionOverride : (session != null ? session.jsessionId : null);
        if ((mgeSession == null || mgeSession.isEmpty()) && session != null && session.token != null && !session.token.isEmpty()) {
            mgeSession = session.token;
        }
        String counter = String.valueOf(System.currentTimeMillis() % 100000);
        String globalId = SessionContextHelper.generateUid();
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append(SERVICE_PATH)
                .append("?serviceName=").append(SERVICE_BOLETO_PREVIEW)
                .append("&outputType=json&preventTransform=false&vss=1")
                .append("&counter=").append(counter)
                .append("&globalID=").append(URLEncoder.encode(globalId, StandardCharsets.UTF_8.name()))
                .append("&application=").append(APPLICATION_BOLETO)
                .append("&resourceID=").append(RESOURCE_ID_BOLETO);
        if (mgeSession != null && !mgeSession.isEmpty()) {
            urlBuilder.append("&mgeSession=").append(URLEncoder.encode(mgeSession, StandardCharsets.UTF_8.name()));
        }
        String serviceUrl = urlBuilder.toString();
        String cookieToUse = cookie;
        if (cookieToUse == null || cookieToUse.isEmpty()) {
            String cookieSource = session != null && session.jsessionId != null ? session.jsessionId : (session != null ? session.token : null);
            cookieToUse = SessionContextHelper.buildCookieHeader(cookieSource, session != null ? session.cookieHeader : null);
        }
        byte[] payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
        HttpResult initial = doPost(serviceUrl, cookieToUse, payloadBytes);
        if (isRedirect(initial.code) && initial.location != null && !initial.location.isEmpty()) {
            String redirectUrl = buildRedirectUrl(serviceUrl, initial.location);
            HttpResult redirected = doPost(redirectUrl, cookieToUse, payloadBytes);
            logIfNotOk(redirected);
            return redirected.body;
        }
        logIfNotOk(initial);
        return initial.body;
    }
    private String readAll(InputStream is) throws Exception {
        if (is == null) {
            return null;
        }
        try (InputStream input = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = input.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static class HttpResult {
        int code;
        String body;
        String location;
    }
    private HttpResult doPost(String url, String cookie, byte[] payloadBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_BOLETO_CONNECT);
        conn.setReadTimeout(TIMEOUT_BOLETO_READ);
        conn.setDoOutput(true);
        conn.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        conn.setRequestProperty(HEADER_ACCEPT, ACCEPT_JSON);
        conn.setRequestProperty(HEADER_X_REQUESTED_WITH, XML_HTTP_REQUEST);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty(HEADER_COOKIE, cookie);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payloadBytes);
            os.flush();
        }
        HttpResult result = new HttpResult();
        result.code = conn.getResponseCode();
        result.location = conn.getHeaderField("Location");
        InputStream is = (result.code >= 200 && result.code < 300) ? conn.getInputStream() : conn.getErrorStream();
        result.body = readAll(is);
        conn.disconnect();
        return result;
    }
    private boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308;
    }
    private String buildRedirectUrl(String originalUrl, String location) throws Exception {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        URL base = new URL(originalUrl);
        return new URL(base, location).toString();
    }
    private void logIfNotOk(HttpResult result) {
        if (result.code >= 200 && result.code < 300) {
            return;
        }
        String locationInfo = (result.location != null && !result.location.isEmpty())
                ? " Location=" + TextUtils.asciiSanitize(result.location)
                : "";
        LOGGER.warning(LOG_PREFIX + "HTTP " + result.code + " ao gerar boleto: " +
                (result.body != null && !result.body.isEmpty() ? TextUtils.asciiSanitize(result.body) : "<sem corpo>") +
                locationInfo);
    }

    private String extractFileKeyFromResponse(String response) {
        String key = TextUtils.extractFileKey(response);
        if (key != null && !key.isEmpty()) {
            return key;
        }
        if (response == null || response.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("\"valor\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String extractStatusMessage(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("\"statusMessage\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
        if (m.find()) {
            return TextUtils.asciiSanitize(m.group(1));
        }
        return null;
    }
}
