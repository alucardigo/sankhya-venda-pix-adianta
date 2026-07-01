# Dossiê de PD&I — Lei do Bem (Lei nº 11.196/2005)
## Projeto: Automação do Ciclo de Adiantamento PIX + Boleto Bancário no ERP Sankhya
### Ano-base 2026 — Recorte do **2º Trimestre (abril a junho/2026)** — evidências e defesa técnica

> Documento **autônomo** para compor o dossiê fiscal (FORMP&D/DIRBI ciclo 2026). Elaborado sob a ótica de
> *desenvolvimento experimental* (OCDE/Frascati; art. 17 da Lei 11.196/2005; Decreto 5.798/2006). Foca nos
> **marcos técnicos do 2º trimestre de 2026**; para o histórico completo ver
> [`DOSSIE_LEI_DO_BEM_VENDAPIX.md`](DOSSIE_LEI_DO_BEM_VENDAPIX.md) e a documentação técnica do módulo.

---

## 1. Identificação

| Campo | Conteúdo |
|---|---|
| **Empresa** | Bel Lube (POSTO REDE BELS LTDA / BEL DISTRIBUIDOR DE LUBRIFICANTES LTDA — CODEMP 26) |
| **Projeto** | Motor de adiantamento PIX e ciclo de vida de boleto bancário sobre ERP Sankhya-OM |
| **Módulo/artefato** | `br.com.bellube.sankhya.eventos.VendaPixAdianta` (~27 classes, ~7.956 linhas Java) |
| **Natureza** | Desenvolvimento experimental de software corporativo (integração de baixo nível a ERP proprietário) |
| **Recorte deste dossiê** | 2º trimestre de 2026 (abr–jun): ciclos experimentais **V15.1 → V16.3** |
| **Regime** | Lucro Real (pré-requisito Lei do Bem) — *confirmar com a contabilidade* |
| **TRL no período** | 6 → 8 (protótipo validado em ambiente relevante → sistema em operação de produção) |

## 2. Fase 1 — Triagem de Elegibilidade (N1)

Questionário binário (uma resposta **SIM** invalidaria o projeto):

| # | Quesito eliminatório | Resposta | Justificativa |
|---|---|---|---|
| 1 | É predominantemente **rotina/operacional** (manutenção, parametrização, rollout sem experimentação)? | **NÃO** | Exigiu engenharia reversa de comportamento **não-documentado** do ERP e experimentação iterativa (12+ hipóteses testadas). |
| 2 | É mera **adequação normativa/compliance** sem desafio técnico? | **NÃO** | O desafio é técnico (concorrência, integração de baixo nível, ciclo bancário assíncrono), não regulatório. |
| 3 | É **solução de prateleira (COTS)** sem customização profunda? | **NÃO** | Extensão via SDK/JAPE do Sankhya com integração direta a helpers internos (bypass de camadas), inexistente no produto nativo. |
| 4 | Está **fora da janela de maturidade** (TRL<3 ou TRL>7 já estabilizado)? | **NÃO** | No trimestre o sistema evoluiu de protótipo validado (TRL 6) a operação (TRL 7–8), com incerteza técnica ativa. |
| 5 | Escopo **não-tecnológico** (marketing, financeiro, comercial puro)? | **NÃO** | Escopo é engenharia de software/integração de sistemas. |

**Veredito N1: ELEGÍVEL** — prosseguir para N2.

## 3. Fase 2 — Descrição das Atividades de PD&I (N2)

### 3.1. Objetivo tecnológico do trimestre

Tornar **determinístico e idempotente** o ciclo completo *venda PIX → adiantamento financeiro → boleto Itaú →
cancelamento/baixa → reconciliação de órfãos*, operando sobre um ERP cujo **modelo financeiro interno não é
documentado** e cujo comportamento (triggers, versionamento, eventos, contexto transacional) só é observável
empiricamente. A incerteza central: **não havia especificação de como o núcleo do ERP localiza, bloqueia,
versiona e autentica** as operações de adiantamento — cada hipótese precisou ser refutada ou confirmada por
experimento controlado.

### 3.2. Barreiras técnicas enfrentadas (incerteza → experimentação → resultado)

