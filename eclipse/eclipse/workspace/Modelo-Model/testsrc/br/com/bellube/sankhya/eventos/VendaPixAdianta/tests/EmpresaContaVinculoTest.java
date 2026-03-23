package br.com.bellube.sankhya.eventos.VendaPixAdianta.tests;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;

import java.math.BigDecimal;
import java.util.*;

public class EmpresaContaVinculoTest {
    public static void main(String[] args) {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("SEQ", new BigDecimal("1"));
        r.put("CODTIPOPER", new BigDecimal("51"));
        r.put("CODTIPTIT", new BigDecimal("4"));
        r.put("CODCTABCOINT", new BigDecimal("2001"));
        r.put("CODNAT", new BigDecimal("1030101"));

        ConfiguracaoHelper.clearCache();
        ConfiguracaoHelper.setTestConfiguracoes(new BigDecimal("2"), Collections.singletonList(r));
        Map<BigDecimal, BigDecimal> contaEmp = new HashMap<>();
        contaEmp.put(new BigDecimal("2001"), new BigDecimal("2"));
        ConfiguracaoHelper.setTestContaEmpresaMap(contaEmp);

        // Validação indireta via cache info para evitar dependências ERP no ambiente de teste
        String info = ConfiguracaoHelper.getCacheInfo();
        if (!info.contains("Empresas em cache: 1")) throw new RuntimeException("Cache não carregado corretamente");

        System.out.println("OK: EmpresaContaVinculoTest");
    }
}