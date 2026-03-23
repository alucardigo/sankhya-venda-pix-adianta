package br.com.bellube.sankhya.eventos.VendaPixAdianta.integration;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AsyncAdiantamentoProcessor;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.event.VendaPixAdiantaEvent;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.MockUtils;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.TestDataBuilder;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.MockDynamicVO;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;
import br.com.sankhya.jape.event.PersistenceEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes de integração para o módulo VendaPixAdianta.
 * 
 * Verifica o fluxo completo especificado nas diretrizes:
 * 1. Happy Path: Venda PIX → Task → Processamento → Adiantamento → Sucesso
 * 2. Negative Path: Venda não-PIX → Ignorada (sem processamento)
 * 3. Business Rule Failure: Venda PIX → Task → Processamento → Falha de regra → Erro
 * 4. Configuration Failure: Evento inativo ou parâmetros inválidos → Sem processamento
 */
@DisplayName("VendaPixAdianta Integration Tests")
class VendaPixAdiantaIntegrationTest {
    
    private VendaPixAdiantaEvent vendaPixAdiantaEvent;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vendaPixAdiantaEvent = new VendaPixAdiantaEvent();
    }
    
    @Nested
    @DisplayName("Happy Path Integration Tests")
    class HappyPathIntegrationTests {
        
        @Test
        @DisplayName("Fluxo completo: Venda PIX deve criar adiantamento com sucesso")
        void fluxoCompletoVendaPixDevecriarAdiantamentoComSucesso() throws Exception {
            // Given - Cenário de sucesso completo
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            final CountDownLatch successLatch = new CountDownLatch(1);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock configuração válida
                setupValidConfiguration(configHelper);
                
                // Mock processamento assíncrono
                asyncProcessor.when(() -> AsyncAdiantamentoProcessor.submitTask(any()))
                    .thenAnswer(invocation -> {
                        // Simular processamento assíncrono bem-sucedido
                        new Thread(() -> {
                            try {
                                Thread.sleep(100); // Simular processamento
                                AuditLogger.logSuccess(TestDataBuilder.DEFAULT_NUNOTA);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                        return null;
                    });
                
                // Mock audit logger para detectar sucesso
                auditLogger.when(() -> AuditLogger.logSuccess(any(BigDecimal.class)))
                    .thenAnswer(invocation -> {
                        successLatch.countDown();
                        return null;
                    });
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Aguardar processamento completo
                assertTrue(successLatch.await(10, TimeUnit.SECONDS), 
                    "Processamento completo não foi concluído no tempo esperado");
                
                // Verificar que toda a cadeia foi executada
                configHelper.verify(ConfiguracaoHelper::getTipoTituloPix, times(1));
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), times(1));
                auditLogger.verify(() -> AuditLogger.logSuccess(TestDataBuilder.DEFAULT_NUNOTA), timeout(5000).times(1));
            }
        }
        
        @Test
        @DisplayName("Múltiplas vendas PIX devem ser processadas independentemente")
        void multiplasVendasPixDevemSerProcessadasIndependentemente() throws Exception {
            // Given
            MockDynamicVO pixCabecalho1 = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("100001"))
                .comTipoPixPadrao()
                .build();
            MockDynamicVO pixCabecalho2 = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("100002"))
                .comTipoPixPadrao()
                .build();
            MockDynamicVO pixCabecalho3 = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("100003"))
                .comTipoPixPadrao()
                .build();
            
            PersistenceEvent event1 = MockUtils.createCabecalhoNotaEvent(pixCabecalho1);
            PersistenceEvent event2 = MockUtils.createCabecalhoNotaEvent(pixCabecalho2);
            PersistenceEvent event3 = MockUtils.createCabecalhoNotaEvent(pixCabecalho3);
            
            final CountDownLatch allProcessedLatch = new CountDownLatch(3);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // Mock para contar processamentos
                auditLogger.when(() -> AuditLogger.logSuccess(any(BigDecimal.class)))
                    .thenAnswer(invocation -> {
                        allProcessedLatch.countDown();
                        return null;
                    });
                
                auditLogger.when(() -> AuditLogger.logError(any(BigDecimal.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        allProcessedLatch.countDown();
                        return null;
                    });
                
                // When - Processar múltiplas vendas
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event1);
                    vendaPixAdiantaEvent.afterInsert(event2);
                    vendaPixAdiantaEvent.afterInsert(event3);
                });
                
                // Then - Todas devem ser submetidas para processamento
                assertTrue(allProcessedLatch.await(15, TimeUnit.SECONDS),
                    "Nem todas as vendas foram processadas no tempo esperado");
                
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), times(3));
            }
        }
    }
    
    @Nested
    @DisplayName("Negative Path Integration Tests")
    class NegativePathIntegrationTests {
        
        @Test
        @DisplayName("Venda não-PIX deve ser completamente ignorada")
        void vendaNaoPixDeveSerCompletamenteIgnorada() throws Exception {
            // Given - Venda não-PIX
            MockDynamicVO nonPixCabecalho = TestDataBuilder.validNonPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(nonPixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When - Processar venda não-PIX
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Nenhum processamento deve ser disparado
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), never());
                auditLogger.verify(() -> AuditLogger.logSuccess(any(BigDecimal.class)), never());
                auditLogger.verify(() -> AuditLogger.logError(any(BigDecimal.class), anyString(), any(Exception.class)), never());
            }
        }
        
        @Test
        @DisplayName("Entidade diferente de CabecalhoNota deve ser ignorada")
        void entidadeDiferenteDeCabecalhoNotaDeveSerIgnorada() throws Exception {
            // Given
            PersistenceEvent event = MockUtils.createNonCabecalhoEvent();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Nada deve ser processado, nem configuração deve ser consultada
                configHelper.verifyNoInteractions();
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), never());
                auditLogger.verifyNoInteractions();
            }
        }
        
        @Test
        @DisplayName("Mix de vendas PIX e não-PIX deve processar apenas PIX")
        void mixDeVendasPixENaoPixDeveProcessarApenasPix() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho()
                .withProperty("NUNOTA", new BigDecimal("200001"));
            MockDynamicVO nonPixCabecalho1 = TestDataBuilder.validNonPixCabecalho()
                .withProperty("NUNOTA", new BigDecimal("200002"));
            MockDynamicVO nonPixCabecalho2 = TestDataBuilder.validNonPixCabecalho()
                .withProperty("NUNOTA", new BigDecimal("200003"));
            
            PersistenceEvent pixEvent = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            PersistenceEvent nonPixEvent1 = MockUtils.createCabecalhoNotaEvent(nonPixCabecalho1);
            PersistenceEvent nonPixEvent2 = MockUtils.createCabecalhoNotaEvent(nonPixCabecalho2);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When - Processar mix de vendas
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(nonPixEvent1);
                    vendaPixAdiantaEvent.afterInsert(pixEvent); // Apenas esta deve ser processada
                    vendaPixAdiantaEvent.afterInsert(nonPixEvent2);
                });
                
                // Then - Apenas 1 task deve ser submetida (da venda PIX)
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), times(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Business Rule Failure Integration Tests")
    class BusinessRuleFailureIntegrationTests {
        
        @Test
        @DisplayName("Venda PIX com parceiro inválido deve gerar erro")
        void vendaPixComParceiroInvalidoDeveGerarErro() throws Exception {
            // Given - Venda PIX com parceiro que causará erro
            MockDynamicVO pixCabecalho = TestDataBuilder.cabecalhoNota()
                .nunota(new BigDecimal("300001"))
                .codparc(new BigDecimal("999999")) // Parceiro inexistente
                .comTipoPixPadrao()
                .build();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            final CountDownLatch errorLatch = new CountDownLatch(1);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // Mock processamento que resultará em erro
                asyncProcessor.when(() -> AsyncAdiantamentoProcessor.submitTask(any()))
                    .thenAnswer(invocation -> {
                        // Simular processamento que resulta em erro
                        new Thread(() -> {
                            try {
                                Thread.sleep(100);
                                AuditLogger.logError(new BigDecimal("300001"), 
                                    "Parceiro não encontrado", 
                                    new RuntimeException("Parceiro não encontrado"));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                        return null;
                    });
                
                // Mock para detectar erro
                auditLogger.when(() -> AuditLogger.logError(any(BigDecimal.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        errorLatch.countDown();
                        return null;
                    });
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Erro deve ser registrado
                assertTrue(errorLatch.await(10, TimeUnit.SECONDS),
                    "Erro não foi registrado no tempo esperado");
                
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), times(1));
                auditLogger.verify(() -> AuditLogger.logError(eq(new BigDecimal("300001")), anyString(), any(Exception.class)), 
                    timeout(5000).times(1));
                auditLogger.verify(() -> AuditLogger.logSuccess(any(BigDecimal.class)), never());
            }
        }
        
        @Test
        @DisplayName("Venda PIX com configuração inválida deve gerar erro")
        void vendaPixComConfiguracaoInvalidaDeveGerarErro() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            final CountDownLatch errorLatch = new CountDownLatch(1);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock configuração inválida
                configHelper.when(ConfiguracaoHelper::getTipoTituloPix).thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(new BigDecimal("999999")); // TOP inexistente
                setupOtherValidConfigs(configHelper);
                
                // Mock processamento que falhará por configuração
                asyncProcessor.when(() -> AsyncAdiantamentoProcessor.submitTask(any()))
                    .thenAnswer(invocation -> {
                        new Thread(() -> {
                            try {
                                Thread.sleep(100);
                                AuditLogger.logError(TestDataBuilder.DEFAULT_NUNOTA, 
                                    "TOP não encontrado", 
                                    new RuntimeException("TOP 999999 não encontrado"));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                        return null;
                    });
                
                auditLogger.when(() -> AuditLogger.logError(any(BigDecimal.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        errorLatch.countDown();
                        return null;
                    });
                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then
                assertTrue(errorLatch.await(10, TimeUnit.SECONDS),
                    "Erro de configuração não foi registrado");
                
                auditLogger.verify(() -> AuditLogger.logError(eq(TestDataBuilder.DEFAULT_NUNOTA), 
                    contains("TOP"), any(Exception.class)), timeout(5000).times(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Configuration Failure Integration Tests")
    class ConfigurationFailureIntegrationTests {
        
        @Test
        @DisplayName("Evento inativo deve impedir qualquer processamento")
        void eventoInativoDeveImpedirQualquerProcessamento() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {

                
                // When
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Nada deve ser processado
                configHelper.verify(ConfiguracaoHelper::getTipoTituloPix, never()); // Não deve verificar tipo PIX
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), never());
                auditLogger.verifyNoInteractions();
            }
        }
        
        @Test
        @DisplayName("Erro na leitura de configuração deve ser tratado graciosamente")
        void erroNaLeituraConfiguracaoDeveSerTratadoGraciosamente() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {

                
                // When & Then - Não deve interromper transação
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                // Then - Processamento não deve prosseguir devido ao erro
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), never());
                auditLogger.verifyNoInteractions();
            }
        }
    }
    
    @Nested
    @DisplayName("Performance Integration Tests")
    class PerformanceIntegrationTests {
        
        @Test
        @DisplayName("Deve processar vendas PIX sem afetar performance da transação principal")
        void deveProcessarVendasPixSemAfetarPerformanceTransacaoPrincipal() throws Exception {
            // Given
            MockDynamicVO pixCabecalho = TestDataBuilder.validPixCabecalho();
            PersistenceEvent event = MockUtils.createCabecalhoNotaEvent(pixCabecalho);
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AsyncAdiantamentoProcessor> asyncProcessor = mockStatic(AsyncAdiantamentoProcessor.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When - Medir tempo de execução do evento
                long startTime = System.currentTimeMillis();
                
                assertDoesNotThrow(() -> {
                    vendaPixAdiantaEvent.afterInsert(event);
                });
                
                long executionTime = System.currentTimeMillis() - startTime;
                
                // Then - Deve ser muito rápido (< 100ms) pois é assíncrono
                assertTrue(executionTime < 100, 
                    "Execução do evento demorou " + executionTime + "ms, muito tempo para processamento assíncrono");
                
                // Verificar que task foi submetida
                asyncProcessor.verify(() -> AsyncAdiantamentoProcessor.submitTask(any()), times(1));
            }
        }
    }
    
    /**
     * Setup de configuração válida completa
     */
    private void setupValidConfiguration(MockedStatic<ConfiguracaoHelper> configHelper) {
        configHelper.when(ConfiguracaoHelper::getTipoTituloPix).thenReturn(TestDataBuilder.DEFAULT_CODTIPTIT_PIX);
        setupOtherValidConfigs(configHelper);
    }
    
    /**
     * Setup das outras configurações válidas (exceto evento ativo e tipo PIX)
     */
    private void setupOtherValidConfigs(MockedStatic<ConfiguracaoHelper> configHelper) {
        configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento).thenReturn(new BigDecimal("201"));
        configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento).thenReturn(new BigDecimal("15"));
        configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento).thenReturn(new BigDecimal("10101"));
        configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento).thenReturn(new BigDecimal("1"));
        configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento).thenReturn(new BigDecimal("1"));
        configHelper.when(ConfiguracaoHelper::getCodTopAdiantamentoRec).thenReturn(new BigDecimal("202"));
    }
}
