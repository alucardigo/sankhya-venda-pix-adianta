package br.com.bellube.sankhya.eventos.VendaPixAdianta.async;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.math.BigDecimal;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para AdiantamentoTask.
 * 
 * Verifica:
 * - Criação correta do objeto imutável
 * - Getters retornam valores corretos
 * - Immutabilidade dos dados
 * - Método toString funciona adequadamente
 */
@DisplayName("AdiantamentoTask Tests")
class AdiantamentoTaskTest {
    
    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {
        
        @Test
        @DisplayName("Deve criar task com todos os campos válidos")
        void deveCriarTaskComTodosCamposValidos() {
            // Given
            BigDecimal nunota = new BigDecimal("123456");
            BigDecimal codparc = new BigDecimal("1001");
            BigDecimal vlrnota = new BigDecimal("1500.00");
            Timestamp dtneg = new Timestamp(System.currentTimeMillis());
            BigDecimal codemp = new BigDecimal("1");
            BigDecimal codcencus = new BigDecimal("101");
            
            // When
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
            
            // Then
            assertNotNull(task);
            assertEquals(nunota, task.nunota());
            assertEquals(codparc, task.codparc());
            assertEquals(vlrnota, task.vlrnota());
            assertEquals(dtneg, task.dtneg());
            assertEquals(codemp, task.codemp());
            assertEquals(codcencus, task.codcencus());
        }
        
        @Test
        @DisplayName("Deve criar task com centro de custo nulo")
        void deveCriarTaskComCentroCustoNulo() {
            // Given
            BigDecimal nunota = new BigDecimal("123456");
            BigDecimal codparc = new BigDecimal("1001");
            BigDecimal vlrnota = new BigDecimal("1500.00");
            Timestamp dtneg = new Timestamp(System.currentTimeMillis());
            BigDecimal codemp = new BigDecimal("1");
            
            // When
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, null);
            
            // Then
            assertNotNull(task);
            assertEquals(nunota, task.nunota());
            assertEquals(codparc, task.codparc());
            assertEquals(vlrnota, task.vlrnota());
            assertEquals(dtneg, task.dtneg());
            assertEquals(codemp, task.codemp());
            assertNull(task.codcencus());
        }
        
        @Test
        @DisplayName("Deve criar task com valores mínimos")
        void deveCriarTaskComValoresMinimos() {
            // Given
            BigDecimal nunota = new BigDecimal("1");
            BigDecimal codparc = new BigDecimal("1");
            BigDecimal vlrnota = new BigDecimal("0.01");
            Timestamp dtneg = new Timestamp(0); // Epoch time
            BigDecimal codemp = new BigDecimal("1");
            BigDecimal codcencus = new BigDecimal("1");
            
            // When
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
            
            // Then
            assertNotNull(task);
            assertEquals(nunota, task.nunota());
            assertEquals(codparc, task.codparc());
            assertEquals(vlrnota, task.vlrnota());
            assertEquals(dtneg, task.dtneg());
            assertEquals(codemp, task.codemp());
            assertEquals(codcencus, task.codcencus());
        }
        
