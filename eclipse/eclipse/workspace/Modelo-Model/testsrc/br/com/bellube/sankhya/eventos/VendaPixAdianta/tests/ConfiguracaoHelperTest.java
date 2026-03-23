package br.com.bellube.sankhya.eventos.VendaPixAdianta.tests;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;

import java.math.BigDecimal;
import java.util.*;

public class ConfiguracaoHelperTest {

    public static void main(String[] args) {
        Map<String, BigDecimal> row1 = new HashMap<>();
        row1.put("SEQ", new BigDecimal("1"));
        row1.put("CODTIPOPER", new BigDecimal("51"));
        row1.put("CODTIPTIT", new BigDecimal("4"));
        row1.put("CODCTABCOINT", new BigDecimal("1001"));
        row1.put("CODNAT", new BigDecimal("1030101"));
        row1.put("CODCENCUS", new BigDecimal("20200"));
        row1.put("CODPROJ", new BigDecimal("0"));

        Map<String, BigDecimal> row2 = new HashMap<>();
        row2.put("SEQ", new BigDecimal("2"));
        row2.put("CODTIPOPER", new BigDecimal("51"));
        row2.put("CODTIPTIT", new BigDecimal("4"));
        row2.put("CODCTABCOINT", new BigDecimal("1002"));
        row2.put("CODNAT", new BigDecimal("1030101"));
        row2.put("CODCENCUS", new BigDecimal("30300"));
        row2.put("CODPROJ", new BigDecimal("0"));

        ConfiguracaoHelper.clearCache();
        ConfiguracaoHelper.setTestConfiguracoes(new BigDecimal("1"), Arrays.asList(row1, row2));
        Map<BigDecimal, BigDecimal> contaEmp = new HashMap<>();
        contaEmp.put(new BigDecimal("1001"), new BigDecimal("1"));
        contaEmp.put(new BigDecimal("1002"), new BigDecimal("1"));
        ConfiguracaoHelper.setTestContaEmpresaMap(contaEmp);

        BigDecimal codEmp = new BigDecimal("1");

        BigDecimal codCta = ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(codEmp);
        if (!new BigDecimal("1001").equals(codCta)) throw new RuntimeException("Seleção de configuração falhou: esperado CODCTA=1001, obtido=" + codCta);

        BigDecimal tipOper = ConfiguracaoHelper.getCodTopAdiantamento(codEmp);
        if (!new BigDecimal("51").equals(tipOper)) throw new RuntimeException("CODTIPOPER incorreto: " + tipOper);

        boolean erroOk = false;
        try {
            contaEmp.put(new BigDecimal("1001"), new BigDecimal("2"));
            ConfiguracaoHelper.setTestContaEmpresaMap(contaEmp);
            ConfiguracaoHelper.getCodCtaBcoIntAdiantamento(codEmp);
        } catch (Exception e) { erroOk = true; }
        if (!erroOk) throw new RuntimeException("Validação de vínculo empresa↔conta não disparou erro");

        System.out.println("OK: ConfiguracaoHelperTest");
    }
}
