package br.com.bellube.sankhya.eventos.VendaPixAdianta.service;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.TestDataBuilder;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.ConfiguracaoHelper;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.util.AuditLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários para AdiantamentoService.
 * 
 * Cobre todos os cenários especificados nas diretrizes:
 * - Happy Path: Criação bem-sucedida de adiantamento
 * - Business Rule Failures: Falhas de validação e regras de negócio
 * - Configuration Failures: Parâmetros inválidos
 * - Edge Cases: Casos extremos e situações inesperadas
 */
@DisplayName("AdiantamentoService Tests")
class AdiantamentoServiceTest {
    
    private AdiantamentoService adiantamentoService;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adiantamentoService = new AdiantamentoService();
    }
    
    @Nested
    @DisplayName("Happy Path Tests")
    class HappyPathTests {
        
        @Test
        @DisplayName("Deve criar adiantamento com sucesso para venda PIX válida")
        void devecriarAdiantamentoComSucesso() {
            // Given - Cenário de sucesso
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock da configuração válida
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(new BigDecimal("201"));
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenReturn(new BigDecimal("15"));
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenReturn(new BigDecimal("10101")); // Natureza analítica
                configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamentoRec)
                    .thenReturn(new BigDecimal("202"));
                
                // When - Executar o serviço
                assertDoesNotThrow(() -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then - Verificar log de sucesso
                auditLogger.verify(() -> AuditLogger.logSuccess(task.getNunota()), times(1));
                auditLogger.verify(() -> AuditLogger.logError(any(BigDecimal.class), anyString(), any(Exception.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve processar venda PIX de valor alto corretamente")
        void deveProcessarVendaPixDeValorAlto() {
            // Given
            AdiantamentoTask task = TestDataBuilder.Scenarios.highValuePixSale();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock da configuração válida
                setupValidConfiguration(configHelper);
                
                // When - Executar o serviço
                assertDoesNotThrow(() -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                auditLogger.verify(() -> AuditLogger.logSuccess(task.getNunota()), times(1));
            }
        }
        
        @Test
        @DisplayName("Deve processar venda PIX sem centro de custo")
        void deveProcessarVendaPixSemCentroCusto() {
            // Given
            AdiantamentoTask task = TestDataBuilder.Scenarios.pixSaleWithoutCentroCusto();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When
                assertDoesNotThrow(() -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                auditLogger.verify(() -> AuditLogger.logSuccess(task.getNunota()), times(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Business Rule Failure Tests")
    class BusinessRuleFailureTests {
        
        @Test
        @DisplayName("Deve falhar quando natureza não é analítica")
        void deveFalharQuandoNaturezaNaoEhAnalitica() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock configuração com natureza sintética (inválida)
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(new BigDecimal("201"));
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenReturn(new BigDecimal("15"));
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenReturn(new BigDecimal("10000")); // Natureza sintética
                configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                assertTrue(exception.getMessage().contains("Natureza") || 
                          exception.getMessage().contains("analítica"));
                
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
                auditLogger.verify(() -> AuditLogger.logSuccess(any(BigDecimal.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve falhar com parceiro inexistente")
        void deveFalharComParceiroInexistente() {
            // Given
            AdiantamentoTask task = TestDataBuilder.Scenarios.pixSaleWithInvalidPartner();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
                auditLogger.verify(() -> AuditLogger.logSuccess(any(BigDecimal.class)), never());
            }
        }
        
        @Test
        @DisplayName("Deve falhar quando exceder limite de crédito")
        void deveFalharQuandoExcederLimiteCredito() {
            // Given - Venda de valor muito alto que pode exceder limite
            AdiantamentoTask task = TestDataBuilder.adiantamentoTask()
                .vlrnota(new BigDecimal("1000000.00")) // Valor que excede limite típico
                .build();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Configuration Failure Tests")
    class ConfigurationFailureTests {
        
        @Test
        @DisplayName("Deve falhar quando código TOP é inválido")
        void deveFalharQuandoCodigoTopEhInvalido() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock configuração com TOP inválido
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(new BigDecimal("999999")); // TOP inexistente
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenReturn(new BigDecimal("15"));
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenReturn(new BigDecimal("10101"));
                configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
            }
        }
        
        @Test
        @DisplayName("Deve falhar quando conta bancária é inválida")
        void deveFalharQuandoContaBancariaEhInvalida() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock configuração com conta bancária inválida
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(new BigDecimal("201"));
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenReturn(new BigDecimal("15"));
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenReturn(new BigDecimal("10101"));
                configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
                    .thenReturn(new BigDecimal("999999")); // Conta inexistente
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(new BigDecimal("1"));
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
            }
        }
        
        @Test
        @DisplayName("Deve falhar quando ConfiguracaoHelper lança exceção")
        void deveFalharQuandoConfiguracaoHelperLancaExcecao() {
            // Given
            AdiantamentoTask task = TestDataBuilder.validAdiantamentoTask();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                // Mock ConfiguracaoHelper lançando exceção
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenThrow(new RuntimeException("Parâmetro VENDAPIX.CODTOP não encontrado"));
                
                // When
                Exception exception = assertThrows(Exception.class, () -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                assertNotNull(exception);
                assertTrue(exception.getMessage().contains("VENDAPIX.CODTOP") ||
                          exception.getMessage().contains("não encontrado"));
                
                auditLogger.verify(() -> AuditLogger.logError(eq(task.getNunota()), anyString(), any(Exception.class)), times(1));
            }
        }
    }
    
    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Deve lançar exceção para task nula")
        void deveLancarExcecaoParaTaskNula() {
            // When & Then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                adiantamentoService.criarAdiantamentoParaVenda(null);
            });
            
            assertEquals("Task não pode ser nula.", exception.getMessage());
        }
        
        @Test
        @DisplayName("Deve processar venda PIX de valor mínimo")
        void deveProcessarVendaPixDeValorMinimo() {
            // Given
            AdiantamentoTask task = TestDataBuilder.Scenarios.lowValuePixSale();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When
                assertDoesNotThrow(() -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                auditLogger.verify(() -> AuditLogger.logSuccess(task.getNunota()), times(1));
            }
        }
        
        @Test
        @DisplayName("Deve processar venda de empresa diferente")
        void deveProcessarVendaDeEmpresaDiferente() {
            // Given
            AdiantamentoTask task = TestDataBuilder.Scenarios.pixSaleFromDifferentCompany();
            
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class);
                 MockedStatic<AuditLogger> auditLogger = mockStatic(AuditLogger.class)) {
                
                setupValidConfiguration(configHelper);
                
                // When
                assertDoesNotThrow(() -> {
                    adiantamentoService.criarAdiantamentoParaVenda(task);
                });
                
                // Then
                auditLogger.verify(() -> AuditLogger.logSuccess(task.getNunota()), times(1));
            }
        }
    }
    
    /**
     * Método auxiliar para configurar mocks válidos do ConfiguracaoHelper
     */
    private void setupValidConfiguration(MockedStatic<ConfiguracaoHelper> configHelper) {
        configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
            .thenReturn(new BigDecimal("201"));
        configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
            .thenReturn(new BigDecimal("15"));
        configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
            .thenReturn(new BigDecimal("10101")); // Natureza analítica
        configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
            .thenReturn(new BigDecimal("1"));
        configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
            .thenReturn(new BigDecimal("1"));
        configHelper.when(ConfiguracaoHelper::getCodTopAdiantamentoRec)
            .thenReturn(new BigDecimal("202"));
    }
}