> *Barreira técnica ≠ desafio de gestão.* Todas abaixo são incertezas no domínio da engenharia aplicada,
> resolvidas por método experimental. Prazo/equipe/orçamento **não** figuram aqui.

**B1 — Modelo de identificação do adiantamento não-documentado (`NUMNOTA` ≠ `NUMDUPL`).**
- *Incerteza:* qual chave o *desfazer* nativo usa para localizar o adiantamento em TGFFIN. Os DELETEs por
  `NUMNOTA` não removiam nada e geravam **títulos financeiros órfãos** (risco financeiro real).
- *Experimentação:* decompilação dos JARs do núcleo (`mge-modelcore`), inspeção do `AdiantamentoEmprestimoHelper`,
  e testes transacionais em sandbox (`BEGIN TRAN / ROLLBACK`) contra base *production-like*.
- *Resultado:* descoberto que a chave correta é **`NUMDUPL` (= `NUACERTO` em TGFFRE)**. Reescrita da rotina de
  cancelamento para descobrir NUMDUPL antes de qualquer DELETE. Erros de identificação: eliminados.

**B2 — Triggers SQL Server que bloqueiam UPDATE/DELETE de forma condicional.**
- *Incerteza:* `TRG_INC_UPD_TGFFIN_MONIOCOREM` e `TRG_DLT_TGFFIN` abortam operações conforme estado (cobrança
  registrada, título baixado). Não havia documentação da **sequência** de operações que não viola as guardas.
- *Experimentação:* teste transacional de cada ordenação possível (limpar `MONIOCOREM`, inserir TGFRAF de baixa,
  alternar `PROVISAO`, filtrar `DHBAIXA`), medindo qual sequência passa.
- *Resultado:* protocolo determinístico **CASO A** (sem boleto → DELETE físico) e **CASO B** (com boleto →
  `MONIOCOREM='N'` → enfileira baixa Itaú → `PROVISAO='S'` → polling → DELETE físico pós-confirmação).

**B3 — Modelo de eventos do ERP: UPDATE via SQL/SP não dispara listeners JAPE.**
- *Incerteza:* por que o cancelamento acionado pela *stored procedure* nativa não executava a lógica do módulo.
- *Experimentação:* instrumentação e correlação de logs entre gatilho SQL e disparo do `afterUpdate`.
- *Resultado:* confirmado que apenas a camada de persistência do ERP dispara os eventos. Migração da rotina de
  *stored procedure* para **`AcaoRotinaJava`** (botão Java transacional), restabelecendo o disparo síncrono.

**B4 — Propagação de contexto de autenticação para thread assíncrona.**
- *Incerteza:* o `FinanceiroListener` nativo exige um objeto de autenticação (`authInfo`) ao persistir títulos;
  o worker assíncrono roda em *thread pool* **sem sessão autenticada**, causando exceção não-óbvia
  (`getRequiredProperty` → `RETRIES_EXHAUSTED`) sem deixar rastro claro.
- *Experimentação:* análise de *stacktrace* profundo, reprodução controlada, e injeção do contexto capturado na
  thread autenticada (evento/botão) para dentro do worker.
- *Resultado:* padrão de **captura e reinjeção de `authInfo`** (DTO de 7 campos) no `AsyncAdiantamentoProcessor`.
  Falha de criação por ausência de contexto: eliminada; rollback comprovadamente **atômico** (sem títulos parciais).

**B5 — Ciclo de vida do boleto na API bancária (Itaú) — assíncrono e com sinalização enganosa.**
- *Incerteza:* quando o boleto está **efetivamente** registrado/baixado. A interface exibe *"será enviado após 24h
  por regra do banco"*, mas a baixa é frequentemente aceita em segundos — usar o texto como verdade levaria a
  decisões erradas (deletar título com boleto vivo → cliente pode pagar valor sem nota).
- *Experimentação:* captura e análise das requisições/respostas reais da API (`ApiBancosHelper`) nos logs de
  produção; medição da latência real de registro e de baixa (`status:-1`).
- *Resultado:* decisão de DELETE físico condicionada ao estado **real** (`TGFRAF STATUS='E'`), com *polling*
  síncrono limitado e *safety-net* assíncrono; a mensagem de 24h foi caracterizada como **não-confiável**.

