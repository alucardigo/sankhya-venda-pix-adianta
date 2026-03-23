/**
 * Sistema de Mensagens PIX para Sankhya ERP
 * 
 * Este sistema faz polling constante para verificar se há mensagens JavaScript
 * pendentes do backend e as executa no navegador para exibir notificações
 * estilizadas sobre o processamento de adiantamentos PIX.
 * 
 * COMO USAR:
 * 1. Inclua este arquivo no seu formulário Sankhya
 * 2. Chame inicializarSistemaMensagensPix() no evento onLoad do formulário
 * 3. As mensagens aparecerão automaticamente quando adiantamentos forem processados
 */

// Namespace para evitar conflitos
var VendaPixMessageSystem = VendaPixMessageSystem || {};

/**
 * Configurações do sistema de mensagens
 */
VendaPixMessageSystem.config = {
    pollingInterval: 2000,  // Intervalo de polling em milissegundos (2 segundos)
    messageTimeout: 15000,  // Tempo para auto-remover mensagens (15 segundos)
    enabled: true           // Flag para ativar/desativar o sistema
};

/**
 * Inicializa o sistema de mensagens PIX
 */
VendaPixMessageSystem.inicializar = function() {
    console.log("[VendaPixMessageSystem] Inicializando sistema de mensagens PIX...");
    
    if (!this.config.enabled) {
        console.log("[VendaPixMessageSystem] Sistema desabilitado por configuração");
        return;
    }
    
    // Iniciar polling para verificar mensagens
    this.iniciarPolling();
    
    // Executar verificação imediatamente após carregar
    setTimeout(function() {
        VendaPixMessageSystem.verificarTodasMensagens();
    }, 1000);
    
    console.log("[VendaPixMessageSystem] Sistema iniciado com sucesso!");
};

/**
 * Inicia o polling constante para verificar mensagens
 */
VendaPixMessageSystem.iniciarPolling = function() {
    if (this.pollingInterval) {
        clearInterval(this.pollingInterval);
    }
    
    this.pollingInterval = setInterval(function() {
        VendaPixMessageSystem.verificarTodasMensagens();
    }, this.config.pollingInterval);
    
    console.log("[VendaPixMessageSystem] Polling iniciado com intervalo de " + 
                this.config.pollingInterval + "ms");
};

/**
 * Para o polling (útil para debugging ou desativação temporária)
 */
VendaPixMessageSystem.pararPolling = function() {
    if (this.pollingInterval) {
        clearInterval(this.pollingInterval);
        this.pollingInterval = null;
        console.log("[VendaPixMessageSystem] Polling parado");
    }
};

/**
 * Função principal que verifica todas as fontes de mensagens
 */
VendaPixMessageSystem.verificarTodasMensagens = function() {
    try {
        this.verificarSystemProperties();
        this.verificarSankhyaContext();
        this.verificarLocalStorage();
    } catch (e) {
        console.error("[VendaPixMessageSystem] Erro na verificação de mensagens: " + e.message);
    }
};

/**
 * Verifica mensagens via System Properties (se disponível)
 */
VendaPixMessageSystem.verificarSystemProperties = function() {
    try {
        if (typeof java !== 'undefined' && java.lang && java.lang.System) {
            var msgSucesso = java.lang.System.getProperty("browser.message.sucesso");
            var msgErro = java.lang.System.getProperty("browser.message.erro");
            
            if (msgSucesso) {
                console.log("[VendaPixMessageSystem] Mensagem de sucesso encontrada via System Properties");
                this.executarJavaScript(msgSucesso);
                java.lang.System.clearProperty("browser.message.sucesso");
            }
            
            if (msgErro) {
                console.log("[VendaPixMessageSystem] Mensagem de erro encontrada via System Properties");
                this.executarJavaScript(msgErro);
                java.lang.System.clearProperty("browser.message.erro");
            }
        }
    } catch (e) {
        // Silenciar erros de System Properties pois podem não estar disponíveis
        console.debug("[VendaPixMessageSystem] System Properties não disponível: " + e.message);
    }
};

/**
 * Verifica mensagens via contexto do Sankhya (se disponível)
 */
VendaPixMessageSystem.verificarSankhyaContext = function() {
    try {
        // Tentar acessar o contexto global do Sankhya
        if (typeof window.sankhyaContext !== 'undefined') {
            var msgSucesso = window.sankhyaContext.getProperty("BROWSER_MESSAGE_SUCESSO");
            var msgErro = window.sankhyaContext.getProperty("BROWSER_MESSAGE_ERRO");
            
            if (msgSucesso) {
                console.log("[VendaPixMessageSystem] Mensagem de sucesso encontrada via Sankhya Context");
                this.executarJavaScript(msgSucesso);
                window.sankhyaContext.removeProperty("BROWSER_MESSAGE_SUCESSO");
            }
            
            if (msgErro) {
                console.log("[VendaPixMessageSystem] Mensagem de erro encontrada via Sankhya Context");
                this.executarJavaScript(msgErro);
                window.sankhyaContext.removeProperty("BROWSER_MESSAGE_ERRO");
            }
        }
    } catch (e) {
        console.debug("[VendaPixMessageSystem] Sankhya Context não disponível: " + e.message);
    }
};

/**
 * Verifica mensagens via localStorage (fallback)
 */
