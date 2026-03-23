package br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Mock implementation that simulates DynamicVO behavior for testing purposes.
 * 
 * Simula o comportamento básico de DynamicVO usado pelos componentes
 * do módulo VendaPixAdianta. Permite criar objetos de teste sem
 * dependência do framework JAPE.
 */
public class MockDynamicVO {
    
    private final Map<String, Object> properties;
    
    /**
     * Construtor que aceita um map de propriedades
     * @param properties Propriedades a serem simuladas
     */
    public MockDynamicVO(Map<String, Object> properties) {
        this.properties = new HashMap<>(properties);
    }
    
    /**
     * Construtor vazio
     */
    public MockDynamicVO() {
        this.properties = new HashMap<>();
    }
    
    public BigDecimal asBigDecimal(String property) {
        Object value = properties.get(property);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public String asString(String property) {
        Object value = properties.get(property);
        return value != null ? value.toString() : null;
    }
    
    public Timestamp asTimestamp(String property) {
        Object value = properties.get(property);
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        return null;
    }
    
    public Object getProperty(String property) {
        return properties.get(property);
    }
    
    public void setProperty(String property, Object value) {
        properties.put(property, value);
    }
    
    /**
     * Método de conveniência para adicionar propriedades
     */
    public MockDynamicVO withProperty(String property, Object value) {
        properties.put(property, value);
        return this;
    }
    
    /**
     * Obtém todas as propriedades (para debug/teste)
     */
    public Map<String, Object> getAllProperties() {
        return new HashMap<>(properties);
    }
    
    public String toString() {
        return "MockDynamicVO{" + properties + '}';
    }
}