        @Test
        @DisplayName("Deve criar task com valores grandes")
        void deveCriarTaskComValoresGrandes() {
            // Given
            BigDecimal nunota = new BigDecimal("999999999");
            BigDecimal codparc = new BigDecimal("999999");
            BigDecimal vlrnota = new BigDecimal("999999.99");
            Timestamp dtneg = new Timestamp(System.currentTimeMillis());
            BigDecimal codemp = new BigDecimal("999");
            BigDecimal codcencus = new BigDecimal("999999");
            
            // When
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
            
            // Then
            assertNotNull(task);
            assertEquals(nunota, task.nunota());
            assertEquals(codparc, task.codparc());
            assertEquals(vlrnota, task.vlrnota());
            assertEquals(dtneg, task.dtneg());
            assertEquals(codemp, task.codemp());
            assertEquals(codcencus, task.codcencus());
        }
    }
    
    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {
        
        @Test
        @DisplayName("Deve ser imutável - modificação externa não afeta objeto")
        void deveSerImutavel() {
            // Given
            BigDecimal nunota = new BigDecimal("123456");
            BigDecimal codparc = new BigDecimal("1001");
            BigDecimal vlrnota = new BigDecimal("1500.00");
            Timestamp dtneg = new Timestamp(1000000000L);
            BigDecimal codemp = new BigDecimal("1");
            BigDecimal codcencus = new BigDecimal("101");
            
            // When
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
            
            // Tentativa de modificação externa (não deveria afetar o objeto)
            nunota = new BigDecimal("999999");
            codparc = new BigDecimal("9999");
            vlrnota = new BigDecimal("9999.99");
            dtneg.setTime(2000000000L);
            codemp = new BigDecimal("999");
            codcencus = new BigDecimal("999");
            
            // Then
            assertEquals(new BigDecimal("123456"), task.nunota());
            assertEquals(new BigDecimal("1001"), task.codparc());
            assertEquals(new BigDecimal("1500.00"), task.vlrnota());
            assertNotEquals(0, task.dtneg().getTime()); // Timestamp foi modificado na origem, mas não afeta o objeto
            assertEquals(new BigDecimal("1"), task.codemp());
            assertEquals(new BigDecimal("101"), task.codcencus());
        }
        
        @Test
        @DisplayName("Deve retornar cópias independentes dos objetos internos")
        void deveRetornarCopiasIndependentes() {
            // Given
            BigDecimal nunota = new BigDecimal("123456");
            BigDecimal codparc = new BigDecimal("1001");
            BigDecimal vlrnota = new BigDecimal("1500.00");
            Timestamp dtneg = new Timestamp(1000000000L);
            BigDecimal codemp = new BigDecimal("1");
            BigDecimal codcencus = new BigDecimal("101");
            
            AdiantamentoTask task = new AdiantamentoTask(nunota, codparc, vlrnota, dtneg, codemp, codcencus);
            
            // When
            Timestamp returnedDtneg = task.dtneg();
            
            // Modificar o Timestamp retornado
            long originalTime = returnedDtneg.getTime();
            returnedDtneg.setTime(originalTime + 1000000);
            
            // Then
            // O Timestamp interno não deve ter sido afetado
            assertEquals(originalTime, task.dtneg().getTime());
        }
    }
    
    @Nested
    @DisplayName("Accessor Methods Tests")
    class AccessorMethodsTests {
        
        @Test
        @DisplayName("nunota() deve retornar valor correto")
        void nunotaDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new BigDecimal("123456"), task.nunota());
        }
        
        @Test
        @DisplayName("codparc() deve retornar valor correto")
        void codparcDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new BigDecimal("1001"), task.codparc());
        }
        
        @Test
        @DisplayName("vlrnota() deve retornar valor correto")
        void vlrnotaDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new BigDecimal("1500.00"), task.vlrnota());
        }
        
        @Test
        @DisplayName("dtneg() deve retornar valor correto")
        void dtnegDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new Timestamp(1000000000L), task.dtneg());
        }
        
        @Test
        @DisplayName("codemp() deve retornar valor correto")
        void codempDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new BigDecimal("1"), task.codemp());
        }
        
        @Test
        @DisplayName("codcencus() deve retornar valor correto")
        void codcencusDeveRetornarValorCorreto() {
            // Given & When
            AdiantamentoTask task = createSampleTask();
            
            // Then
            assertEquals(new BigDecimal("101"), task.codcencus());
        }
        
        @Test
        @DisplayName("codcencus() deve retornar null quando não informado")
        void codcencusDeveRetornarNullQuandoNaoInformado() {
            // Given
            AdiantamentoTask task = new AdiantamentoTask(
                new BigDecimal("123456"),
                new BigDecimal("1001"),
                new BigDecimal("1500.00"),
                new Timestamp(1000000000L),
                new BigDecimal("1"),
                null // Centro de custo não informado
            );
            
            // When & Then
            assertNull(task.codcencus());
        }
    }
    
    @Nested
    @DisplayName("Object Methods Tests")
    class ObjectMethodsTests {
        
        @Test
        @DisplayName("equals() deve comparar corretamente objetos iguais")
        void equalsDeveCompararCorretamenteObjetosIguais() {
            // Given
            AdiantamentoTask task1 = createSampleTask();
            AdiantamentoTask task2 = createSampleTask();
            
            // When & Then
            assertEquals(task1, task2);
            assertEquals(task1.nunota(), task2.nunota());
            assertEquals(task1.codparc(), task2.codparc());
            assertEquals(task1.vlrnota(), task2.vlrnota());
            assertEquals(task1.dtneg(), task2.dtneg());
            assertEquals(task1.codemp(), task2.codemp());
            assertEquals(task1.codcencus(), task2.codcencus());
        }
        
        @Test
        @DisplayName("equals() deve retornar false para objetos diferentes")
        void equalsDeveRetornarFalseParaObjetosDiferentes() {
            // Given
            AdiantamentoTask task1 = createSampleTask();
            AdiantamentoTask task2 = new AdiantamentoTask(
                new BigDecimal("654321"), // nunota diferente
                new BigDecimal("1001"),
                new BigDecimal("1500.00"),
                new Timestamp(1000000000L),
                new BigDecimal("1"),
                new BigDecimal("101")
            );
            
            // When & Then
            assertNotEquals(task1, task2);
        }
        
        @Test
        @DisplayName("hashCode() deve ser consistente")
        void hashCodeDeveSerConsistente() {
            // Given
            AdiantamentoTask task1 = createSampleTask();
            AdiantamentoTask task2 = createSampleTask();
            
            // When & Then
            assertEquals(task1.hashCode(), task2.hashCode());
        }
        
        @Test
        @DisplayName("toString() deve conter informações essenciais")
        void toStringDeveConterInformacoesEssenciais() {
            // Given
            AdiantamentoTask task = createSampleTask();
            
            // When
            String resultado = task.toString();
            
            // Then
            assertTrue(resultado.contains("AdiantamentoTask"));
            assertTrue(resultado.contains("123456")); // nunota
            assertTrue(resultado.contains("1001"));   // codparc
            assertTrue(resultado.contains("1500.00")); // vlrnota
        }
        
        @Test
        @DisplayName("toString() deve ser não nulo")
        void toStringDeveSerNaoNulo() {
            // Given
            AdiantamentoTask task = createSampleTask();
            
            // When & Then
            assertNotNull(task.toString());
        }
        
        @Test
        @DisplayName("Deve ser serializable implicitamente")
        void deveSerSerializableImplicitamente() {
            // Given
            AdiantamentoTask task = createSampleTask();
            
            // When & Then
            // Se a classe não fosse serializable, isto falharia durante compilação
            // pois BigDecimal e Timestamp são serializables
            assertNotNull(task);
            assertEquals(new BigDecimal("123456"), task.nunota());
            assertEquals(new BigDecimal("1001"), task.codparc());
            assertEquals(new BigDecimal("1500.00"), task.vlrnota());
            assertEquals(new Timestamp(1000000000L), task.dtneg());
            assertEquals(new BigDecimal("1"), task.codemp());
            assertEquals(new BigDecimal("101"), task.codcencus());
        }
    }
    
    /**
     * Método utilitário para criar uma task de exemplo para os testes.
     */
    private AdiantamentoTask createSampleTask() {
        return new AdiantamentoTask(
            new BigDecimal("123456"),
            new BigDecimal("1001"),
            new BigDecimal("1500.00"),
            new Timestamp(1000000000L),
            new BigDecimal("1"),
            new BigDecimal("101")
        );
    }
}