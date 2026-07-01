## Análise do problema e correções necessárias – VendaPixAdianta

### Contexto observado
- Logs de produção (`server.log_20251118104559/server.log`) mostram que o processamento assíncrono quebra em **todas** as tentativas para a nota `4516009` com `CreateException: Propriedade de sessão requerida não encontrada: 'usuario_logado'`.
- O módulo roda como **evento + fila assíncrona** (`VendaPixAdiantaEvent` → `AsyncAdiantamentoProcessor` → `AdiantamentoService`), mas apenas a thread que disparou o evento possui `ServiceContext/JapeSession` com `usuario_logado`.
- Os workers criados em `AsyncAdiantamentoProcessor` não carregam nenhum contexto de sessão/usuário; mesmo abrindo um `JapeSession`, o método `AdiantamentoService.criarAdiantamentoParaVenda`
  - consulta `ServiceContext.getCurrent()` duas vezes (linhas ~350 e ~690) para token/cookie/baseUrl;
  - sempre usa `BigDecimal codUsuLogado = new BigDecimal("0"); // Usuário SUP`.
- Resultado: o EJB chamado internamente exige o atributo `usuario_logado` na sessão e lança a exceção.

### O que falta
1. **Usar usuário técnico dedicado**:
   - Vendedores não possuem permissão nas telas financeiras, portanto o usuário logado na TGFCAB não pode ser reutilizado.
   - Definimos o usuário `ADIANTAPIX` (CODUSU = 376) como identidade fixa para todo o fluxo de adiantamento.
2. **Aplicar o usuário no worker assíncrono**:
   - Ao iniciar cada tentativa em `AsyncAdiantamentoProcessor`, registrar `usuario_logado=376` no `JapeSessionContext` antes de executar a transação.
   - Após a execução, restaurar o valor anterior (se existir) para evitar interferência com outras rotinas.
3. **Injetar o mesmo usuário no serviço**:
   - O `AdiantamentoService` usa esse CODUSU tanto para `salvarParcelamento` quanto para qualquer chamada ao core financeiro.
   - Fallbacks para SUP ou valores “0” foram removidos; se o usuário técnico não estiver configurado, o serviço lança exceção.
4. **Eliminar configurações padrão hardcoded**:
   - O modelo de boleto (`TSICTA.MODELO`) deixou de ter valores padrão (ex.: 27). Caso a configuração não exista, o módulo aborta com erro orientativo.

### Passos recomendados
1. **Alterar `AdiantamentoTask`** para carregar:
   - `BigDecimal codUsu`;
   - `SessionContextHelper.SessionInfo sessionInfo`.
2. **No `afterUpdate`**:
   - Obter `codUsu` usando `EntityFacadeFactory.getDWFFacade().getUsuarioLogado()` (ou equivalente).
   - Preencher `SessionInfo info = SessionContextHelper.getCurrentSessionInfo()`.
   - Incluir esses campos ao instanciar a task.
3. **No worker**:
   - Antes do retry loop, chamar método utilitário (ex.: `SessionBootstrapper.apply(task)`) que:
     - cria `ServiceContext` via reflexão e injeta token/cookie/baseUrl;
     - seta `JapeSessionContext.putProperty("usuario_logado", task.codUsu())`.
4. **No serviço**:
   - Ler o usuário do contexto (preferível) ou diretamente da task e remover o hardcode do usuário SUP.
   - Garantir que qualquer método que use `ServiceContext` tenha fallback para `SessionInfo` da task caso `ServiceContext.getCurrent()` retorne `null`.

Com essas alterações o processamento assíncrono sempre executa sob o usuário `ADIANTAPIX`, o atributo `usuario_logado` existe em todas as threads e as rotinas financeiras respeitam apenas os parâmetros cadastrados em banco (sem defaults silenciosos).