**B6 — Versionamento temporal de tabela de configuração (TGFTOP com PK `(CODTIPOPER, DHALTER)`).**
- *Incerteza:* por que habilitar a flag `AD_GERAADIANT` pela tela **não surtia efeito** no gate de qualificação.
- *Experimentação:* consulta ao dicionário (chave primária), listagem das N versões históricas do Tipo de Operação
  e comparação da versão lida pelo código (`MAX(DHALTER)`) versus a versão editada.
- *Resultado:* descoberto que a tabela é **versionada no tempo** e o gate lê a versão vigente; a edição por tela
  criava uma versão nova com o campo nulo. Correção pontual na versão vigente e caracterização do risco de
  reincidência (escrita pela tela).

**B7 — Concorrência e idempotência sob múltiplos gatilhos.**
- *Incerteza:* o mesmo pedido pode ser tocado por evento, scheduler e botão simultaneamente → risco de
  **adiantamento/boleto em duplicidade** (dano financeiro).
- *Experimentação:* projeto de guardas atômicas (`Set#add`), verificação de pré-existência em TGFFIN, e eleição de
  líder do scheduler via *system property* (resistente a *redeploy* de classloader).
- *Resultado:* idempotência multicamada; duplicidade não reproduzível nos testes.

### 3.3. Caráter metodológico (método científico aplicado)

O desenvolvimento seguiu ciclos **hipótese → protótipo → experimento → refutação/confirmação**, materializados em
versões carimbadas (`BUILD_VERSION`) e testadas em ambiente *production-like* transacional antes de produção:

- **V15.1** — bifurcação CASO A/CASO B (hipótese do NUMDUPL + sequência anti-trigger).
- **V16** — polling síncrono + DELETE físico pós-baixa Itaú (hipótese do estado real do boleto).
- **V16.1** — correção de atribuição de usuário (CODUSU real) e ergonomia de retorno.
- **V16.2** — introdução do acionador manual (`GerarBoletoAdiantamentoPixAction`) para notas fora do fluxo automático.
- **V16.3** — correção do contexto de autenticação assíncrona (`authInfo`) — refutação da hipótese de que
  `usuario_logado` bastaria; confirmação de que o objeto `authInfo` é obrigatório.

**O insucesso faz parte da prova:** múltiplas hipóteses foram refutadas por experimento (ex.: identificação por
NUMNOTA; suficiência de `usuario_logado`; confiabilidade da mensagem de 24h). Os logs de produção registram
falhas reais (`RETRIES_EXHAUSTED`, `AD_GERAADIANT bloqueando`, adiantamento não criado) que antecederam as
correções — evidência direta de **risco tecnológico**.

## 4. Métricas e KPIs técnicos (quantificação)

| Indicador | Valor | Observação |
|---|---|---|
| Latência do worker de criação de adiantamento | **139–387 ms** | Medida em produção (build V16). |
| Redução de *round-trips* JDBC | **~60%** | Consolidação de queries (ex.: gate TPV+TOP em 1 consulta). |
| Ganho de desempenho em trecho crítico | **~850×** | Otimização algorítmica (registrada em histórico de commits). |
| Grace de reconciliação de órfãos | **5 min** (era 24 h) | Responsividade a cancelamentos via SP externa. |
| Janela de *polling* de baixa Itaú | **≤30 s** síncrono + *safety-net* 5 min | Balanceia UX e consistência. |
| Volume de código do trimestre | **+1.372 linhas** alteradas / **+1.463 linhas** novas | 7 classes novas; núcleo `CancelamentoHelper` +1.214 linhas. |
| Cobertura de gatilhos de cancelamento | 4 caminhos | `afterUpdate`, `afterDelete`, botão, scheduler. |

## 5. Incertezas resolvidas × residuais

- **Resolvidas:** identificação correta do adiantamento (NUMDUPL); sequência anti-trigger; disparo de evento via
  Java; contexto de autenticação assíncrona; leitura de estado real do boleto; idempotência sob concorrência.
- **Residuais (risco tecnológico em aberto):** (a) **reincidência da configuração versionada** — editar a TOP pela
  tela pode recriar versão com flag nula; (b) **acoplamento a constantes** (CODEMP/CODUSU) que limita
  multi-empresa; (c) dependência de **comportamento não-documentado** do ERP, sujeito a mudança entre releases do
  fornecedor. Estas incertezas justificam continuidade de P&D em trimestres seguintes.

