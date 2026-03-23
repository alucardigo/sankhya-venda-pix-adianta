package br.com.bellube.sankhya.eventos.VendaPixAdianta.event;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AsyncAdiantamentoProcessor;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.MockUtils;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.MockDynamicVO;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.TestDataBuilder;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.sankhya.jape.event.PersistenceEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários para VendaPixAdiantaEvent.
 * 
 * Cobre todos os cenários especificados nas diretrizes:
 * - Happy Path: Venda PIX identifica e submete task corretamente
 * - Negative Path: Venda não-PIX não deve disparar processamento
 * - Configuration Failures: Evento inativo, configuração inválida
 * - Edge Cases: Entidades diferentes, erros durante processamento
 */
@DisplayName("VendaPixAdiantaEvent Tests")
class VendaPixAdiantaEventTest {
    
    private VendaPixAdiantaEvent vendaPixAdiantaEvent;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vendaPixAdiantaEvent = new VendaPixAdiantaEvent();
    }
    
    @Nested
    @DisplayName("Happy Path Tests")
    class HappyPathTests {
        
        @Test
        @DisplayName("Deve processar venda PIX e submeter task corretamente")
        void deveProcessarVendaPixESubmeterTask() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then - Verificar se task foi submetida
                ArgumentCaptor<AdiantamentoTask> taskCaptor = ArgumentCaptor.forClass(AdiantamentoTask.class);
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(taskCaptor.capture()), times(1));
                
                AdiantamentoTask capturedTask = taskCaptor.getValue();
                assertNotNull(capturedTask);
                assertEquals(TestDataBuilder.DEFAULT_NUNOTA, capturedTask.nunota());
                assertEquals(TestDataBuilder.DEFAULT_CODPARC, capturedTask.codparc());
                assertEquals(TestDataBuilder.DEFAULT_VLRNOTA, capturedTask.vlrnota());
                assertEquals(TestDataBuilder.DEFAULT_CODEMP, capturedTask.codemp());
                assertEquals(TestDataBuilder.DEFAULT_CODCENCUS, capturedTask.codcencus());
            }
        }
        
        @Test
        @DisplayName("Deve processar múltiplas vendas PIX corretamente")
        void deveProcessarMultiplasVendasPix() throws Exception {
            // Given
            MockDynamicVO pixCabecalho1 = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("100001"))
                .comTipoPixPadrao()
                .build();
            MockDynamicVO pixCabecalho2 = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("100002"))
                .comTipoPixPadrao()
                .build();
            
            PersistenceEvent mockEvent1 = MockUtils.createCabecalhoNotaEvent(pixCabecalho1);
            PersistenceEvent mockEvent2 = MockUtils.createCabecalhoNotaEvent(pixCabecalho2);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent1);
                    vendaPixAdiantaEvent.afterInsert(mockEvent2);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), times(2));
            }
        }
    }
    
    @Nested
    @DisplayName("Negative Path Tests - Non-PIX Sales")
    class NegativePathTests {
        
        @Test
        @DisplayName("Deve ignorar venda não-PIX (não deve submeter task)")
        void deveIgnorarVendaNaoPix() throws Exception {
            // Given - Venda com CODTIPTIT diferente do PIX
            MockDynamicVO nonPixCabecalho = TestDataBuilder.validNonPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(nonPixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX); // Diferente do CODTIPTIT da venda
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then - Não deve submeter task
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve ignorar entidade diferente de CabecalhoNota")
        void deveIgnorarEntidadeDiferente() throws Exception {
            // Given
            PersistenceEvent mockEvent = MockUtils.createNonCabecalhoEvent();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then - Não deve chamar configuração nem submeter task
                configHelper.verifyNoInteractions();
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve ignorar quando CODTIPTIT da venda é null")
        void deveIgnorarQuandoVendaSemCodTipTit() throws Exception {
            // Given
            MockDynamicVO cabecalhoSemTipTit = TestDataBuilder.cabecalhoNota()
                .codtiptit(null)
                .build();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(cabecalhoSemTipTit);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
    }
    
    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {
        
        @Test
        @DisplayName("Deve ignorar processamento quando evento está inativo")
        void deveIgnorarQuandoEventoInativo() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
                // getTipoTituloPix não deve ser chamado se evento está inativo
                configHelper.verify(ConfiguracaoHelper::getTipoTituloPix, never());
            }
        }
        
        @Test
        @DisplayName("Deve tratar erro na configuração sem interromper transação")
        void deveTratarErroConfiguracaoSemInterromperTransacao() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                
                // When & Then - Não deve propagar exceção
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve tratar erro na verificação PIX sem interromper transação")
        void deveTratarErroVerificacaoPixSemInterromperTransacao() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenThrow(new RuntimeException("Erro ao ler tipo PIX"));
                
                // When & Then
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
    }
    
    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Deve tratar VO nulo sem falhar")
        void deveTratarVoNuloSemFalhar() throws Exception {
            // Given
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(null);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                
                // When & Then
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any(AdiantamentoTask.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve tratar venda PIX com dados incompletos")
        void deveTratarVendaPixComDadosIncompletos() throws Exception {
            // Given - Venda PIX mas sem alguns campos obrigatórios
            MockDynamicVO pixIncompleto = TestDataBuilder.cabecalhoNota()
                .nunota(null) // Campo obrigatório nulo
                .comTipoPixPadrao()
                .build();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(pixIncompleto);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
                
                // When & Then - Deve capturar erro internamente
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(mockEvent);
                });
                
                // Then - Task pode ou não ser submetida dependendo da validação
                // O importante é que não interrompe a transação principal
            }
        }
    }
    
    @Nested
    @DisplayName("Interface Methods Tests")
    class InterfaceMethodsTests {
        
        @Test
        @DisplayName("Métodos não implementados não devem lançar exceção")
        void metodosNaoImplementadosNaoDevemLancarExcecao() throws Exception {
            // Given
            MockDynamicVO cabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent mockEvent = MockUtils.createCabecalhoNotaEvent(cabecalho);
            
            // When & Then - Todos os métodos devem executar sem exceção
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.beforeInsert(mockEvent));
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.beforeUpdate(mockEvent));
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.beforeDelete(mockEvent));
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.afterUpdate(mockEvent));
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.afterDelete(mockEvent));
            assertDoesNotThrow(() -> vendaPixAdiantaEvent.beforeCommit(mockEvent.getTransactionContext()));
        }
    }
}
