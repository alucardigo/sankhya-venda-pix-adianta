package br.com.bellube.sankhya.eventos.VendaPixAdianta.async;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * DTO imutável que encapsula os dados da venda PIX necessária para gerar o adiantamento.
 */
public final class AdiantamentoTask {

    private final BigDecimal nunota;
    private final BigDecimal codparc;
    private final BigDecimal vlrnota;
    private final Timestamp dtneg;
    private final BigDecimal codemp;
    private final BigDecimal codcencus;
    private final Object authInfo;

    public AdiantamentoTask(BigDecimal nunota, BigDecimal codparc, BigDecimal vlrnota, Timestamp dtneg,
                            BigDecimal codemp, BigDecimal codcencus) {
        this.nunota = nunota;
        this.codparc = codparc;
        this.vlrnota = vlrnota;
        this.dtneg = dtneg;
        this.codemp = codemp;
        this.codcencus = codcencus;
        this.authInfo = null;
    }

    public AdiantamentoTask(BigDecimal nunota, BigDecimal codparc, BigDecimal vlrnota, Timestamp dtneg,
                            BigDecimal codemp, BigDecimal codcencus, Object authInfo) {
        this.nunota = nunota;
        this.codparc = codparc;
        this.vlrnota = vlrnota;
        this.dtneg = dtneg;
        this.codemp = codemp;
        this.codcencus = codcencus;
        this.authInfo = authInfo;
    }

    public BigDecimal nunota() {
        return nunota;
    }

    public BigDecimal codparc() {
        return codparc;
    }

    public BigDecimal vlrnota() {
        return vlrnota;
    }

    public Timestamp dtneg() {
        return dtneg;
    }

    public BigDecimal codemp() {
        return codemp;
    }

    public BigDecimal codcencus() {
        return codcencus;
    }

    // Getters tradicionais para compatibilidade com testes

    public BigDecimal getNunota() {
        return nunota;
    }

    public BigDecimal getCodparc() {
        return codparc;
    }

    public BigDecimal getVlrnota() {
        return vlrnota;
    }

    public Timestamp getDtneg() {
        return dtneg;
    }

    public BigDecimal getCodemp() {
        return codemp;
    }

    public BigDecimal getCodcencus() {
        return codcencus;
    }

    public Object authInfo() {
        return authInfo;
    }

    public Object getAuthInfo() {
        return authInfo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AdiantamentoTask that = (AdiantamentoTask) obj;
        return Objects.equals(nunota, that.nunota) &&
               Objects.equals(codparc, that.codparc) &&
               Objects.equals(vlrnota, that.vlrnota) &&
               Objects.equals(dtneg, that.dtneg) &&
               Objects.equals(codemp, that.codemp) &&
               Objects.equals(codcencus, that.codcencus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
    }

    @Override
    public String toString() {
        return "AdiantamentoTask{" +
                "nunota=" + nunota +
                ", codparc=" + codparc +
                ", vlrnota=" + vlrnota +
                ", dtneg=" + dtneg +
                ", codemp=" + codemp +
                ", codcencus=" + codcencus +
                '}';
    }
}
