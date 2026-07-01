# HANDOVER — Módulo VendaPixAdianta (continuidade para devs)

> Guia **operacional** de continuidade: build, deploy, acesso, gotchas e troubleshooting.
> Para arquitetura detalhada e ADRs, ver [`DOCUMENTACAO_TECNICA.md`](DOCUMENTACAO_TECNICA.md) e
> [`ARCHITECTURAL_DOCUMENTATION.md`](ARCHITECTURAL_DOCUMENTATION.md). Para uso operacional, ver
> [`MANUAL_USUARIO_VENDAPIXADIANTA.md`](MANUAL_USUARIO_VENDAPIXADIANTA.md).
> Última atualização: 2026-06-26 (build `2026-06-26-BOTAO-PIX-AUTHINFO-V16.3`).

---

## 1. O que este módulo faz (1 parágrafo)

Customização Sankhya-OM (`br.com.bellube.sankhya.eventos.VendaPixAdianta`) para a **Bel Lube**.
Quando uma **venda PIX** é confirmada no ERP (Tipo de Negociação `500 = BOLETO PIX` + Tipo de
Operação habilitado), o módulo **cria automaticamente um Adiantamento/Empréstimo** (2 títulos em
TGFFIN: despesa + receita) e **gera o boleto bancário Itaú** via API nativa do Sankhya. Também
**cancela/desfaz** o adiantamento e **baixa o boleto no Itaú** quando o pedido é cancelado, marcado
não-pendente, ou fica órfão (reconciliação). Tudo assíncrono e idempotente. ~27 classes Java, ~8k linhas.

## 2. Ambiente de build

- **JDK 8** obrigatório (o WildFly de produção roda `1.8.0_231`; compilar em 8 evita incompatibilidade de bytecode). O script procura `C:\sk-java\jdk1.8.0_121` ou usa `JAVA_HOME`.
- Fontes: `X:\system_apps\sk-java\eclipse\eclipse\workspace\Modelo-Model\ejbsrc\...` (este diretório).
- Script de build: `C:\sk-java\build-VendaPixAdianta.ps1` (lista **explícita** de fontes — ao criar uma classe nova, **adicione-a à lista** `$sourceFiles`, senão não entra no JAR).

### Build

```powershell
cd C:\sk-java
powershell -ExecutionPolicy Bypass -File .\build-VendaPixAdianta.ps1
```

Gera **`X:\system_apps\sk-java\dist\VendaPixAdianta.jar`** (~112 KB).
> Ignore o `NoClassDefFoundError: javax/ejb/*` no fim do log — é o passo de *testes* pós-build tentando
> rodar classes que precisam do EJB no classpath; **não afeta o JAR** (procure `Compilation successful`
> e `VendaPixAdianta.jar created successfully`).

### Confirmar a versão compilada

Cada build carimba `BUILD_VERSION` em `event/VendaPixAdiantaEvent.java`. Para conferir no JAR:

```bash
unzip -p dist/VendaPixAdianta.jar br/com/bellube/sankhya/eventos/VendaPixAdianta/event/VendaPixAdiantaEvent.class \
  | tr -cd '\11\12\15\40-\176' | grep -oE "20[0-9]{2}-[0-9]{2}-[0-9]{2}-[A-Z0-9.-]+"
```

**Sempre suba o `BUILD_VERSION`** ao mudar código — é como se confirma no log de produção qual JAR está no ar
(`grep "Classloader carregou VendaPixAdiantaEvent - BUILD=" server.log`).

## 3. Deploy em produção

1. Substituir o JAR no WildFly de produção (mesmo local do anterior) e reiniciar/redeploy o módulo.
2. No startup, o `ReconciliacaoScheduler` (static block) sobe sozinho; o log deve mostrar
   `*** Classloader carregou VendaPixAdiantaEvent - BUILD=<versão> ***`.
3. **Botões de ação** (`AcaoRotinaJava`) precisam ser cadastrados/atualizados no *Dicionário de Dados*
   (ver §5). Só o JAR não cria o botão na tela.

### Acesso aos ambientes

