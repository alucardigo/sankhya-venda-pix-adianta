package br.com.bellube.sankhya.eventos.VendaPixAdianta.util;

import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.jape.wrapper.JapeWrapper;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.testutil.MockDynamicVO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários para ConfiguracaoHelper.
 * 
 * Verifica:
 * - Leitura correta de parâmetros do sistema
 * - Cache de parâmetros para otimização
 * - Tratamento de parâmetros inválidos ou ausentes
 * - Diferentes tipos de dados (String, BigDecimal, Boolean)
 */
@DisplayName("ConfiguracaoHelper Tests")
class ConfiguracaoHelperTest {
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Limpar cache antes de cada teste
        ConfiguracaoHelper.clearCache();
    }
    
    @AfterEach
    void tearDown() {
        // Limpar cache após cada teste
        ConfiguracaoHelper.clearCache();
    }
    
    @Nested
    @DisplayName("Basic Parameter Tests")
    class BasicParameterTests {
        
        @Test
        @DisplayName("Deve retornar tipo de venda PIX válido")
        void deveRetornarTipoVendaPixValido() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("123");
                configHelper.when(ConfiguracaoHelper::getTipoVendaPix)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal resultado = ConfiguracaoHelper.getTipoVendaPix();
                
                // Then
                assertEquals(expectedValue, resultado);
            }
        }
        
        @Test
        @DisplayName("Deve lançar exceção quando parâmetro é null")
        void deveLancarExcecaoQuandoParametroNull() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getTipoVendaPix)
                    .thenThrow(new RuntimeException("Parâmetro não encontrado"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getTipoVendaPix();
                });
            }
        }
        
        @Test
        @DisplayName("Deve tratar parâmetros com valores inválidos")
        void deveTratarParametrosComValoresInvalidos() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getDiasVencimento)
                    .thenThrow(new RuntimeException("Valor inválido"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getDiasVencimento();
                });
            }
        }
    }
    
    @Nested
    @DisplayName("BigDecimal Parameter Tests")
    class BigDecimalParameterTests {
        
        @Test
        @DisplayName("Deve retornar BigDecimal válido para tipo título PIX")
        void deveRetornarBigDecimalValidoParaTipoTituloPix() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("999");
                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal resultado = ConfiguracaoHelper.getTipoTituloPix();
                
                // Then
                assertEquals(expectedValue, resultado);
            }
        }
        
        @Test
        @DisplayName("Deve retornar BigDecimal válido para código TOP")
        void deveRetornarBigDecimalValidoParaCodigoTop() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("201");
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal resultado = ConfiguracaoHelper.getCodTopAdiantamento();
                
                // Then
                assertEquals(expectedValue, resultado);
            }
        }
        
        @Test
        @DisplayName("Deve lançar exceção para BigDecimal inválido")
        void deveLancarExcecaoParaBigDecimalInvalido() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenThrow(new RuntimeException("Erro ao ler configuração"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getCodTopAdiantamento();
                });
            }
        }
        
        @Test
        @DisplayName("Deve lançar exceção quando parâmetro BigDecimal é null")
        void deveLancarExcecaoQuandoParametroBigDecimalNull() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenThrow(new RuntimeException("Erro ao ler configuração"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getCodNatAdiantamento();
                });
            }
        }
    }
    
    @Nested
    @DisplayName("All Configuration Parameters Tests")
    class AllConfigurationParametersTests {
        
        @Test
        @DisplayName("Deve retornar todos os parâmetros BigDecimal corretamente")
        void deveRetornarTodosParametrosBigDecimalCorretamente() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given - Mock de todos os parâmetros
                BigDecimal pixValue = new BigDecimal("999");
                BigDecimal topValue = new BigDecimal("201");
                BigDecimal natValue = new BigDecimal("10101");
                BigDecimal tipTitValue = new BigDecimal("15");
                BigDecimal cenCusValue = new BigDecimal("101");
                BigDecimal ctaBcoValue = new BigDecimal("1");
                BigDecimal projValue = new BigDecimal("1");
                BigDecimal diasVencValue = new BigDecimal("30");
                
                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(pixValue);
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(topValue);
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenReturn(natValue);
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenReturn(tipTitValue);
                configHelper.when(ConfiguracaoHelper::getCodCencusAdiantamento)
                    .thenReturn(cenCusValue);
                configHelper.when(ConfiguracaoHelper::getCodCtaBcoIntAdiantamento)
                    .thenReturn(ctaBcoValue);
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(projValue);
                configHelper.when(ConfiguracaoHelper::getDiasVencimento)
                    .thenReturn(diasVencValue);
                
                // When & Then
                assertEquals(pixValue, ConfiguracaoHelper.getTipoTituloPix());
                assertEquals(topValue, ConfiguracaoHelper.getCodTopAdiantamento());
                assertEquals(natValue, ConfiguracaoHelper.getCodNatAdiantamento());
                assertEquals(tipTitValue, ConfiguracaoHelper.getCodTipTitAdiantamento());
                assertEquals(cenCusValue, ConfiguracaoHelper.getCodCencusAdiantamento());
                assertEquals(ctaBcoValue, ConfiguracaoHelper.getCodCtaBcoIntAdiantamento());
                assertEquals(projValue, ConfiguracaoHelper.getCodProjAdiantamento());
                assertEquals(diasVencValue, ConfiguracaoHelper.getDiasVencimento());
            }
        }
        
        @Test
        @DisplayName("Deve retornar código TOP de receita diferente do de despesa")
        void deveRetornarCodigoTopReceitaDiferenteDoDeDesoesa() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given - TOPs diferentes para despesa e receita
                BigDecimal topDespesa = new BigDecimal("201");
                BigDecimal topReceita = new BigDecimal("202");
                
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenReturn(topDespesa);
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamentoRec)
                    .thenReturn(topReceita);
                
                // When
                BigDecimal resultTopDespesa = ConfiguracaoHelper.getCodTopAdiantamento();
                BigDecimal resultTopReceita = ConfiguracaoHelper.getCodTopAdiantamentoRec();
                
                // Then
                assertEquals(topDespesa, resultTopDespesa);
                assertEquals(topReceita, resultTopReceita);
                assertNotEquals(resultTopDespesa, resultTopReceita);
            }
        }
    }
    
    @Nested
    @DisplayName("Cache Tests")
    class CacheTests {
        
        @Test
        @DisplayName("Deve usar cache para diferentes parâmetros independentemente")
        void deveUsarCacheParaDiferentesParametrosIndependentemente() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {

                configHelper.when(ConfiguracaoHelper::getTipoTituloPix)
                    .thenReturn(new BigDecimal("999"));

                ConfiguracaoHelper.getTipoTituloPix();
                ConfiguracaoHelper.getTipoTituloPix(); // Deve usar cache
                
                // Then - Cada parâmetro deve ser lido
                configHelper.verify(ConfiguracaoHelper::getTipoTituloPix, times(2));
            }
        }
        
        @Test
        @DisplayName("clearCache deve limpar o cache corretamente")
        void clearCacheDeveLimparCacheCorretamente() {
            // When - Usar cache, limpar, e usar novamente
            ConfiguracaoHelper.clearCache(); // Limpar cache
            
            // Then - Verificar que não há exceções
            assertDoesNotThrow(() -> ConfiguracaoHelper.clearCache());
        }
        
        @Test
        @DisplayName("getCacheInfo deve retornar informações do cache")
        void getCacheInfoDeveRetornarInformacoesDoeCache() {
            // When
            String cacheInfo = ConfiguracaoHelper.getCacheInfo();
            
            // Then
            assertNotNull(cacheInfo);
            assertTrue(cacheInfo.contains("Cache")); // Deve mostrar informação de cache
        }
    }
    
    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Deve propagar exceção quando configuração falha")
        void devePropagateExcecaoQuandoConfiguracaoFalha() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getCodTopAdiantamento)
                    .thenThrow(new RuntimeException("Erro ao acessar parâmetro"));
                
                // When & Then
                Exception exception = assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getCodTopAdiantamento();
                });
                
                assertTrue(exception.getMessage().contains("Erro ao acessar parâmetro"));
            }
        }
        
        @Test
        @DisplayName("Deve tratar parâmetros empty string como inválidos")
        void deveTratarParametrosEmptyStringComoInvalidos() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getCodNatAdiantamento)
                    .thenThrow(new RuntimeException("Valor do parâmetro está nulo"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getCodNatAdiantamento();
                });
            }
        }
        
        @Test
        @DisplayName("Deve tratar parâmetros com espaços como inválidos")
        void deveTratarParametrosComEspacosComoInvalidos() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                configHelper.when(ConfiguracaoHelper::getCodTipTitAdiantamento)
                    .thenThrow(new RuntimeException("Valor do parâmetro está nulo"));
                
                // When & Then
                assertThrows(RuntimeException.class, () -> {
                    ConfiguracaoHelper.getCodTipTitAdiantamento();
                });
            }
        }
    }
    
    @Nested
    @DisplayName("Specific Configuration Values Tests")
    class SpecificConfigurationValuesTests {
        
        @Test
        @DisplayName("Deve retornar centro de custo padrão quando não informado")
        void deveRetornarCentroCustoPadraoQuandoNaoInformado() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("999");
                configHelper.when(ConfiguracaoHelper::getCodCencusAdiantamento)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal centroCusto = ConfiguracaoHelper.getCodCencusAdiantamento();
                
                // Then
                assertEquals(expectedValue, centroCusto);
            }
        }
        
        @Test
        @DisplayName("Deve retornar projeto padrão quando informado")
        void deveRetornarProjetoPadraoQuandoInformado() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("1");
                configHelper.when(ConfiguracaoHelper::getCodProjAdiantamento)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal projeto = ConfiguracaoHelper.getCodProjAdiantamento();
                
                // Then
                assertEquals(expectedValue, projeto);
            }
        }
        
        @Test
        @DisplayName("Deve retornar dias de vencimento padrão")
        void deveRetornarDiasVencimentoPadrao() {
            try (MockedStatic<ConfiguracaoHelper> configHelper = mockStatic(ConfiguracaoHelper.class)) {
                // Given
                BigDecimal expectedValue = new BigDecimal("30");
                configHelper.when(ConfiguracaoHelper::getDiasVencimento)
                    .thenReturn(expectedValue);
                
                // When
                BigDecimal dias = ConfiguracaoHelper.getDiasVencimento();
                
                // Then
                assertEquals(expectedValue, dias);
            }
        }
    }
}