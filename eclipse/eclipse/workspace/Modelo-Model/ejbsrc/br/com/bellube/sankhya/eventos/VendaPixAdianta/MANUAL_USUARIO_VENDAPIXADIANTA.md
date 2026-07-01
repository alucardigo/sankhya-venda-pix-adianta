# Manual do Usuário - Módulo VendaPixAdianta

## Automação de Adiantamentos para Vendas PIX no Sankhya OM

**Versão:** 1.0  
**Data:** Agosto/2025  
**Sistema:** Sankhya OM  

---

## 📋 Índice

1. [Visão Geral](#visão-geral)
2. [Funcionalidades](#funcionalidades)
3. [Requisitos do Sistema](#requisitos-do-sistema)
4. [Instalação e Configuração](#instalação-e-configuração)
5. [Configuração de Parâmetros](#configuração-de-parâmetros)
6. [Como Usar](#como-usar)
7. [Monitoramento e Auditoria](#monitoramento-e-auditoria)
8. [Solução de Problemas](#solução-de-problemas)
9. [Perguntas Frequentes](#perguntas-frequentes)
10. [Suporte Técnico](#suporte-técnico)

---

## 🎯 Visão Geral

O **VendaPixAdianta** é um módulo de automação desenvolvido especificamente para o Sankhya OM que **automatiza a criação de adiantamentos financeiros** sempre que uma venda é realizada com pagamento via PIX.

### O que o módulo faz?

1. **Monitora** todas as vendas inseridas no sistema
2. **Identifica** automaticamente vendas pagas via PIX
3. **Cria** automaticamente um adiantamento financeiro correspondente
4. **Registra** todas as operações para auditoria e controle

### Benefícios

✅ **Automação Completa**: Elimina a necessidade de criar adiantamentos manualmente  
✅ **Processamento Assíncrono**: Não afeta a performance das vendas  
✅ **Auditoria Total**: Registra todas as operações e erros  
✅ **Configuração Flexível**: Adapta-se às regras de cada empresa  
✅ **Integração Nativa**: Usa os mesmos controles e validações do Sankhya  

---

## ⚙️ Funcionalidades

### 🔄 Processamento Automático
- Detecção automática de vendas PIX
- Criação de adiantamentos em segundo plano
- Processamento assíncrono para alta performance

### 🛠️ Configuração Avançada
- Todos os parâmetros configuráveis via sistema
- Controle de ativação/desativação
- Personalização de códigos financeiros

### 📊 Auditoria e Controle
- Log completo de todas as operações
- Registro detalhado de erros
- Relatórios de monitoramento

### 🔐 Segurança e Confiabilidade
- Integração nativa com controles do Sankhya
- Validações automáticas de regras de negócio
- Tratamento robusto de erros

---

## 💻 Requisitos do Sistema

### Sistema Operacional
- Windows Server 2012 ou superior
- Linux (distribuições suportadas pelo Sankhya OM)

### Sankhya OM
- Versão mínima: Sankhya W 4.0
- Módulos necessários: Financeiro, Vendas
- Permissões: Administrador do Sistema

### Recursos do Servidor
- RAM mínima: 4GB adicional
- Processamento: Suporta 3 threads simultâneas
- Espaço em disco: 50MB para logs de auditoria

---

## 🚀 Instalação e Configuração

### Passo 1: Preparação do Ambiente

1. **Faça backup completo** do sistema Sankhya
2. **Verifique permissões** de administrador
3. **Confirme** que os módulos Financeiro e Vendas estão ativos

### Passo 2: Instalação do Módulo

1. **Deploy do JAR**:
   ```
   Copie o arquivo VendaPixAdianta.jar para:
   [SANKHYA_HOME]/standalone/deployments/sankhyaw.ear/lib/
   ```

2. **Restart do Servidor**:
   - Pare o serviço do Sankhya
   - Aguarde 30 segundos
   - Inicie o serviço novamente

### Passo 3: Configuração do Banco de Dados

Execute o seguinte script SQL no seu banco de dados:

```sql
-- Criação da tabela de auditoria
CREATE TABLE AD_LOGVENDAPIXADI (
    NUNOTA NUMERIC(10) NOT NULL,
    DHEXEC TIMESTAMP NOT NULL,
    STATUS VARCHAR(10) NOT NULL,
    MENSAGEM VARCHAR(4000),
    STACKTRACE CLOB,
    CONSTRAINT PK_AD_LOGVENDAPIXADI PRIMARY KEY (NUNOTA)
);

-- Comentários da tabela
COMMENT ON TABLE AD_LOGVENDAPIXADI IS 'Log de auditoria para processamento de adiantamentos PIX';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.NUNOTA IS 'Número único da nota de venda';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.DHEXEC IS 'Data/hora da execução';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.STATUS IS 'Status: SUCCESS ou ERROR';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.MENSAGEM IS 'Mensagem de resultado ou erro';
COMMENT ON COLUMN AD_LOGVENDAPIXADI.STACKTRACE IS 'Stack trace completo em caso de erro';
```

### Passo 4: Registro do Evento Programável

1. Acesse **Sistema → Eventos → Eventos Programáveis**
2. Clique em **Incluir**
3. Preencha os campos:
   - **Entidade**: `CabecalhoNota`
   - **Tipo de Evento**: `afterInsert`
   - **Classe Java**: `br.com.bellube.sankhya.eventos.VendaPixAdianta.event.VendaPixAdiantaEvent`
   - **Ativo**: ✅ Sim
4. Clique em **Confirmar**

---

## ⚙️ Configuração de Parâmetros

O módulo utiliza parâmetros do sistema (TSIPAR) para configuração. Acesse **Sistema → Parâmetros do Sistema** e configure:

### Parâmetros Obrigatórios

| Parâmetro | Tipo | Descrição | Exemplo |
|-----------|------|-----------|---------|
| `VENDAPIX.EVENTO.ATIVO` | Lógico | Ativa/desativa a automação | S |
| `VENDAPIX.CODTIPTITPIX` | Inteiro | Código do tipo de título PIX | 17 |
| `VENDAPIX.CODTOP` | Inteiro | Tipo de operação para adiantamentos | 501 |
| `VENDAPIX.CODNAT` | Inteiro | Código da natureza financeira | 10101 |
| `VENDAPIX.CODTIPTIT` | Inteiro | Tipo de título do adiantamento | 999 |
| `VENDAPIX.CODCTABCOINT` | Inteiro | Conta bancária padrão | 1 |
| `VENDAPIX.DIASVENC` | Inteiro | Dias para vencimento | 30 |

### Parâmetros Opcionais

| Parâmetro | Tipo | Descrição | Padrão |
|-----------|------|-----------|--------|
| `VENDAPIX.CODCENCUS` | Inteiro | Centro de custo padrão | Usa da venda |
| `VENDAPIX.CODPROJ` | Inteiro | Projeto padrão | 0 |

### Script SQL para Configuração Inicial

```sql
-- Parâmetros obrigatórios
INSERT INTO TSIPAR (CHAVE, LOGICO, ATIVO) VALUES ('VENDAPIX.EVENTO.ATIVO', 'S', 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODTIPTITPIX', 17, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODTOP', 501, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODNAT', 10101, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODTIPTIT', 999, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODCTABCOINT', 1, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.DIASVENC', 30, 'S');

-- Parâmetros opcionais
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODCENCUS', 1001, 'S');
INSERT INTO TSIPAR (CHAVE, INTEIRO, ATIVO) VALUES ('VENDAPIX.CODPROJ', 0, 'S');
```

---

## 📱 Como Usar

### Operação Normal

O módulo funciona de forma **completamente automática**. Uma vez configurado:

1. **Vendas PIX são criadas** normalmente no sistema
2. **O módulo identifica automaticamente** vendas com o código de título PIX configurado
3. **Cria automaticamente** o adiantamento correspondente
4. **Registra o resultado** na tabela de auditoria

### Fluxo do Processo

```
📋 Venda PIX Criada
    ↓
🔍 Sistema Identifica PIX (CODTIPTIT = valor configurado)
    ↓
⚡ Processa em Segundo Plano (Assíncrono)
    ↓
💰 Cria Adiantamento Automaticamente
    ↓
📝 Registra Log de Auditoria
```

### Exemplo Prático

**Cenário**: Venda de R$ 1.500,00 paga via PIX

1. **Venda é inserida** com `CODTIPTIT = 17` (PIX)
2. **Sistema detecta** que 17 é o código PIX configurado
3. **Cria automaticamente** um adiantamento de R$ 1.500,00
4. **Define vencimento** para 30 dias após a venda (configurável)
5. **Registra sucesso** na tabela `AD_LOGVENDAPIXADI`

---

## 📊 Monitoramento e Auditoria

### Consulta de Operações Bem-sucedidas

```sql
SELECT 
    NUNOTA,
    DHEXEC,
    STATUS,
    MENSAGEM
FROM AD_LOGVENDAPIXADI 
WHERE STATUS = 'SUCCESS' 
ORDER BY DHEXEC DESC;
```

### Consulta de Erros

```sql
SELECT 
    NUNOTA,
    DHEXEC,
    MENSAGEM,
    STACKTRACE
FROM AD_LOGVENDAPIXADI 
WHERE STATUS = 'ERROR' 
ORDER BY DHEXEC DESC;
```

### Dashboard de Monitoramento

Crie uma consulta personalizada para acompanhar as estatísticas:

```sql
SELECT 
    STATUS,
    COUNT(*) as QUANTIDADE,
    DATE(DHEXEC) as DATA
FROM AD_LOGVENDAPIXADI
WHERE DHEXEC >= CURRENT_DATE - 7  -- Últimos 7 dias
GROUP BY STATUS, DATE(DHEXEC)
ORDER BY DATA DESC, STATUS;
```

### Relatório de Performance

```sql
SELECT 
    DATE(DHEXEC) as DATA,
    COUNT(CASE WHEN STATUS = 'SUCCESS' THEN 1 END) as SUCESSOS,
    COUNT(CASE WHEN STATUS = 'ERROR' THEN 1 END) as ERROS,
    ROUND(
        COUNT(CASE WHEN STATUS = 'SUCCESS' THEN 1 END) * 100.0 / 
        COUNT(*), 2
    ) as TAXA_SUCESSO_PERCENT
FROM AD_LOGVENDAPIXADI
WHERE DHEXEC >= CURRENT_DATE - 30  -- Últimos 30 dias
GROUP BY DATE(DHEXEC)
ORDER BY DATA DESC;
```

---

## 🛠️ Solução de Problemas

### Problema: Nenhum Adiantamento é Criado

**Possíveis Causas**:
- Parâmetro `VENDAPIX.EVENTO.ATIVO` está como 'N'
- Código PIX incorreto em `VENDAPIX.CODTIPTITPIX`
- Evento programável não foi registrado

**Solução**:
1. Verifique se `VENDAPIX.EVENTO.ATIVO = 'S'`
2. Confirme que vendas PIX têm `CODTIPTIT` igual ao configurado
3. Verifique se evento programável está ativo

### Problema: Erros na Criação de Adiantamentos

**Verificação**:
```sql
SELECT * FROM AD_LOGVENDAPIXADI WHERE STATUS = 'ERROR' ORDER BY DHEXEC DESC;
```

**Possíveis Causas**:
- Códigos de configuração inativos ou inexistentes
- Problemas de limite de crédito
- Natureza financeira incorreta

**Solução**:
1. Verifique se todos os códigos configurados existem e estão ativos
2. Confirme regras de crédito do parceiro
3. Valide configuração da natureza financeira

### Problema: Performance Lenta

**Verificação**:
- Monitor de threads do servidor
- Logs de aplicação do Sankhya

**Solução**:
1. O módulo usa 3 threads por padrão
2. Ajuste pode ser feito no código se necessário
3. Monitore uso de CPU e memória

### Problema: Tabela de Log Muito Grande

**Manutenção**:
```sql
-- Arquivar logs antigos (mais de 3 meses)
DELETE FROM AD_LOGVENDAPIXADI 
WHERE DHEXEC < CURRENT_DATE - 90;
```

---

## ❓ Perguntas Frequentes

### 1. O módulo afeta a performance das vendas?
**Resposta**: Não. O processamento é assíncrono, ou seja, a venda é salva normalmente e o adiantamento é criado em segundo plano.

### 2. O que acontece se o módulo estiver desligado?
**Resposta**: As vendas funcionam normalmente, mas os adiantamentos não são criados automaticamente.

### 3. Posso alterar os parâmetros com o sistema rodando?
**Resposta**: Sim, mas as alterações só têm efeito após um tempo (cache interno do módulo).

### 4. O módulo funciona para outros tipos de pagamento?
**Resposta**: Não, apenas para vendas identificadas com o código PIX configurado.

### 5. Posso desinstalar o módulo?
**Resposta**: Sim, removendo o JAR e o evento programável. Os adiantamentos já criados permanecem.

### 6. Como identifico se uma venda vai gerar adiantamento?
**Resposta**: Verifique se o `CODTIPTIT` da venda é igual ao configurado em `VENDAPIX.CODTIPTITPIX`.

### 7. O módulo cria duplicatas se eu reprocessar uma venda?
**Resposta**: Não, o evento só dispara na inserção inicial da venda.

### 8. Posso personalizar o histórico do adiantamento?
**Resposta**: O histórico é padronizado: "Adiantamento ref. Venda PIX NUNOTA: [número]".

---

## 📞 Suporte Técnico

### Informações para Contato

**Desenvolvedor**: Bellube Sistemas  
**Módulo**: VendaPixAdianta v1.0  
**Compatibilidade**: Sankhya OM 4.0+  

### Ao Solicitar Suporte

Inclua sempre as seguintes informações:

1. **Versão do Sankhya OM**
2. **Mensagem de erro** (se aplicável)
3. **Configuração dos parâmetros** VENDAPIX.*
4. **Log da tabela** AD_LOGVENDAPIXADI
5. **Exemplo da venda** que apresentou problema

### Logs Úteis

1. **Log de Auditoria do Módulo**:
```sql
SELECT * FROM AD_LOGVENDAPIXADI ORDER BY DHEXEC DESC LIMIT 10;
```

2. **Configuração de Parâmetros**:
```sql
SELECT * FROM TSIPAR WHERE CHAVE LIKE 'VENDAPIX.%';
```

3. **Status do Evento Programável**:
Verifique em Sistema → Eventos → Eventos Programáveis

---

## 📝 Notas da Versão

**v1.0 - Agosto/2025**
- ✅ Lançamento inicial
- ✅ Processamento assíncrono com 3 threads
- ✅ Configuração completa via TSIPAR
- ✅ Auditoria completa de operações
- ✅ Integração nativa com Sankhya OM
- ✅ Validações de regras de negócio
- ✅ Tratamento robusto de erros

---

## 📄 Licença e Garantia

Este módulo é fornecido "como está", sem garantias expressas ou implícitas. O uso é por conta e risco do usuário final. Recomenda-se sempre testar em ambiente de homologação antes de usar em produção.

**Copyright © 2025 Bellube Sistemas. Todos os direitos reservados.**

---

*Este documento foi gerado automaticamente pela ferramenta de desenvolvimento Junie AI para o módulo VendaPixAdianta. Para atualizações, consulte a documentação técnica ou entre em contato com o suporte.*