VendaPixMessageSystem.verificarLocalStorage = function() {
    try {
        if (typeof localStorage !== 'undefined') {
            var msgSucesso = localStorage.getItem("BROWSER_MESSAGE_SUCESSO");
            var msgErro = localStorage.getItem("BROWSER_MESSAGE_ERRO");
            
            if (msgSucesso) {
                console.log("[VendaPixMessageSystem] Mensagem de sucesso encontrada via localStorage");
                this.executarJavaScript(msgSucesso);
                localStorage.removeItem("BROWSER_MESSAGE_SUCESSO");
            }
            
            if (msgErro) {
                console.log("[VendaPixMessageSystem] Mensagem de erro encontrada via localStorage");
                this.executarJavaScript(msgErro);
                localStorage.removeItem("BROWSER_MESSAGE_ERRO");
            }
        }
    } catch (e) {
        console.debug("[VendaPixMessageSystem] localStorage não disponível: " + e.message);
    }
};

/**
 * Executa o código JavaScript de forma segura
 */
VendaPixMessageSystem.executarJavaScript = function(jsCode) {
    try {
        if (jsCode && jsCode.trim().length > 0) {
            console.log("[VendaPixMessageSystem] Executando JavaScript de mensagem");
            eval(jsCode);
        }
    } catch (e) {
        console.error("[VendaPixMessageSystem] Erro ao executar JavaScript: " + e.message);
        // Fallback: usar alert simples se JavaScript falhar
        alert("Mensagem PIX: Erro ao exibir notificação personalizada");
    }
};

/**
 * Cria uma mensagem de teste para verificar se o sistema está funcionando
 */
VendaPixMessageSystem.testarMensagem = function(tipo) {
    tipo = tipo || "SUCESSO";
    
    var mensagemTeste = tipo === "SUCESSO" ? 
        "🧪 <strong>TESTE DE MENSAGEM DE SUCESSO</strong><br><br>" +
        "Este é um teste do sistema de notificações PIX.<br>" +
        "Se você está vendo esta mensagem, o sistema está funcionando corretamente!" :
        "🧪 <strong>TESTE DE MENSAGEM DE ERRO</strong><br><br>" +
        "Este é um teste do sistema de notificações PIX.<br>" +
        "Esta é uma mensagem de erro de teste.";
    
    var jsCode = this.gerarJavaScriptMensagem(tipo, mensagemTeste);
    this.executarJavaScript(jsCode);
};

/**
 * Gera código JavaScript para exibir mensagem (similar ao BrowserMessageHelper)
 */
VendaPixMessageSystem.gerarJavaScriptMensagem = function(tipo, mensagem) {
    var cor = tipo === "SUCESSO" ? "#4CAF50" : "#f44336";
    var mensagemFormatada = mensagem.replace(/'/g, "\\'").replace(/\n/g, "\\n").replace(/\r/g, "\\r");
    
    return "(function() {" +
           "    try {" +
           "        var div = document.createElement('div');" +
           "        div.style.cssText = '" +
           "            position: fixed;" +
           "            top: 20px;" +
           "            right: 20px;" +
           "            background: " + cor + ";" +
           "            color: white;" +
           "            padding: 20px;" +
           "            border-radius: 10px;" +
           "            box-shadow: 0 4px 8px rgba(0,0,0,0.2);" +
           "            z-index: 999999;" +
           "            max-width: 500px;" +
           "            font-family: Arial, sans-serif;" +
           "            font-size: 14px;" +
           "            line-height: 1.5;" +
           "            word-wrap: break-word;" +
           "        ';" +
           "        div.innerHTML = '" + mensagemFormatada + "';" +
           "        var closeButton = document.createElement('button');" +
           "        closeButton.textContent = '×';" +
           "        closeButton.style.cssText = '" +
           "            position: absolute;" +
           "            top: 5px;" +
           "            right: 10px;" +
           "            background: none;" +
           "            border: none;" +
           "            color: white;" +
           "            font-size: 20px;" +
           "            cursor: pointer;" +
           "            padding: 0;" +
           "            width: 25px;" +
           "            height: 25px;" +
           "        ';" +
           "        closeButton.onclick = function() { document.body.removeChild(div); };" +
           "        div.appendChild(closeButton);" +
           "        document.body.appendChild(div);" +
           "        setTimeout(function() {" +
           "            if (div.parentNode) { document.body.removeChild(div); }" +
           "        }, " + this.config.messageTimeout + ");" +
           "        console.log('Mensagem " + tipo + " exibida no navegador');" +
           "    } catch(e) {" +
           "        console.error('Erro ao exibir mensagem: ' + e.message);" +
           "        alert('" + mensagemFormatada + "');" +
           "    }" +
           "})();";
};

/**
 * Função global para inicializar (compatibilidade com versões antigas)
 */
function inicializarSistemaMensagensPix() {
    VendaPixMessageSystem.inicializar();
}

/**
 * Auto-inicialização quando o DOM estiver pronto
 */
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
        VendaPixMessageSystem.inicializar();
    });
} else {
    // DOM já carregado, inicializar imediatamente
    VendaPixMessageSystem.inicializar();
}

// Expor no escopo global para uso em formulários Sankhya
window.VendaPixMessageSystem = VendaPixMessageSystem;