> ⚠️ **Credenciais NÃO ficam neste repositório.** Ver o cofre de credenciais da equipe / notas internas.
> Resumo dos caminhos (sem segredos):
- **Produção — SQL Server** `sankhya_prod`: host `bellube-sql01-oci.cldns.top` (Linux/OCI). Acesso via SSH
  (usuário e senha no cofre da equipe) + `/opt/mssql-tools18/bin/sqlcmd -S localhost -d sankhya_prod` (flags `-C -N`).
  O nome **cru** `bellube-sql01-oci` não resolve — usar o sufixo `.cldns.top`.
- **Produção — App Server (WildFly)**: `bellube-app05.cldns.top` (porta SSH 2022).
- **Teste (sandbox isolado, production-like)**: `SANKHYA_TESTE` em `172.16.127.12` (via Tailscale
  `admin@100.72.97.11`). **Não** tem os dados de produção nem rota até a OCI — usar só para validar SQL com
  `BEGIN TRAN / ROLLBACK`.

## 4. Inventário rápido (com os componentes novos do Q2/2026)

| Camada | Classe | Função |
|---|---|---|
| `event/` | **VendaPixAdiantaEvent** | Entry point JAPE (`afterUpdate`/`afterDelete` de TGFCAB). Qualifica PIX, dispara criação, trata cancelamento. |
| `event/` | DespesaBaixaControlEvent | Controle de baixa de despesa. |
| `async/` | **AsyncAdiantamentoProcessor** | Fila + worker pool. `submitTask()` é o ponto único de disparo. Injeta `authInfo`/`usuario_logado` no contexto do worker. |
| `async/` | AdiantamentoTask | DTO imutável da venda (inclui `authInfo`). |
| `async/` | **ReconciliacaoScheduler** | Scheduler embedded (a cada 5 min) — safety net de órfãos + completar DELETE pós-baixa Itaú. Eleição de líder via System.property. |
| `service/` | **AdiantamentoService** | Cria os 2 títulos TGFFIN via `AdiantamentoEmprestimoHelper`. Re-valida o gate `AD_GERAADIANT`. |
| `service/` | **BoletoAutoService** | Geração automática do boleto (API Itaú). |
| `service/` | BoletoResult / NotificacaoService | Resultado tipado / notificações. |
| `util/` | **CancelamentoHelper** | **Coração do cancelamento** (~1.2k linhas). Bifurca CASO A (sem boleto → DELETE físico) e CASO B (com boleto → soft-cancel + baixa Itaú + polling + DELETE). Reconciliação de órfãos. |
| `util/` | **ConfiguracaoHelper** | Lê `AD_TGFCAA` (config por empresa) e o gate `verifyGeraAdiantamento(CODTIPVENDA, CODTIPOPER)`. |
| `action/` | **GerarBoletoAdiantamentoPixAction** ⭐novo | Botão manual: força geração de adiantamento+boleto p/ uma nota já confirmada que pulou o fluxo automático. |
| `action/` | **MarcarNaoPendenteAdiantamentoAction** ⭐novo | Botão que marca itens não-pendentes (substitui SP `AD_MARCA_NAO_PENDENTE`) + cancela adiantamento na mesma transação. |
| `action/` | ReconciliacaoAdiantamentoAction | Botão/rotina p/ varrer e cancelar órfãos manualmente. |
| `action/` | ImprimirBoletoAdiantamentoAction, BoletoRepository, BoletoPreviewService | Impressão/consulta de boleto. |

## 5. Os 3 botões de ação e como cadastrar

Todos implementam `AcaoRotinaJava`. Cadastrar em **Dicionário de Dados → TGFCAB → Ações**, tipo **Action Java**:

| Botão | Classe | Quando usar |
|---|---|---|
| **Gerar Boleto PIX** | `...action.GerarBoletoAdiantamentoPixAction` | Nota PIX confirmada que **não** gerou boleto (ex.: TOP não estava habilitada na hora). Dispara o pipeline sem reabrir/refaturar. |
| **Marcar Como Não Pendente** | `...action.MarcarNaoPendenteAdiantamentoAction` | Substitui a "Rotina no Banco de Dados" (SP). Marca não-pendente **e** desfaz o adiantamento na hora. |
| **Reconciliação Adiantamentos** | `...action.ReconciliacaoAdiantamentoAction` | Saneamento manual de órfãos (parâmetros opcionais `DIAS_LOOKBACK`, `GRACE_HORAS`). |

