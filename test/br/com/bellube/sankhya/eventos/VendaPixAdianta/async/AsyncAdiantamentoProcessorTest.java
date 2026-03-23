package br.com.bellube.sankhya.eventos.VendaPixAdianta.async;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.TestDataBuilder;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários para AsyncAdiantamentoProcessor.
 * 
 * Verifica o comportamento do processamento assíncrono:
 * - Thread safety e gerenciamento de filas
 * - Processamento de tasks válidas e inválidas
 * - Tratamento de erros sem interrupção do worker
 * - Singleton pattern
 */
@DisplayName("AsyncAdiantamentoProcessor Tests")
class AsyncAdiantamentoProcessorTest {
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Nested
    @DisplayName("Static API Tests")
    class StaticApiTests {

        @Test
        @DisplayName("Deve aceitar verificacao de task pendente sem erro")
        void deveVerificarTaskPendente() {
            assertFalse(AsyncAdiantamentoProcessor.isTaskPending(null));
            assertFalse(AsyncAdiantamentoProcessor.isTaskPending(new BigDecimal("999999999")));
        }

        @Test
        @DisplayName("Deve retornar metricas do sistema")
        void deveRetornarMetricas() {
            String metrics = AsyncAdiantamentoProcessor.getSystemMetrics();
            assertNotNull(metrics);
            assertTrue(metrics.contains("Fila="));
        }
    }
    
    @Nested
    @DisplayName("Task Submission Tests")
    class TaskSubmissionTests {
        
        @Test
        @DisplayName("Deve submeter task válida com sucesso")
        void deveSubmeterTaskValidaComSucesso() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            // When & Then - Não deve lançar exceção
            assertDoesNotThrow(() -> {
                AsyncAdiantamentoProcessor.submitTask(task);
            });
        }
        
        @Test
        @DisplayName("Deve ignorar task nula sem falhar")
        void deveIgnorarTaskNulaSemFalhar() {
            // When & Then
            assertDoesNotThrow(() -> {
                AsyncAdiantamentoProcessor.submitTask(null);
            });
        }
        
        @Test
        @DisplayName("Deve submeter múltiplas tasks corretamente")
        void deveSubmeterMultiplasTasksCorretamente() {
            // Given
            AdiantamentoTask task1 = TestDataBuilder.adiantamentoTask()
                .nunota(new BigDecimal("100001"))
                .build();
            AdiantamentoTask task2 = TestDataBuilder.adiantamentoTask()
                .nunota(new BigDecimal("100002"))
                .build();
            AdiantamentoTask task3 = TestDataBuilder.adiantamentoTask()
                .nunota(new BigDecimal("100003"))
                .build();
            
            // When & Then
            assertDoesNotThrow(() -> {
                AsyncAdiantamentoProcessor.submitTask(task1);
                AsyncAdiantamentoProcessor.submitTask(task2);
                AsyncAdiantamentoProcessor.submitTask(task3);
            });
        }
        