## 6. TRL e vinculação a ODS (FORMP&D 2026)

- **TRL 6 → 8** no trimestre (protótipo validado em ambiente relevante → sistema operando em produção com
  monitoramento). Campo obrigatório no FORMP&D 2025/2026.
- **ODS 9** (Indústria, Inovação e Infraestrutura) — automação e digitalização de processo financeiro
  empresarial. (ODS é campo obrigatório do FORMP&D; confirmar aderência com o time de PD&I.)

## 7. Evidências arquiváveis (trilha auditável)

| Evidência | Localização / identificador | O que comprova |
|---|---|---|
| Código-fonte versionado (Git) | Repositório `sankhya-venda-pix-adianta`, commit do 2º tri/2026 | Volume, autoria e datas do desenvolvimento. |
| Carimbos de versão | `BUILD_VERSION` V15.1 → V16.3 nos artefatos `.jar` | Iterações experimentais datadas. |
| Logs de produção | `server.log_20260515*`, `server.log_20260519*`, `server.log_20260626*` (.zip) | Testes, **falhas reais** e validação do comportamento (registro/baixa Itaú, RETRIES_EXHAUSTED, gate bloqueando). |
| Decompilação do núcleo Sankhya | `mge-modelcore-*.jar` (análise) | Esforço de **engenharia reversa** do modelo não-documentado. |
| Validação transacional em sandbox | Ambiente `SANKHYA_TESTE` (BEGIN TRAN/ROLLBACK) | Método experimental controlado antes de produção. |
| Documentação técnica | `DOCUMENTACAO_TECNICA.md`, `ARCHITECTURAL_DOCUMENTATION.md`, `HANDOVER_DEV.md` | ADRs, arquitetura e decisões de projeto. |

> **Boas práticas de guarda (skill Lei do Bem):** preservar os `.zip` de log e os `.jar` datados como prova de
> risco tecnológico; não sobrescrever. Correlacionar horas-homem dos pesquisadores (time-tracking) com estas datas.

## 8. Dispêndios elegíveis — a consolidar com Contabilidade/RH

> **Não estimado aqui por ausência de dados primários.** Este dossiê comprova o *mérito técnico*; o *cálculo do
> benefício* (exclusão adicional de 60–100% da base de IRPJ/CSLL) depende dos dispêndios que **o setor fiscal/RH
> deve fornecer**:
- **Recursos humanos de P&D:** horas dos desenvolvedores/analistas alocados ao módulo no trimestre × custo
  (salários + encargos), com *time-tracking* auditável e vínculo em 31/12.
- **Serviços/infraestrutura** diretamente afetados à pesquisa (ambiente de homologação, ferramentas).
- Aplicar a calculadora da consultoria (`scripts/calculadora_beneficio.py`) quando os valores estiverem disponíveis.

## 9. Conformidade e prazos (ciclo 2026)

- **FORMP&D:** transmissão até **31/08** (Portaria MCTI 9.563/2025); preencher TRL e ODS.
- **DIRBI:** Lucro Real anual → consolidada até **20/02** do ano seguinte; trimestral → meses de encerramento.
  Manter **ECF, SPED, DIRBI e FORMP&D coerentes** (divergência acende malha).
- Avaliação por ≥2 peritos do CAT (modo cego); recurso administrativo em **10 dias corridos**.

## 10. Conclusão para o MCTI

O trabalho do 2º trimestre de 2026 constitui **desenvolvimento experimental** legítimo: enfrentou **incerteza
técnica real** decorrente do comportamento não-documentado de um ERP proprietário (identificação, bloqueio por
trigger, versionamento temporal, modelo de eventos, contexto transacional e ciclo bancário assíncrono), e a
resolveu por **método científico iterativo** (hipóteses refutadas e confirmadas, prototipação datada, validação
transacional e observação de falhas reais em produção). Os ganhos são **quantificados** (latência 139–387 ms,
−60% round-trips, ~850× em trecho crítico) e as incertezas residuais justificam continuidade. Recomenda-se o
enquadramento das atividades, condicionado à consolidação dos dispêndios pela área fiscal e à guarda das
evidências listadas na §7.