## 6. ⚠️ GOTCHAS críticos (leia ANTES de mexer)

Estes custaram muitas iterações. Ignorar = quebrar produção.

1. **`NUMNOTA` ≠ `NUMDUPL` na TGFFIN.** Em adiantamentos (`DESDOBDUPL='ZZ'`), o *desfazer* nativo e os DELETEs
   físicos usam **NUMDUPL** (= NUACERTO, o número que aparece na tela "Adiant./Emp."), **não** NUMNOTA. Passar
   NUMNOTA faz o DELETE não achar nada e o adiantamento fica órfão. Sempre descobrir NUMDUPL via
   `SELECT NUMDUPL FROM TGFFIN WHERE NUMNOTA=? AND DESDOBDUPL='ZZ'` antes.

2. **UPDATE/SP direto em SQL NÃO dispara evento JAPE.** `afterUpdate` só roda em alterações feitas pela camada
   de persistência do Sankhya (DWF/tela/serviço). Por isso o botão "Marcar Não Pendente" teve que virar
   `AcaoRotinaJava` (a SP não disparava o evento). E por isso não dá para "re-disparar" a geração via UPDATE no banco.

3. **O evento só cria adiantamento quando o campo `NUMNOTA` está entre os alterados** (atribuição da pré-nota no
   faturamento). Nota já faturada não re-dispara sozinha → use o botão **Gerar Boleto PIX** (ou reabra+refature).

4. **TGFTOP (e TGFTPV) são VERSIONADAS — PK = `(CODTIPOPER, DHALTER)`.** Cada Tipo de Operação tem N versões
   históricas; o gate lê a **versão atual = MAX(DHALTER)**. Marcar a flag `AD_GERAADIANT='S'` pela tela cria uma
   **versão nova** que pode vir com o campo NULL → "a flag não pega". Para habilitar de fato:
   ```sql
   UPDATE TGFTOP SET AD_GERAADIANT='S'
   WHERE CODTIPOPER=<top> AND DHALTER=(SELECT MAX(DHALTER) FROM TGFTOP WHERE CODTIPOPER=<top>);
   ```
   O gate `verifyGeraAdiantamento` exige **AD_GERAADIANT='S' na TPV (negociação) E na TOP (operação)** — é AND.

5. **Worker assíncrono NÃO tem contexto de autenticação.** O `FinanceiroListener` nativo exige `authInfo` ao
   salvar TGFFIN (`getRequiredProperty("authInfo")`). Toda task submetida ao `AsyncAdiantamentoProcessor` **deve
   carregar o `authInfo`** capturado na thread autenticada (evento: `JapeSessionContext.getProperty("authInfo")`;
   botão: idem, construtor de **7 args**). Sem isso → `RETRIES_EXHAUSTED` e o adiantamento não é criado (rollback limpo).

6. **Triggers SQL Server que bloqueiam:**
   - `TRG_INC_UPD_TGFFIN_MONIOCOREM` — bloqueia UPDATE `PROVISAO='S'` quando há cobrança registrada
     (`MONIOCOREM='S'` + TGFRAF TIPO='E'). Por isso o CASO B faz `MONIOCOREM='N'` **antes**.
   - `TRG_DLT_TGFFIN` — bloqueia DELETE quando `PROVISAO='N' AND DHBAIXA NOT NULL`. Por isso os DELETEs filtram
     `DHBAIXA IS NULL` e o soft-cancel seta `PROVISAO='S'` primeiro.

7. **Ciclo do boleto Itaú:** o registro/baixa passam por worker async nativo do Sankhya (queries
   `ApiBancosHelper_queBoletosHibridosRafEnviaHba.sql` etc.). A mensagem de tela *"será enviada após 24h por regra
   do banco"* é **enganosa** — a baixa costuma ser aceita em ~25–50s (resposta `status:-1` = cancelado). Não
   confie no texto; confira `TGFRAF STATUS='E'` / resposta da API no log.

