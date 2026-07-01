## Plano de alinhamento do botão **Imprimir Boleto Adiantamento**

**Objetivo**: Fazer com que o botão customizado reproduza exatamente o fluxo observado nos logs capturados em `C:\sk-java\MonitorDeConsultas\eR1ZahdwRx0GWtZljKlcTV3tICxNo1I3IhDDeMKm`.

---

### 1. Evidências coletadas nos logs

| ID log | Evidência | Observação |
|--------|-----------|------------|
| `Monitor_Consulta.log:7…260` | Cada requisição da tela padrão (`Application: ImpressaoBoletoGrafico`) chama `service-name: BoletoSP.buildPreVisualizacao` com `Referer https://skw.bellube.com.br/mgefin/ImpressaoBoletoGrafico.xhtml5`. | Fluxo genuíno da UI do Sankhya. |
| `ID_252` e `ID_254` | `SELECT (REMBCO + 1) ... WITH(UPDLOCK,HOLDLOCK)` seguido de `UPDATE TSICTA SET REMBCO = ? WHERE CODCTABCOINT = ?`. | O serviço oficial controla a sequência REM/REMBCO. |
| `ID_256` | `SELECT ... TSICTA.MODBOLETA ... WHERE TSICTA.CODCTABCOINT = 15`. | Modelo do boleto é lido diretamente de **TSICTA**, não de TGFCTA/TGFCBM. |
| `Monitor_Processos.log:1-33` | O call stack termina em `br.com.sankhya.modelcore.facades.BoletoSPBean.buildPreVisualizacao`. | A tela chama o EJB diretamente, sem HTTP “manual”. |

Nenhum desses logs cita `[ImprimirBoletoAdiantamento]` ou classes de `VendaPixAdianta`, provando que o botão atual não replica o fluxo oficial.

---

### 2. Diagnóstico do código atual do botão

| Classe | Problema |
|--------|----------|
| `ImprimirBoletoAdiantamentoAction` | Orquestra toda a operação via `BoletoPreviewService`/`BoletoRepository`, retornando apenas uma URL de visualização. |
| `BoletoPreviewService` | Usa um *HTTP client* próprio (`SankhyaHttpClient`) para montar um JSON customizado. Faltam as etapas de remessa (`REMBCO`), set de sessão (`Stp_Set_Session2`), locking e demais queries vistas nos logs. |
| `BoletoRepository` | Busca o modelo do boleto em **TGFCTA/TGFCBM**, enquanto o sistema corporativo usa **TSICTA**. Não atualiza `REMBCO`. |

Na prática, o botão age como um microcliente paralelo e não dispara o mesmo pipeline do Sankhya.

---

### 3. Alterações necessárias

#### 3.1 Substituir o mecanismo de geração no `BoletoPreviewService`

1. **Descartar o HTTP manual** (`generateWithCurrentSession`, `generateWithDedicatedLogin`, etc.).
2. Importar e chamar diretamente o EJB oficial:
   ```java
   BoletoSPBean bean = ServiceFactory.getService(BoletoSPBean.class, new Contexto());
   ```,
   reutilizando o mesmo `Contexto`/`JapeSession` já aberto pelo botão.
3. Invocar `bean.buildPreVisualizacao(Map<String, Object> request)` com o payload usado pela UI. Basear-se no dicionário padrão (`configBoleto.agrupamento=4`, `gerarNumeroBoleto=true`, `telaImpressaoBoleto=true`, `multiTransacional=true`, `titulo=[{"$":<NUFIN>}]`).
4. Ler o retorno JSON (o próprio bean devolve uma chave no `responseBody`). Não é preciso gerar link manual – basta replicar a mensagem exibida pela UI: use o mesmo `mge/visualizadorArquivos.mge?chaveArquivo=...`.

#### 3.2 Adequar o `BoletoRepository`

1. Buscar modelo/relatório apenas na **TSICTA** (coluna `MODBOLETA` ou `CODREL` conforme o ambiente). A consulta já aparece nos logs (`ID_256`), portanto deve ser reproduzida.
2. Antes de chamar o bean, executar o mesmo bloco da UI:
   - `SELECT (REMBCO + 1) ... WITH(UPDLOCK,HOLDLOCK)` para obter a sequência.
   - `UPDATE TSICTA SET REMBCO = ? WHERE CODCTABCOINT = ?`.
3. Remover dependência de TGFCTA/TGFCBM nesse contexto (a UI não usa essas tabelas para impressão).

#### 3.3 Ajustar o `ImprimirBoletoAdiantamentoAction`

1. Após validar `NUNOTA/NUFIN`, criar um `ServiceContext` idêntico ao da UI (o log mostra `Stp_Set_Session2` com usuário/empresa). Utilize `SessionContextHelper.ensureSession(codUsu, codEmp)` para garantir o `authInfo`.
2. Substituir a chamada ao `BoletoPreviewService.generatePreview` pelo novo método que invoca `BoletoSPBean`.
3. Em caso de sucesso, montar a mensagem usando o mesmo padrão do Sankhya (“Boleto disponível … clique para abrir”).
4. Em caso de falha, reproduzir o texto padrão retornado pelo bean (ele devolve `status=0` e `tsError`). Não inventar mensagens customizadas para manter consistência.

#### 3.4 Limpar código morto

- Remover `SankhyaHttpClient`, estratégias de *login dedicado* e constantes não usadas (`SERVICE_MOBILE_LOGIN`, `FILE_PREFIX_BOLETO`, etc.). O fluxo oficial não exige HTTP manual.

---

### 4. Passos sugeridos de implementação

1. **Criar um wrapper** `BoletoPreviewClient` bem simples:
   ```java
   public PreviewResult generate(BigDecimal nufin) {
       Map<String, Object> payload = buildPayload(nufin);
       BoletoSPBean bean = ServiceFactory.getService(BoletoSPBean.class, new Contexto());
       String response = bean.buildPreVisualizacao(payload);
       return parseResponse(response);
   }
   ```
2. **Mover** a lógica de busca/validação de `NUMNOTA/NUFIN/CODCTABCOINT` para `BoletoRepository`, garantindo o update de `REMBCO`.
3. **Atualizar** `ImprimirBoletoAdiantamentoAction` para usar o novo cliente e remover o atual `previewService`.
4. **Testar** em um ambiente de homologação acompanhando o `MonitorDeConsultas` e comparando novamente com os logs. O objetivo é voltar a ver as mesmas sequências `Stp_Set_Session2`, `SELECT ... TSICTA`, `UPDATE TSICTA SET REMBCO` e o stack `BoletoSPBean`.

---

### 5. Resultado esperado

Após as alterações acima:

- Os logs capturados ao clicar no botão customizado deverão ser idênticos aos da tela padrão (mesmo `service-name`, mesmas queries, mesmo EJB).
- O botão deixará de manter lógica duplicada e passará a reutilizar o pipeline 100% suportado pelo Sankhya, evitando divergências de configuração quando novas versões adicionarem colunas (como aconteceu com `MODELO/MODBOLETA`).

> **Importante:** As mudanças acima não alteram o contrato com o usuário final (mensagem/URL continuam iguais), mas garantem que toda a lógica sensível (REMBCO, bloqueios, parâmetros bancários) seja tratada pelo próprio Sankhya, reduzindo o risco de falhas na geração dos boletos.
