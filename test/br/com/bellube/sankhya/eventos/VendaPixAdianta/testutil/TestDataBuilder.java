package br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.sankhya.jape.vo.DynamicVO;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Builder para criar objetos de teste para o módulo VendaPixAdianta.
 * 
 * Fornece métodos fluentes para criar dados de teste consistentes
 * e válidos para todos os cenários de teste, incluindo casos de
 * sucesso, falha e casos extremos.
 */
public class TestDataBuilder {
    
    // Valores padrão para testes
    public static final BigDecimal DEFAULT_NUNOTA = new BigDecimal("123456");
    public static final BigDecimal DEFAULT_CODPARC = new BigDecimal("1001");
    public static final BigDecimal DEFAULT_VLRNOTA = new BigDecimal("1500.00");
    public static final BigDecimal DEFAULT_CODEMP = new BigDecimal("1");
    public static final BigDecimal DEFAULT_CODCENCUS = new BigDecimal("101");
    public static final BigDecimal DEFAULT_CODTIPTIT_PIX = new BigDecimal("999");
    public static final BigDecimal DEFAULT_CODTIPTIT_NORMAL = new BigDecimal("1");
    
    /**
     * Builder para AdiantamentoTask
     */
    public static class AdiantamentoTaskBuilder {
        private BigDecimal nunota = DEFAULT_NUNOTA;
        private BigDecimal codparc = DEFAULT_CODPARC;
        private BigDecimal vlrnota = DEFAULT_VLRNOTA;
        private Timestamp dtneg = new Timestamp(System.currentTimeMillis());
        private BigDecimal codemp = DEFAULT_CODEMP;
        private BigDecimal codcencus = DEFAULT_CODCENCUS;
        
        public AdiantamentoTaskBuilder nunota(BigDecimal nunota) {
            this.nunota = nunota;
            return this;
        }
        
        public AdiantamentoTaskBuilder codparc(BigDecimal codparc) {
            this.codparc = codparc;
            return this;
        }
        
        public AdiantamentoTaskBuilder vlrnota(BigDecimal vlrnota) {
            this.vlrnota = vlrnota;
            return this;
        }
        
        public AdiantamentoTaskBuilder dtneg(Timestamp dtneg) {
            this.dtneg = dtneg;
            return this;
        }
        
        public AdiantamentoTaskBuilder codemp(BigDecimal codemp) {
            this.codemp = codemp;
            return this;
        }
        
        public AdiantamentoTaskBuilder codcencus(BigDecimal codcencus) {
            this.codcencus = codcencus;
            return this;
        }
        
        public AdiantamentoTask build() {
            return new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
        }
    }
    
    /**
     * Builder para DynamicVO simulando TGFCAB
     */
    public static class CabecalhoNotaBuilder {
        private final Map<String, Object> properties = new HashMap<>();
        
        public CabecalhoNotaBuilder() {
            // Valores padrão
            properties.put("NUNOTA", DEFAULT_NUNOTA);
            properties.put("CODPARC", DEFAULT_CODPARC);
            properties.put("VLRNOTA", DEFAULT_VLRNOTA);
            properties.put("DTNEG", new Timestamp(System.currentTimeMillis()));
            properties.put("CODEMP", DEFAULT_CODEMP);
            properties.put("CODCENCUS", DEFAULT_CODCENCUS);
            properties.put("CODTIPTIT", DEFAULT_CODTIPTIT_NORMAL);
        }
        
        public CabecalhoNotaBuilder nunota(BigDecimal nunota) {
            properties.put("NUNOTA", nunota);
            return this;
        }
        
        public CabecalhoNotaBuilder codparc(BigDecimal codparc) {
            properties.put("CODPARC", codparc);
            return this;
        }
        
        public CabecalhoNotaBuilder vlrnota(BigDecimal vlrnota) {
            properties.put("VLRNOTA", vlrnota);
            return this;
        }
        
        public CabecalhoNotaBuilder dtneg(Timestamp dtneg) {
            properties.put("DTNEG", dtneg);
            return this;
        }
        
        public CabecalhoNotaBuilder codemp(BigDecimal codemp) {
            properties.put("CODEMP", codemp);
            return this;
        }
        
        public CabecalhoNotaBuilder codcencus(BigDecimal codcencus) {
            properties.put("CODCENCUS", codcencus);
            return this;
        }
        
        public CabecalhoNotaBuilder codtiptit(BigDecimal codtiptit) {
            properties.put("CODTIPTIT", codtiptit);
            return this;
        }
        
        public CabecalhoNotaBuilder comTipoPixPadrao() {
            properties.put("CODTIPTIT", DEFAULT_CODTIPTIT_PIX);
            return this;
        }
        
        public MockDynamicVO build() {
            return new MockDynamicVO(properties);
        }
    }
    
    /**
     * Cria uma nova instância do builder para AdiantamentoTask
     */
    public static AdiantamentoTaskBuilder adiantamentoTask() {
        return new AdiantamentoTaskBuilder();
    }
    
    /**
     * Cria uma nova instância do builder para CabecalhoNota (DynamicVO)
     */
    public static CabecalhoNotaBuilder cabecalhoNota() {
        return new CabecalhoNotaBuilder();
    }
    
    /**
     * Cria um AdiantamentoTask válido com valores padrão
     */
    public static AdiantamentoTask validAdiantamentoTask() {
        return adiantamentoTask().build();
    }
    
    /**
     * Cria um CabecalhoNota PIX válido com valores padrão
     */
    public static MockDynamicVO validPixCabecalho() {
        return cabecalhoNota().comTipoPixPadrao().build();
    }
    
    /**
     * Cria um CabecalhoNota não-PIX válido com valores padrão
     */
    public static MockDynamicVO validNonPixCabecalho() {
        return cabecalhoNota().build(); // Usa DEFAULT_CODTIPTIT_NORMAL
    }
    
    /**
     * Cenários de teste específicos
     */
    public static class Scenarios {
        
        /**
         * Cenário: Venda PIX com valor alto (testando limites)
         */
        public static AdiantamentoTask highValuePixSale() {
            return adiantamentoTask()
                .vlrnota(new BigDecimal("50000.00"))
                .build();
        }
        
        /**
         * Cenário: Venda PIX com valor muito baixo
         */
        public static AdiantamentoTask lowValuePixSale() {
            return adiantamentoTask()
                .vlrnota(new BigDecimal("0.01"))
                .build();
        }
        
        /**
         * Cenário: Venda PIX sem centro de custo
         */
        public static AdiantamentoTask pixSaleWithoutCentroCusto() {
            return adiantamentoTask()
                .codcencus(null)
                .build();
        }
        
        /**
         * Cenário: Venda PIX de empresa diferente
         */
        public static AdiantamentoTask pixSaleFromDifferentCompany() {
            return adiantamentoTask()
                .codemp(new BigDecimal("2"))
                .build();
        }
        
        /**
         * Cenário: Parceiro inexistente (para teste de erro)
         */
        public static AdiantamentoTask pixSaleWithInvalidPartner() {
            return adiantamentoTask()
                .codparc(new BigDecimal("999999"))
                .build();
        }
    }
}