8. **Race com `BoletoAutoService`:** ao cancelar, pode haver TGFRAF `TIPO='E' STATUS='A'` (registro pendente).
   O cancelamento faz `DELETE` desse pedido pendente (PASSO 0) para o worker não registrar o boleto após o cancel.

## 7. Playbook de troubleshooting

Método padrão de análise de log de produção (logs são grandes, ~300–500 MB):
```bash
# baixar o server.log_*.zip, extrair só o server.log e usar grep com nº de linha
unzip -o server.log_AAAAMMDD.zip server.log
grep -nE "NUNOTA=<nunota>|CODPARC=<codparc>" server.log
```

| Sintoma | Onde olhar | Causa provável / ação |
|---|---|---|
| **Boleto não gerou** p/ um pedido | `grep "afterUpdate.*NUNOTA=<n>"` | Ver `AD_GERAADIANT bloqueando` → TOP/TPV sem flag (gotcha #4). Habilitar na versão atual da TGFTOP e clicar **Gerar Boleto PIX**. |
| Botão diz **"não qualifica (TOP/TPV)"** | msg do botão + `verifyGeraAdiantamento` | Gate falhou. Conferir `TGFTOP/TGFTPV` MAX(DHALTER) = 'S' (gotcha #4). |
| Botão diz **"Geração disparada"** mas nada é criado | `grep "RETRIES_EXHAUSTED"` + stacktrace | `authInfo` ausente (gotcha #5). Garantir construtor 7-args. Rollback é limpo (sem lixo em TGFFIN). |
| **Adiantamento órfão** (some o pedido, fica o adiantamento) | `grep CancelamentoHelper` | Ver CASO A/B. NUMDUPL correto? (gotcha #1). Scheduler completa em ≤5 min. |
| Adiantamento **não some** após cancelar (CASO B) | `DIAG-DEPOIS` + TGFRAF | Boleto ainda em baixa no Itaú; DELETE físico só após `TGFRAF STATUS='E'`. |
| Scheduler não roda | `grep ReconciliacaoScheduler` | Ver heartbeat "Iniciando ciclo"; eleição de líder pode ter passado posse a outro deploy. |

## 8. Histórico de versões (resumo)

| Versão | O que resolveu |
|---|---|
| V13 | Botão Java `MarcarNaoPendente` substitui a SP (evento não disparava por SP). |
| V15 / V15.1 | Descoberta **NUMNOTA≠NUMDUPL**; cancelamento bifurcado CASO A (DELETE) / CASO B (soft-cancel + baixa Itaú). |
| V16 | CASO B com **polling síncrono 30s** + DELETE físico pós-baixa; safety-net no scheduler. |
| V16.1 | Botão Marcar-Não-Pendente: `CODUSU` real (`contexto.getUsuarioLogado()`) na TGFHIP; mensagem simplificada. |
| V16.2 | Novo botão **Gerar Boleto PIX** (força geração p/ nota já confirmada). |
| V16.3 | Fix: botão passa **`authInfo`** ao worker (evita `RETRIES_EXHAUSTED`). |

## 9. Riscos conhecidos / TODO

- **Constantes hardcoded** (ex.: `CODEMP=26`, `CODUSU_SISTEMA=376`) — ver §12 da DOCUMENTACAO_TECNICA.
- **Reincidência da TOP 448**: reeditar a TOP pela tela pode zerar `AD_GERAADIANT` de novo (gotcha #4). Correção
  definitiva = travar o canal externo/portal para usar a TOP PIX homologada (326/32), ou tornar a leitura do gate
  robusta a versões (ler MAX(DHALTER) já é o caso; o problema é a escrita pela tela).
- **Diretório de fontes poluído** com arquivos-lixo de sessões (fragmentos de shell, `token.txt`, `.claude/` de
  outro projeto). Não versionar; limpar quando possível.
- Auditoria via `System.out` (não logger dedicado) — ver ADR-008.
