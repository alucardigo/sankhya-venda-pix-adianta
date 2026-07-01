package br.com.bellube.sankhya.eventos.VendaPixAdianta.service;

import java.math.BigDecimal;

/**
 * DTO imutavel com o resultado da geracao automatica de boleto.
 */
public final class BoletoResult {

    private final boolean success;
    private final BigDecimal nufin;
    private final String nossoNumero;
    private final byte[] pdfBytes;
    private final String errorMessage;

    private BoletoResult(boolean success, BigDecimal nufin, String nossoNumero, byte[] pdfBytes, String errorMessage) {
        this.success = success;
        this.nufin = nufin;
        this.nossoNumero = nossoNumero;
        this.pdfBytes = pdfBytes;
        this.errorMessage = errorMessage;
    }

    public static BoletoResult success(BigDecimal nufin, String nossoNumero, byte[] pdfBytes) {
        return new BoletoResult(true, nufin, nossoNumero, pdfBytes, null);
    }

    public static BoletoResult failure(String errorMessage) {
        return new BoletoResult(false, null, null, null, errorMessage);
    }

    public static BoletoResult failure(BigDecimal nufin, String errorMessage) {
        return new BoletoResult(false, nufin, null, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public BigDecimal getNufin() { return nufin; }
    public String getNossoNumero() { return nossoNumero; }
    public byte[] getPdfBytes() { return pdfBytes; }
    public String getErrorMessage() { return errorMessage; }
    public boolean hasPdf() { return pdfBytes != null && pdfBytes.length > 0; }

    @Override
    public String toString() {
        return "BoletoResult{success=" + success +
                ", nufin=" + nufin +
                ", nossoNumero=" + nossoNumero +
                ", pdfSize=" + (pdfBytes != null ? pdfBytes.length : 0) +
                (errorMessage != null ? ", error=" + errorMessage : "") +
                '}';
    }
}
