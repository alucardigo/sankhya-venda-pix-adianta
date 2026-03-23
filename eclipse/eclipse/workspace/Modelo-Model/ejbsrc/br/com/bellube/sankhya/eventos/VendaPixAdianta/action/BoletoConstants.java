package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

/**
 * Constants for Boleto operations.
 * Centralizes all magic strings and configuration values.
 * 
 * @author VendaPixAdianta Module
 * @version 5.0.0 - Professional Refactoring
 */
public final class BoletoConstants {

    public static final String SERVICE_BOLETO_PREVIEW = "BoletoSP.buildPreVisualizacao";
    public static final String VIEWER_PATH = "/mge/visualizadorArquivos.mge";
    public static final String SERVICE_PATH = "/mge/service.sbr";
    public static final String APPLICATION_BOLETO = "ImpressaoBoletoGrafico";
    public static final String RESOURCE_ID_BOLETO = "br.com.sankhya.fin.impressao.boletos.grafico";

    // Default Values
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";
    public static final String COD_CONTA_PADRAO = ""; // vazio = padrão da conta do título

    // Boleto Config Values
    public static final String AGRUPAMENTO_BOLETO = "4";
    public static final int ORDENACAO_PARCEIRO = 1;
    public static final int TIPO_TITULO_DEFAULT = -1;
    public static final String TIPO_REIMPRESSAO_PRIMEIRA = "N";
    

    // SQL Table Names
    public static final String TABLE_TGFCAB = "TGFCAB";
    public static final String TABLE_TGFFIN = "TGFFIN";
    public static final String TABLE_TGFTPV = "TGFTPV";
    public static final String TABLE_AD_TGFCAA = "AD_TGFCAA";
    public static final String TABLE_TSICTA = "TSICTA";

    // SQL Column Names
    public static final String COL_NUNOTA = "NUNOTA";
    public static final String COL_NUFIN = "NUFIN";
    public static final String COL_NUMNOTA = "NUMNOTA";
    public static final String COL_RECDESP = "RECDESP";
    public static final String COL_NOSSONUM = "NOSSONUM";
    public static final String COL_CODEMP = "CODEMP";
    public static final String COL_CODCTABCOINT = "CODCTABCOINT";
    public static final String COL_MODBOLETA = "MODBOLETA";
    public static final String COL_AD_GERAADIANT = "AD_GERAADIANT";
    public static final String COL_AD_NUNOTAADIANT = "AD_NUNOTAADIANT";
    public static final String COL_CODPARC = "CODPARC";
    public static final String COL_CODTIPVENDA = "CODTIPVENDA";

    

    // Logger Prefix
    public static final String LOG_PREFIX = "[ImprimirBoletoAdiantamento] ";

    // Reflection Class Names
    public static final String CLASS_SERVICE_CONTEXT = "br.com.sankhya.ws.ServiceContext";
    public static final String CLASS_MGE_CORE_PARAMETER = "br.com.sankhya.modelcore.util.MGECoreParameter";
    public static final String CLASS_SESSION_FILE = "com.sankhya.util.SessionFile";
    public static final String CLASS_UID_GENERATOR = "com.sankhya.util.UIDGenerator";
    public static final String CLASS_SYSTEM_CACHE = "br.com.sankhya.jape.system.SystemCache";

    // System Properties
    public static final String PROP_JBOSS_NODE_NAME = "jboss.node.name";
    public static final String PROP_URL_SANKHYAW = "URLSANKHYAW";

    // Viewer Cache
    public static final String CACHE_VISUALIZADOR_ARQUIVOS = "VisualizadorArquivos";

    // HTTP Headers used for base URL extraction
    public static final String HEADER_COOKIE = "Cookie";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String XML_HTTP_REQUEST = "XMLHttpRequest";
    public static final String HEADER_X_REQUESTED_WITH = "X-Requested-With";
    public static final String HEADER_X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final String HEADER_X_FORWARDED_HOST = "X-Forwarded-Host";
    public static final String HEADER_X_FORWARDED_PORT = "X-Forwarded-Port";
    public static final String COOKIE_JSESSIONID = "JSESSIONID";

    // File key prefix used by viewer
    public static final String FILE_PREFIX_BOLETO = "boleto_";
    public static final String PDF_SIGNATURE = "%PDF";
    public static final String CHARSET_UTF8 = "UTF-8";
    public static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    public static final String ACCEPT_JSON = "application/json";
    public static final int TIMEOUT_BOLETO_CONNECT = 8000;
    public static final int TIMEOUT_BOLETO_READ = 30000;

    // RECDESP Values
    public static final int RECDESP_RECEITA = 1; // Receita (boleto)


    private BoletoConstants() {
        throw new AssertionError("Constants class cannot be instantiated");
    }
}
