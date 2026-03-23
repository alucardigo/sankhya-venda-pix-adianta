package br.com.bellube.sankhya.eventos.VendaPixAdianta.action;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Normalizer;

import static br.com.bellube.sankhya.eventos.VendaPixAdianta.action.BoletoConstants.*;

/**
 * Utility class for text manipulation operations.
 * Provides methods for sanitization, encoding, and text processing.
 * 
 * @author VendaPixAdianta Module
 * @version 5.0.0 - Professional Refactoring
 */
public final class TextUtils {

    private TextUtils() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    /**
     * Escapes special characters in a string for JSON.
     * Handles quotes, backslashes, and control characters.
     * 
     * @param s String to escape
     * @return Escaped string safe for JSON
     */
    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Sanitizes text to ASCII, removing accents and special characters.
     * Preserves line breaks and printable ASCII characters.
     * Useful for system messages that don't support UTF-8.
     * 
     * @param s String to sanitize
     * @return ASCII-safe string
     */
    public static String asciiSanitize(String s) {
        if (s == null) {
            return "";
        }

        try {
            // Normalize to NFD form and remove combining diacritical marks (accents)
            String normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

            // Replace smart quotes and other special characters
            normalized = normalized
                    .replace('\u201C', '"').replace('\u201D', '"')  // Smart double quotes
                    .replace('\u2018', '\'').replace('\u2019', '\'') // Smart single quotes
                    .replace('\u00A0', ' ')   // Non-breaking space
                    .replace('\u2022', '*')   // Bullet point
                    .replace('\u2013', '-').replace('\u2014', '-'); // En dash, Em dash

            // Keep only printable ASCII and line breaks
            StringBuilder sb = new StringBuilder(normalized.length());
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if (c == '\n' || c == '\r' || (c >= 32 && c <= 126)) {
                    sb.append(c);
                }
            }

            return sb.toString();

        } catch (Throwable t) {
            // Fallback: return original string if normalization fails
            return s;
        }
    }

    /**
     * Returns a safe text sample for logging.
     * Truncates to specified length and replaces line breaks with spaces.
     * 
     * @param s String to sample
     * @param maxLength Maximum length of sample
     * @return Truncated, single-line sample
     */
    public static String sampleText(String s, int maxLength) {
        if (s == null) {
            return "<null>";
        }

        String sample = s.replaceAll("\r|\n", " ");
        if (sample.length() > maxLength) {
            return sample.substring(0, maxLength) + "...";
        }

        return sample;
    }

    /**
     * Returns a safe text sample from byte array for logging.
     * Attempts UTF-8 decoding and truncates to specified length.
     * 
     * @param data Byte array to sample
     * @param maxLength Maximum length of sample
     * @return Truncated text sample
     */
    public static String sampleBytes(byte[] data, int maxLength) {
        try {
            if (data == null) {
                return "<null>";
            }

            int len = Math.min(data.length, maxLength);
            String text = new String(data, 0, len, CHARSET_UTF8);
            return text.replaceAll("\r|\n", " ");

        } catch (Throwable t) {
            return "<sample unavailable>";
        }
    }

    /**
     * Checks if byte array starts with PDF signature.
     * 
     * @param data Byte array to check
     * @return true if data appears to be a PDF
     */
    public static boolean isPdf(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }

        try {
            String header = new String(data, 0, 4, CHARSET_UTF8);
            return PDF_SIGNATURE.equals(header);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads all bytes from an InputStream.
     * 
     * @param is InputStream to read
     * @return Byte array with all data
     * @throws Exception if read fails
     */
    public static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }

        return baos.toByteArray();
    }

    /**
     * Extracts file key from JSON response.
     * Handles both XML-like and JSON formats.
     * 
     * @param response Response string (JSON or XML)
     * @return File key if found, null otherwise
     */
    public static String extractFileKey(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String resp = response.trim();

        // Try XML format first: <valor>key</valor>
        int start = resp.indexOf("<valor>");
        if (start >= 0) {
            start += 7;
            int end = resp.indexOf("</valor>", start);
            if (end > start) {
                return resp.substring(start, end).trim();
            }
        }

        // Try JSON format: "chaveArquivo":"key"
        start = resp.indexOf("\"chaveArquivo\"");
        if (start >= 0) {
            start = resp.indexOf("\"", start + 14);
            if (start >= 0) {
                start++;
                int end = resp.indexOf("\"", start);
                if (end > start) {
                    return resp.substring(start, end).trim();
                }
            }
        }

        // Try alternative JSON format: "chaveArquivo":{"$":"key"}
        start = resp.indexOf("\"chaveArquivo\"");
        if (start >= 0) {
            start = resp.indexOf("\"$\"", start);
            if (start >= 0) {
                start = resp.indexOf("\"", start + 3);
                if (start >= 0) {
                    start++;
                    int end = resp.indexOf("\"", start);
                    if (end > start) {
                        return resp.substring(start, end).trim();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Parses JSESSIONID from response body.
     * Looks for jsessionid field in JSON response.
     * 
     * @param response JSON response
     * @return JSESSIONID if found, null otherwise
     */
    public static String extractJSessionId(String response) {
        if (response == null) {
            return null;
        }

        int pos = response.indexOf("\"jsessionid\"");
        if (pos >= 0) {
            int keyPos = response.indexOf("\"$\"", pos);
            if (keyPos >= 0) {
                int q1 = response.indexOf("\"", keyPos + 3);
                int q2 = (q1 >= 0) ? response.indexOf("\"", q1 + 1) : -1;
                if (q1 >= 0 && q2 > q1) {
                    return response.substring(q1 + 1, q2);
                }
            }
        }

        return null;
    }
}
