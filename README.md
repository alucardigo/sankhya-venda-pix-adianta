# VendaPixAdianta - Modulo Sankhya ERP

Modulo de adiantamento automatico para vendas PIX no Sankhya ERP. Cria pares receita/despesa no financeiro (TGFFIN) quando uma venda PIX eh confirmada, com processamento assincrono e regras de negocio para controle de baixa.

## Arquitetura

```
VendaPixAdiantaEvent (TGFCAB afterUpdate)
  |
  +--> Detecta venda PIX confirmada (AD_GERAADIANT em TGFTPV + TGFTOP)
  +--> AsyncAdiantamentoProcessor (pool de 3 threads)
         |
         +--> AdiantamentoService.criarAdiantamentoParaVenda()
                |
                +--> AdiantamentoEmprestimoHelper (API Sankhya)
                +--> Cria Despesa (RECDESP=-1) + Receita (RECDESP=1)
                +--> Grava AD_NUNOTAADIANT na TGFFIN

RegraBaixaAdiantamentoConfirmacao (Regra 13 - Confirmacao)
  |
  +--> Detecta contexto PIX (flags + fallback por TGFFIN real)
  +--> Bloqueia se adiantamento nao esta baixado
  +--> Evento de liberacao: 1004 (Pagamento Antecipado PIX)

DespesaBaixaControlEvent (TGFFIN beforeUpdate)
  |
  +--> Impede baixa da despesa se receita nao foi paga
```

## Componentes

| Classe | Responsabilidade |
|--------|-----------------|
| `VendaPixAdiantaEvent` | Evento TGFCAB - detecta vendas PIX e dispara criacao |
| `AsyncAdiantamentoProcessor` | Pool assincrono com retry e fallback sincrono |
| `AdiantamentoService` | Criacao do par receita/despesa via API Sankhya |
| `RegraBaixaAdiantamentoConfirmacao` | Regra de negocio que bloqueia confirmacao sem baixa |
| `DespesaBaixaControlEvent` | Controle Phase 3 - impede baixa sem pagamento |
| `ConfiguracaoHelper` | Cache TTL para parametros da AD_TGFCAA |
| `CancelamentoHelper` | Cancelamento de adiantamentos quando nota eh cancelada |

## Configuracao

### Tabela AD_TGFCAA (parametros por empresa)

| Campo | Descricao |
|-------|-----------|
| CODEMP | Codigo da empresa |
| CODTIPOPER | Tipo de operacao do adiantamento |
| CODTIPTIT | Tipo de titulo |
| CODCTABCOINT | Conta bancaria |
| CODNAT | Natureza financeira |
| CODCENCUS | Centro de custo |
| CODPROJ | Projeto |

### Flags AD_GERAADIANT

Para ativar o fluxo de adiantamento PIX em um tipo de venda:

1. **TGFTPV** (Tipo de Venda): campo `AD_GERAADIANT = 'S'`
2. **TGFTOP** (Tipo de Operacao): campo `AD_GERAADIANT = 'S'`

Ambos precisam estar `'S'` para a nota gerar adiantamento.

### Regra de Negocio (Sankhya)

- Regra numero: 13
- Descricao: BAIXA ADIANTAMENTO PIX
- Tipo: Rotina Java
- Onde: Portais
- Quando: Confirmacao
- Evento de liberacao: 1004 (Pagamento Antecipado PIX)
- Modulo: 15 (VENDAPIXADIANTA)
- Classe: `br.com.bellube.sankhya.eventos.VendaPixAdianta.rules.RegraBaixaAdiantamentoConfirmacao`

## Build

```powershell
# Requer JDK 1.8 e dependencias Sankhya (SankhyaW-extensions.jar, jape.jar, mge-modelcore.jar)
powershell -ExecutionPolicy Bypass -File build-VendaPixAdianta.ps1
```

Saida: `dist/VendaPixAdianta.jar`

## Deploy

1. Copiar `VendaPixAdianta.jar` para o classpath do WildFly/Sankhya
2. Copiar `SankhyaW-extensions.jar` atualizado (regra de negocio)
3. Reiniciar o servico

## Requisitos

- JDK 1.8+
- Sankhya ERP com WildFly
- SQL Server 2016+
- Campos customizados: `AD_GERAADIANT` (TGFTPV, TGFTOP), `AD_NUNOTAADIANT` (TGFFIN), tabela `AD_TGFCAA`