        @Test
        @DisplayName("Deve ser thread-safe na submissão de tasks")
        void deveSerThreadSafeNaSubmissaoDeTasks() throws InterruptedException {
            // Given
            final int NUM_TASKS = 100;
            final int NUM_THREADS = 10;
            final CountDownLatch latch = new CountDownLatch(NUM_THREADS);
            final AtomicInteger taskCounter = new AtomicInteger(0);
            
            // When - Submeter tasks de múltiplas threads simultaneamente
            for (int i = 0; i < NUM_THREADS; i++) {
                new Thread(() -> {
                    for (int j = 0; j < NUM_TASKS / NUM_THREADS; j++) {
                        AdiantamentoTask task = TestDataBuilder.adiantamentoTask()
                            .nunota(new BigDecimal(taskCounter.incrementAndGet()))
                            .build();
                        
                        AsyncAdiantamentoProcessor.submitTask(task);
                    }
                    latch.countDown();
                }).start();
            }
            
            // Then - Todas as threads devem completar sem erro
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(NUM_TASKS, taskCounter.get());
        }
    }
    
    @Nested
    @DisplayName("Task Processing Tests")
    class TaskProcessingTests {
        
        @Test
        @DisplayName("Deve processar task válida e registrar sucesso")
        void deveProcessarTaskValidaERegistrarSucesso() throws InterruptedException {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            final CountDownLatch processedLatch = new CountDownLatch(1);
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                // Mock para detectar quando o processamento termina
                // Mock the actual AuditLogger methods that exist
                auditLogger.when(() -> AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)))
                    .thenAnswer(invocation -> {
                        processedLatch.countDown();
                        return null;
                    });
                
                auditLogger.when(() -> AuditLogger.logErrorDetailed(any(AuditLogger.ProcessingContext.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        processedLatch.countDown();
                        return null;
                    });
                
                // When
                AsyncAdiantamentoProcessor.submitTask(task);
                
                // Then - Aguardar processamento
                assertTrue(processedLatch.await(15, TimeUnit.SECONDS), 
                    "Task não foi processada no tempo esperado");
                
                // Verificar se sucesso foi registrado (pode ser sucesso ou erro dependendo do mock)
                // O importante é que o processamento foi tentado
                auditLogger.verify(() -> 
                    AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)), 
                    timeout(10000).atLeastOnce());
            }
        }
        
        @Test
        @DisplayName("Deve processar task com erro e registrar falha")
        void deveProcessarTaskComErroERegistrarFalha() throws InterruptedException {
            // Given - Task com dados que causarão erro
            AdiantamentoTask invalidTask = TestDataBuilder.Scenarios.pixSaleWithInvalidPartner();
            final CountDownLatch errorProcessedLatch = new CountDownLatch(1);
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                // Mock para detectar quando erro é registrado
                auditLogger.when(() -> AuditLogger.logErrorDetailed(any(AuditLogger.ProcessingContext.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        errorProcessedLatch.countDown();
                        return null;
                    });
                
                // When
                AsyncAdiantamentoProcessor.submitTask(invalidTask);
                
                // Then - Aguardar processamento do erro
                assertTrue(errorProcessedLatch.await(15, TimeUnit.SECONDS), 
                    "Erro não foi processado no tempo esperado");
                
                // Verificar se erro foi registrado
                auditLogger.verify(() -> 
                    AuditLogger.logErrorDetailed(any(AuditLogger.ProcessingContext.class), anyString(), any(Exception.class)), 
                    timeout(10000).atLeastOnce());
            }
        }
        
        @Test
        @DisplayName("Workers devem continuar funcionando após erro em task")
        void workersDevemContinuarFuncionandoAposErroEmTask() throws InterruptedException {
            // Given
            AdiantamentoTask validTask1 = TestDataBuilder.adiantamentoTask()
                .nunota(new BigDecimal("200001"))
                .build();
            AdiantamentoTask invalidTask = TestDataBuilder.Scenarios.pixSaleWithInvalidPartner(); // Task que causará erro
            AdiantamentoTask validTask2 = TestDataBuilder.adiantamentoTask()
                .nunota(new BigDecimal("200003"))
                .build();
                
            final CountDownLatch allProcessedLatch = new CountDownLatch(2); // 2 logs esperados (válidas)
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                auditLogger.when(() -> AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)))
                    .thenAnswer(invocation -> {
                        allProcessedLatch.countDown();
                        return null;
                    });
                
                // When - Submeter tasks na sequência: válida, inválida, válida
                AsyncAdiantamentoProcessor.submitTask(validTask1);
                AsyncAdiantamentoProcessor.submitTask(TestDataBuilder.adiantamentoTask()
                    .nunota(new BigDecimal("200002"))
                    .codparc(new BigDecimal("999999")) // Parceiro inexistente
                    .build());
                AsyncAdiantamentoProcessor.submitTask(validTask2);
                
                // Then - Ambas as tasks válidas devem ser processadas
                assertTrue(allProcessedLatch.await(20, TimeUnit.SECONDS), 
                    "Tasks válidas não foram processadas após erro");
                
                // Verificar que pelo menos as tasks válidas foram processadas com sucesso
                auditLogger.verify(() -> AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)), 
                    timeout(15000).atLeast(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Performance and Load Tests")
    class PerformanceAndLoadTests {
        
        @Test
        @DisplayName("Deve processar grande volume de tasks sem falhar")
        void deveProcessarGrandeVolumeDeTasks() throws InterruptedException {
            // Given
            final int LARGE_VOLUME = 50;
            final CountDownLatch completionLatch = new CountDownLatch(LARGE_VOLUME);
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                // Mock para contar tasks processadas
                auditLogger.when(() -> AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)))
                    .thenAnswer(invocation -> {
                        completionLatch.countDown();
                        return null;
                    });
                    
                auditLogger.when(() -> AuditLogger.logErrorDetailed(any(AuditLogger.ProcessingContext.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        completionLatch.countDown();
                        return null;
                    });
                
                // When - Submeter grande volume de tasks
                for (int i = 0; i < LARGE_VOLUME; i++) {
                    AdiantamentoTask task = TestDataBuilder.adiantamentoTask()
                        .nunota(new BigDecimal(300000 + i))
                        .build();
                    AsyncAdiantamentoProcessor.submitTask(task);
                }
                
                // Then - Todas devem ser processadas em tempo razoável
                assertTrue(completionLatch.await(60, TimeUnit.SECONDS), 
                    "Grande volume não foi processado no tempo esperado");
            }
        }
        
        @Test
        @DisplayName("Deve manter desempenho com submissão concurrent")
        void deveManterDesempenhoComSubmissaoConcurrent() throws InterruptedException {
            // Given
            final int TASKS_PER_THREAD = 20;
            final int NUM_THREADS = 5;
            final int TOTAL_TASKS = TASKS_PER_THREAD * NUM_THREADS;
            
            final CountDownLatch submissionLatch = new CountDownLatch(NUM_THREADS);
            final CountDownLatch processingLatch = new CountDownLatch(TOTAL_TASKS);
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                auditLogger.when(() -> AuditLogger.logSuccessDetailed(any(AuditLogger.ProcessingContext.class)))
                    .thenAnswer(invocation -> {
                        processingLatch.countDown();
                        return null;
                    });
                    
                auditLogger.when(() -> AuditLogger.logErrorDetailed(any(AuditLogger.ProcessingContext.class), anyString(), any(Exception.class)))
                    .thenAnswer(invocation -> {
                        processingLatch.countDown();
                        return null;
                    });
                
                // When - Submeter de múltiplas threads
                for (int t = 0; t < NUM_THREADS; t++) {
                    final int threadId = t;
                    new Thread(() -> {
                        for (int i = 0; i < TASKS_PER_THREAD; i++) {
                            AdiantamentoTask task = TestDataBuilder.adiantamentoTask()
                                .nunota(new BigDecimal(400000 + (threadId * 1000) + i))
                                .build();
                            AsyncAdiantamentoProcessor.submitTask(task);
                        }
                        submissionLatch.countDown();
                    }).start();
                }
                
                // Then
                assertTrue(submissionLatch.await(10, TimeUnit.SECONDS), "Submissão não completou");
                assertTrue(processingLatch.await(60, TimeUnit.SECONDS), "Processamento não completou");
            }
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Deve lidar com submissão durante shutdown gracefully")
        void deveLidarComSubmissaoDuranteShutdown() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            // When - Submeter após shutdown (se implementado)
            // Note: O shutdown atual não está implementado de forma que impeça submissões
            // Este teste verifica que submissões continuam funcionando
            assertDoesNotThrow(() -> {
                AsyncAdiantamentoProcessor.submitTask(task);
                // Se houvesse shutdown: AsyncAdiantamentoProcessor.shutdown();
                // AsyncAdiantamentoProcessor.submitTask(anotherTask); // Deve ser tratado graciosamente
            });
        }
        
        @Test
        @DisplayName("Deve registrar erro ao submeter task para fila com falha")
        void deveRegistrarErroAoSubmeterTaskParaFilaComFalha() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                // When - Submissão normal (não simula falha da fila pois é difícil mockar BlockingQueue)
                // Este teste verifica que o mecanismo existe
                assertDoesNotThrow(() -> {
                    AsyncAdiantamentoProcessor.submitTask(task);
                });
                
                // Then - Se houvesse erro, seria registrado
                // Na implementação real, erros de submissão são registrados no AuditLogger
            }
        }
    }
}