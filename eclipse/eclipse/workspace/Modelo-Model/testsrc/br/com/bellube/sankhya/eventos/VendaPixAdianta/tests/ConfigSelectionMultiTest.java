package br.com.bellube.sankhya.eventos.VendaPixAdianta.tests;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

public class ConfigSelectionMultiTest {

    @Test
    public void shouldSelectByCompanyAndValidateAccountOwnership() {
        Map<String, BigDecimal> r1 = new HashMap<>();
        r1.put("SEQ", new BigDecimal("1"));
        r1.put("CODTIPOPER", new BigDecimal("51"));
        r1.put("CODTIPTIT", new BigDecimal("4"));
        r1.put("CODCTABCOINT", new BigDecimal("3001"));
        r1.put("CODNAT", new BigDecimal("1030101"));

        ConfiguracaoHelper.clearCache();
        ConfiguracaoHelper.setTestConfiguracoes(new BigDecimal("7"), Collections.singletonList(r1));
        Map<BigDecimal, BigDecimal> contaEmp = new HashMap<>();
        contaEmp.put(new BigDecimal("3001"), new BigDecimal("7"));
        ConfiguracaoHelper.setTestContaEmpresaMap(contaEmp);

        Assertions.assertEquals(new BigDecimal("3001"), ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(new BigDecimal("7")));
        Assertions.assertEquals(new BigDecimal("51"), ConfiguracaoHelper.getCodTopAdiantamento(new BigDecimal("7")));
    }

    @Test
    public void shouldFailWithAccountFromOtherCompany() {
        Map<String, BigDecimal> r1 = new HashMap<>();
        r1.put("SEQ", new BigDecimal("1"));
        r1.put("CODTIPOPER", new BigDecimal("51"));
        r1.put("CODTIPTIT", new BigDecimal("4"));
        r1.put("CODCTABCOINT", new BigDecimal("4001"));
        r1.put("CODNAT", new BigDecimal("1030101"));

        ConfiguracaoHelper.clearCache();
        ConfiguracaoHelper.setTestConfiguracoes(new BigDecimal("8"), Collections.singletonList(r1));
        Map<BigDecimal, BigDecimal> contaEmp = new HashMap<>();
        contaEmp.put(new BigDecimal("4001"), new BigDecimal("9"));
        ConfiguracaoHelper.setTestContaEmpresaMap(contaEmp);

        Assertions.assertThrows(IllegalStateException.class, () -> ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(new BigDecimal("8")));
    }
}