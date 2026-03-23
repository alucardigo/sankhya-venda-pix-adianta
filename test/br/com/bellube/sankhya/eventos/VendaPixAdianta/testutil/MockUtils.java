package br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import br.com.sankhya.jape.event.PersistenceEvent;
import br.com.sankhya.jape.event.TransactionContext;
import br.com.sankhya.modelcore.MGEModelException;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;

import static org.mockito.Mockito.*;

/**
 * Utilitários para criar mocks usando Mockito para testes.
 * Cria mocks apropriados das interfaces do Sankhya.
 */
public class MockUtils {
    
    /**
     * Cria um mock de DynamicVO usando Mockito
     */
    public static DynamicVO createMockDynamicVO(Map<String, Object> properties) {
        DynamicVO mockVO = mock(DynamicVO.class);
        
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                // Mock para diferentes tipos de retorno
                when(mockVO.getProperty(key)).thenReturn(value);
                when(mockVO.asString(key)).thenReturn(value != null ? value.toString() : null);
                
                if (value instanceof BigDecimal) {
                    when(mockVO.asBigDecimal(key)).thenReturn((BigDecimal) value);
                } else if (value instanceof Number) {
                    when(mockVO.asBigDecimal(key)).thenReturn(new BigDecimal(value.toString()));
                } else if (value instanceof String && value != null) {
                    try {
                        when(mockVO.asBigDecimal(key)).thenReturn(new BigDecimal((String) value));
                    } catch (NumberFormatException e) {
                        when(mockVO.asBigDecimal(key)).thenReturn(null);
                    }
                } else {
                    when(mockVO.asBigDecimal(key)).thenReturn(null);
                }
                
                if (value instanceof Timestamp) {
                    when(mockVO.asTimestamp(key)).thenReturn((Timestamp) value);
                } else {
                    when(mockVO.asTimestamp(key)).thenReturn(null);
                }
            }
        }
        
        return mockVO;
    }

    /**
     * Cria um mock de EntityVO usando Mockito com métodos essenciais
     */
    public static Object createMockEntity(String entityName, DynamicVO vo) {
        // Criar um mock genérico que responde aos métodos básicos necessários
        Object mockEntity = mock(Object.class);
        // Não tentamos mockar métodos específicos - deixamos para o teste real
        return mockEntity;
    }

    /**
     * Cria um mock de TransactionContext usando Mockito
     */
    public static TransactionContext createMockTransactionContext() {
        TransactionContext mockContext = mock(TransactionContext.class);
        // Não mockamos métodos específicos - deixamos para o teste real
        return mockContext;
    }
    
    /**
     * Cria um mock de PersistenceEvent usando Mockito (versão simplificada)
     */
    public static PersistenceEvent createPersistenceEvent(String entityName, MockDynamicVO mockVO) {
        // Converter MockDynamicVO para DynamicVO real mockado
        Map<String, Object> properties = mockVO != null ? mockVO.getAllProperties() : new HashMap<>();
        DynamicVO realMockVO = createMockDynamicVO(properties);
        
        // Criar mocks dos componentes
        Object mockEntity = createMockEntity(entityName, realMockVO);
        TransactionContext mockContext = createMockTransactionContext();
        
        // Criar o mock do PersistenceEvent - versão simplificada
        PersistenceEvent mockEvent = mock(PersistenceEvent.class);
        
        // Configurar apenas o que conseguimos mockar sem problemas de assinatura
        try {
            when(mockEvent.getTransactionContext()).thenReturn(mockContext);
        } catch (Exception e) {
            // Ignorar erros de mocking - o importante é criar o objeto
        }
        
        return mockEvent;
    }
    
    /**
     * Cria um mock específico para CabecalhoNota
     */
    public static PersistenceEvent createCabecalhoNotaEvent(MockDynamicVO cabecalhoVO) {
        return createPersistenceEvent("CabecalhoNota", cabecalhoVO);
    }
    
    /**
     * Cria um mock de evento para entidade diferente de CabecalhoNota
     */
    public static PersistenceEvent createNonCabecalhoEvent() {
        MockDynamicVO vo = new MockDynamicVO();
        return createPersistenceEvent("OutraEntidade", vo);
    }
    
    /**
     * Cria um MockDynamicVO com dados de teste padrão para CabecalhoNota
     */
    public static MockDynamicVO createCabecalhoNotaVO() {
        MockDynamicVO vo = new MockDynamicVO();
        vo.setProperty("NUNOTA", new BigDecimal("123456"));
        vo.setProperty("CODPARC", new BigDecimal("37228"));
        vo.setProperty("VLRNOTA", new BigDecimal("1500.00"));
        vo.setProperty("DTNEG", new Timestamp(System.currentTimeMillis()));
        vo.setProperty("CODEMP", new BigDecimal("10"));
        vo.setProperty("CODCENCUS", new BigDecimal("20200"));
        vo.setProperty("CODTIPVENDA", new BigDecimal("290"));
        return vo;
    }
    
    /**
     * Cria um MockDynamicVO com dados específicos
     */
    public static MockDynamicVO createMockVO(Map<String, Object> properties) {
        MockDynamicVO vo = new MockDynamicVO();
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                vo.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return vo;
    }
}