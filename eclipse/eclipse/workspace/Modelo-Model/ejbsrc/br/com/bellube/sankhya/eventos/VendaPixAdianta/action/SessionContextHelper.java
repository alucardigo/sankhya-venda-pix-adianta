package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoConstants.*;

/**
 * Helper class for session context operations.
 * Encapsulates all reflection-based access to Sankhya ServiceContext.
 * Handles token, cookie, and base URL management.
 * 
 * Single Responsibility: Session and authentication context
 * 
 * @author VendaPixAdianta Module
 * @version 5.0.0 - Professional Refactoring
 */
public class SessionContextHelper {

    private static final Logger LOGGER = Logger.getLogger(SessionContextHelper.class.getName());

    /**
     * Session information container.
     */
    public static class SessionInfo {
        public String token;
        public String cookieHeader;
        public String jsessionId;
        public String baseUrl;

        public SessionInfo() {
        }

        public boolean hasToken() {
            return token != null && !token.isEmpty();
        }

        public boolean hasCookie() {
            return cookieHeader != null && !cookieHeader.isEmpty();
        }

        public boolean hasBaseUrl() {
            return baseUrl != null && !baseUrl.isEmpty();
        }
    }

    /**
     * Retrieves complete session information from current ServiceContext.
     * Includes token, cookies, and base URL with proxy header support.
     * 
     * @return SessionInfo object with all available session data
     */
    public static SessionInfo getCurrentSessionInfo() {
        SessionInfo info = new SessionInfo();

        try {
            Class<?> scClass = Class.forName(CLASS_SERVICE_CONTEXT);
            Object context = scClass.getMethod("getCurrent").invoke(null);

            if (context == null) {
                LOGGER.fine(LOG_PREFIX + "ServiceContext.getCurrent() returned null");
                return info;
            }

            // Get token
            try {
                Object tokenObj = scClass.getMethod("getToken").invoke(context);
                if (tokenObj != null) {
                    info.token = String.valueOf(tokenObj);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to get token", e);
            }

            // Get HTTP request object
            Object request = getHttpRequest(context, scClass);
            if (request != null) {
                // Extract cookies
                info.cookieHeader = extractCookieHeader(request);
                info.jsessionId = extractJSessionId(info.cookieHeader);

                // Extract base URL with proxy support
                info.baseUrl = extractBaseUrl(request);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Error retrieving session info", e);
        }

        return info;
    }

    /**
     * Gets the authentication token from current session.
     * 
     * @return Token string, or null if not available
     */
    public static String getCurrentToken() {
        try {
            Class<?> scClass = Class.forName(CLASS_SERVICE_CONTEXT);
            Object context = scClass.getMethod("getCurrent").invoke(null);

            if (context != null) {
                Object tokenObj = scClass.getMethod("getToken").invoke(context);
                return tokenObj != null ? String.valueOf(tokenObj) : null;
            }

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to get current token", e);
        }

        return null;
    }

    /**
     * Gets the base URL from configuration or current request.
     * Prioritizes X-Forwarded headers for proxy environments.
     * 
     * @return Base URL (e.g., "http://localhost:8080")
     */
    public static String getBaseUrl() {
        // Try from current request first (includes proxy headers)
        SessionInfo info = getCurrentSessionInfo();
        if (info.hasBaseUrl()) {
            return info.baseUrl;
        }

        // Fallback to MGE parameter
        try {
            Class<?> paramClass = Class.forName(CLASS_MGE_CORE_PARAMETER);
            Object urlParam = paramClass.getMethod("getParameter", String.class)
                    .invoke(null, PROP_URL_SANKHYAW);

            if (urlParam != null) {
                String url = String.valueOf(urlParam).trim();
                if (!url.isEmpty()) {
                    return normalizeBaseUrl(url);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to get URL from MGECoreParameter", e);
        }

        // Last resort: localhost
        return DEFAULT_BASE_URL;
    }

    /**
     * Publishes a file in the current session for the viewer.
     * Tries SystemCache first, then HttpSession as fallback.
     * 
     * @param content File content as byte array
     * @param fileName File name
     * @param contentType MIME type
     * @return File key for viewer, or null if failed
     */
    public static String publishFileToViewer(byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            return null;
        }

        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = "application/octet-stream";
        }

        try {
            // Create SessionFile object
            Class<?> sessionFileClass = Class.forName(CLASS_SESSION_FILE);
            Object sessionFile = sessionFileClass
                    .getMethod("createSessionFile", String.class, String.class, byte[].class)
                    .invoke(null, fileName != null ? fileName : "file_" + System.currentTimeMillis(),
                            contentType, content);

            // Generate unique key
            String key = generateUniqueKey();

            // Try SystemCache first (shared cache)
            if (publishToSystemCache(key, sessionFile)) {
                LOGGER.info(LOG_PREFIX + "File published to SystemCache with key=" + key);
                return key;
            }

            // Fallback to HttpSession (user session only)
            if (publishToHttpSession(key, sessionFile)) {
                LOGGER.info(LOG_PREFIX + "File published to HttpSession with key=" + key);
                return key;
            }

            LOGGER.warning(LOG_PREFIX + "Failed to publish file - both SystemCache and HttpSession failed");
            return null;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Error publishing file to viewer", e);
            return null;
        }
    }

    /**
     * Extracts HttpServletRequest from ServiceContext.
     */
    private static Object getHttpRequest(Object context, Class<?> scClass) {
        if (context == null) {
            return null;
        }

        try {
            // Try different method names used across Sankhya versions
            String[] methodNames = {"getHttpRequest", "getRequest", "getServletRequest"};

            for (String methodName : methodNames) {
                try {
                    Object request = scClass.getMethod(methodName).invoke(context);
                    if (request != null) {
                        return request;
                    }
                } catch (NoSuchMethodException e) {
                    // Try next method name
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to get HTTP request", e);
        }

        return null;
    }

    /**
     * Extracts Cookie header from HttpServletRequest.
     */
    private static String extractCookieHeader(Object request) {
        if (request == null) {
            return null;
        }

        try {
            Object cookieObj = request.getClass()
                    .getMethod("getHeader", String.class)
                    .invoke(request, HEADER_COOKIE);

            return cookieObj != null ? String.valueOf(cookieObj) : null;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to extract cookie header", e);
            return null;
        }
    }

    /**
     * Extracts JSESSIONID from cookie header.
     */
    private static String extractJSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }

        int pos = cookieHeader.indexOf(COOKIE_JSESSIONID + "=");
        if (pos >= 0) {
            int start = pos + COOKIE_JSESSIONID.length() + 1;
            int end = cookieHeader.indexOf(';', start);
            String sessionId = (end > start) ? cookieHeader.substring(start, end) : cookieHeader.substring(start);
            return sessionId.trim();
        }

        return null;
    }

    /**
     * Extracts base URL from request with proxy header support.
     * Prioritizes X-Forwarded-* headers for reverse proxy environments.
     */
    private static String extractBaseUrl(Object request) {
        if (request == null) {
            return null;
        }

        try {
            Class<?> requestClass = request.getClass();

            // Try X-Forwarded headers first (for proxy environments)
            String xfProto = getHeader(request, requestClass, HEADER_X_FORWARDED_PROTO);
            String xfHost = getHeader(request, requestClass, HEADER_X_FORWARDED_HOST);
            String xfPort = getHeader(request, requestClass, HEADER_X_FORWARDED_PORT);

            if (xfHost != null && !xfHost.isEmpty()) {
                // Handle multiple hosts (take first)
                String host = xfHost.trim();
                int comma = host.indexOf(',');
                if (comma > 0) {
                    host = host.substring(0, comma).trim();
                }

                // Add port if not in host and port is specified
                if (host.indexOf(':') < 0 && xfPort != null && !xfPort.isEmpty()) {
                    host = host + ":" + xfPort.trim();
                }

                String scheme = (xfProto != null && !xfProto.isEmpty()) ? xfProto.trim() : "http";
                return normalizeBaseUrl(scheme + "://" + host);
            }

            // Fallback to direct request properties
            String scheme = String.valueOf(requestClass.getMethod("getScheme").invoke(request));
            String serverName = String.valueOf(requestClass.getMethod("getServerName").invoke(request));
            int port = Integer.parseInt(String.valueOf(requestClass.getMethod("getServerPort").invoke(request)));

            String portPart = (port > 0 && port != 80 && port != 443) ? ":" + port : "";
            return normalizeBaseUrl(scheme + "://" + serverName + portPart);

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to extract base URL", e);
            return null;
        }
    }

    /**
     * Gets a header value from request, handling nulls.
     */
    private static String getHeader(Object request, Class<?> requestClass, String headerName) {
        try {
            Object value = requestClass.getMethod("getHeader", String.class).invoke(request, headerName);
            String strValue = String.valueOf(value);
            return "null".equals(strValue) ? null : strValue;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalizes base URL by removing trailing slashes.
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    /**
     * Generates a unique key for file storage.
     */
    private static String generateUniqueKey() {
        try {
            Class<?> uidGenClass = Class.forName(CLASS_UID_GENERATOR);
            Object id = uidGenClass.getMethod("getNextID").invoke(null);
            return FILE_PREFIX_BOLETO + String.valueOf(id);
        } catch (Exception e) {
            return FILE_PREFIX_BOLETO + System.currentTimeMillis();
        }
    }

    /**
     * Gera um UID simples para ser usado em globalID de chamadas HTTP.
     */
    public static String generateUid() {
        try {
            Class<?> uidGenClass = Class.forName(CLASS_UID_GENERATOR);
            Object id = uidGenClass.getMethod("getNextID").invoke(null);
            return String.valueOf(id);
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * Publishes file to SystemCache (shared across sessions).
     */
    private static boolean publishToSystemCache(String key, Object sessionFile) {
        try {
            Class<?> systemCacheClass = Class.forName(CLASS_SYSTEM_CACHE);
            Object cache = systemCacheClass.getMethod("getCache", String.class)
                    .invoke(null, CACHE_VISUALIZADOR_ARQUIVOS);

            cache.getClass().getMethod("put", Object.class, Object.class)
                    .invoke(cache, key, sessionFile);

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to publish to SystemCache", e);
            return false;
        }
    }

    /**
     * Publishes file to HttpSession (current user session only).
     */
    private static boolean publishToHttpSession(String key, Object sessionFile) {
        try {
            Class<?> scClass = Class.forName(CLASS_SERVICE_CONTEXT);
            Object context = scClass.getMethod("getCurrent").invoke(null);

            if (context == null) {
                return false;
            }

            // Find putHttpSessionAttribute method
            for (java.lang.reflect.Method m : scClass.getMethods()) {
                if ("putHttpSessionAttribute".equals(m.getName())) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (paramTypes.length == 2 && String.class.equals(paramTypes[0])) {
                        m.invoke(context, key, sessionFile);
                        return true;
                    }
                }
            }

            return false;

        } catch (Exception e) {
            LOGGER.log(Level.FINE, LOG_PREFIX + "Failed to publish to HttpSession", e);
            return false;
        }
    }

    /**
     * Builds complete cookie header with JSESSIONID and node routing.
     * 
     * @param token Session token (used as JSESSIONID base)
     * @param existingCookies Optional existing cookies to include
     * @return Complete cookie header string
     */
    public static String buildCookieHeader(String token, String existingCookies) {
        if (token == null || token.isEmpty()) {
            return existingCookies;
        }

        String jsessionId = token;

        // Add node routing if missing and available
        if (jsessionId.indexOf('.') < 0) {
            try {
                String nodeName = System.getProperty(PROP_JBOSS_NODE_NAME);
                if (nodeName != null && !nodeName.isEmpty()) {
                    jsessionId = jsessionId + "." + nodeName;
                }
            } catch (Exception e) {
                // Ignore - node routing is optional
            }
        }

        String sessionCookie = COOKIE_JSESSIONID + "=" + jsessionId;

        // Merge with existing cookies if provided
        if (existingCookies != null && !existingCookies.isEmpty()) {
            // Check if JSESSIONID already exists in existing cookies
            if (existingCookies.contains(COOKIE_JSESSIONID + "=")) {
                return existingCookies; // Use existing as-is
            } else {
                return sessionCookie + "; " + existingCookies;
            }
        }

        return sessionCookie;
    }
}
