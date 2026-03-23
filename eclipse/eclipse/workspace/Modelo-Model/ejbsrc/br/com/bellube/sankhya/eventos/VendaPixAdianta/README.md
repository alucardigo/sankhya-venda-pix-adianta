Entendido. Um bom projeto começa com um bom `README`. Ele serve como o contrato e a fonte da verdade para o desenvolvedor.

Com base em toda a análise e na especificação técnica que definimos, preparei o `README.md` a seguir. Este documento deve ser colocado na raiz do projeto e servirá como o guia mestre para a IA de desenvolvimento, garantindo que a implementação siga a arquitetura e os princípios de robustez que estabelecemos.

-----

`# Módulo de Automação de Adiantamento PIX para Sankhya-OM

## 1. Visão Geral do Projeto

Este projeto visa desenvolver um módulo de customização para o ERP Sankhya-OM. O objetivo é automatizar a criação de um lançamento de AdiantamentoEmpréstimo sempre que uma nota de venda (`TGFCAB`) for confirmada e seu respectivo título financeiro (`TGFFIN`) for identificado como pago via PIX.

A implementação deve ser robusta, resiliente e totalmente configurável, comportando-se como uma extensão nativa da plataforma. A análise para este desenvolvimento se baseia nos seguintes artefatos

   Imagens da UI Fluxo manual de criação de adiantamento.
   Log da Aplicação `AdiantamentoEmprestimo.log`, que revela as validações de backend do sistema.[1]
   Precedentes da Comunidade Discussões técnicas que revelam contratos de serviço.[2]

## 2. Arquitetura e Princípios de Design (Não-Negociáveis)

A solução deve aderir estritamente aos seguintes princípios arquitetônicos para garantir a estabilidade e a manutenibilidade a longo prazo.

### 2.1. Desacoplamento e Resiliência (Execução Assíncrona)

A transação de faturamento é uma operação crítica para o negócio. A lógica de criação do adiantamento não pode, sob nenhuma circunstância, impactar a performance ou a estabilidade do faturamento.

   Justificativa Uma abordagem síncrona (executar a criação do adiantamento dentro do evento `afterInsert` da `TGFCAB`) criaria um acoplamento perigoso. Qualquer lentidão ou erro no módulo financeiro (deadlocks, falhas de serviço) causaria o rollback da venda, paralisando a operação.
   Solução A arquitetura será assíncrona. O evento na `TGFCAB` terá a responsabilidade mínima e ultrarrápida de apenas capturar os dados necessários e adicionar uma tarefa a uma fila de processamento. O trabalho pesado será executado por workers em threads separadas, com suas próprias transações, garantindo total isolamento.

### 2.2. Fidelidade ao Core do ERP (Invocação de Serviço Nativo)

É terminantemente proibido manipular as tabelas `TGFADI` e `TGFFIN` diretamente via JAPE para criar o adiantamento.

   Justificativa A análise dos logs [1] e da UI demonstra que a criação de um adiantamento é uma operação de negócio complexa, com múltiplas validações (`ATIVA = 'S'`, `TIPMOV = 'I'`, etc.), verificações de limite de crédito e criação de registros financeiros interligados. Manipular o banco diretamente contornaria essa inteligência, resultando em dados inconsistentes e problemas de manutenção futuros.
   Solução A automação deve utilizar a camada de serviço nativa do Sankhya através da classe `ServiceInvoker`. O serviço alvo é `AdiantamentoEmprestimoSP.salvarParcelamento`.[2] Esta abordagem garante que todas as regras de negócio do core do ERP sejam respeitadas, tornando a customização inerentemente compatível com futuras atualizações da plataforma.

### 2.3. Configurabilidade Absoluta (Zero Hardcoding)

Nenhum valor de negócio (código de TOP, Natureza, Conta Bancária, etc.) pode estar fixado no código Java.

   Justificativa Hardcoding de valores de configuração torna a customização frágil e dependente de desenvolvedores para qualquer ajuste operacional.
   Solução Toda a configuração da automação será externalizada para a tabela de Parâmetros do Sistema (`TSIPAR`). Será utilizada uma nomenclatura padronizada para fácil identificação
       `AUT.ADIANT.` para parâmetros gerais do adiantamento.
       `AUT.PIX.` para parâmetros de identificação da transação PIX.

### 2.4. Observabilidade e Manutenibilidade (Logging Robusto)

Como o processo é assíncrono, o feedback de erro via `MGEModelException` para o usuário final é inútil e não deve ser utilizado.

   Justificativa Falhas no processamento em background devem ser capturadas e registradas de forma persistente para análise e ação corretiva pela equipe de TI.
   Solução Será criada uma tabela de log customizada (`AD_LOGERROAUT`). Qualquer exceção durante o processamento de uma tarefa será capturada, e um novo registro será inserido nesta tabela, contendo o `NUNOTA` da venda, a mensagem de erro, o stack trace completo e um timestamp.

## 3. Base de Conhecimento (Fontes de Verdade)

O desenvolvimento deve se basear nas seguintes fontes de documentação e precedentes

 Categoria  Recurso  Propósito  Link 
 ---  ---  ---  --- 
 Funcional  Ajuda Sankhya  Entender as regras de negócio da tela de AdiantamentoEmpréstimo.  [Link](httpsajuda.sankhya.com.brhcpt-brarticles360044598534-Adiantamento-Empr%C3%A9stimo) [3] 
 Técnica  Comunidade Sankhya  Contrato da API `AdiantamentoEmprestimoSP.salvarParcelamento` (payload JSON).  [Link](httpscommunity.sankhya.com.brdevelopersconectividadepostproblema-api---adiantamentoemprestimosp-0pP7LH40DNfoCY4) [2] 
 Técnica  Developer Portal  Documentação oficial para Eventos, JAPE e SankhyaUtil. (httpsdeveloper.sankhya.com.brdocsrotina-java) [4] 
 Técnica  Developer Portal  Documentação oficial do JAPE.  [JAPE](httpsdeveloper.sankhya.com.brdocsjape) [5] 
 Técnica  Developer Portal  Documentação de classes utilitárias. ([httpsdeveloper.sankhya.com.brdocssankhyautil](httpsdeveloper.sankhya.com.brdocssankhyautil)) [6] 
 Filosofia  Developer Portal  Diretrizes gerais para desenvolvimento de customizações. ([httpsdeveloper.sankhya.com.brdocsguia-de-boas-praticas](httpsdeveloper.sankhya.com.brdocsguia-de-boas-praticas)) [7] 
 Filosofia  YouTube  Boas práticas de programação na plataforma. ([httpswww.youtube.comwatchv=zA_9p3Q9ATM](httpswww.youtube.comwatchv=zA_9p3Q9ATM)) 

## 4. Especificação Técnica de Implementação

### 4.1. Diagrama de Componentes

O fluxo de dados seguirá a seguinte sequência

`Usuário Salva Venda` - ``- `Evento afterInsert(TGFCAB)` - `AdiantamentoPIXEvento.java` - `TaskQueueManager` - `BlockingQueueProcessamentoTask` - `AsyncTaskProcessor.java` (Worker Thread) - `AdiantamentoService.java` - `ServiceInvoker` -`` - ``

### 4.2. Estrutura de Código e Responsabilidades

O projeto será organizado no pacote `br.com.sankhya.custom.adiantamentopix` com a seguinte estrutura de classes

   `modelProcessamentoTask.java` POJO imutável para transportar os dados da venda (`nunota`, `codparc`, `vlrnota`, etc.) do evento para o worker.
   `asyncTaskQueueManager.java` Singleton que gerencia o `ExecutorService` (pool de threads) e a `BlockingQueue`, iniciando os workers.
   `asyncAsyncTaskProcessor.java` Classe `Runnable` que representa o worker. Fica em um loop consumindo tarefas da fila, gerenciando a transação JAPE e o tratamento de exceções de alto nível.
   `utilParametroUtils.java` Classe utilitária para ler parâmetros da `TSIPAR` de forma segura, com um cache simples para otimizar a performance.
   `eventAdiantamentoPIXEvento.java` Implementa `EventoProgramavelJava`. Sua única função é verificar as condições (`AUT.ADIANT.ATIVO`, `isVendaPIX`) e enfileirar a `ProcessamentoTask`.
   `serviceAdiantamentoService.java` Contém a lógica de negócio principal. É responsável por buscar os parâmetros, montar o payload XML e invocar o `ServiceInvoker`.

### 4.3. Estratégia de Identificação de Venda PIX

A identificação será feita via `TSIPAR` para máxima flexibilidade

1.  O evento `AdiantamentoPIXEvento` lerá os seguintes parâmetros
       `AUT.PIX.CAMPOIDENT` (Texto) Nome da coluna na `TGFFIN` a ser verificada (ex `CODTIPTIT`).
       `AUT.PIX.VALORIDENT` (Texto) Valor que identifica o PIX no campo acima (ex `17`).
2.  Será executada uma consulta na `TGFFIN` com `WHERE NUNOTA = AND =`. Se houver resultados, a venda é considerada PIX.

### 4.4. Estrutura do Payload do `ServiceInvoker`

O corpo da requisição para o serviço `AdiantamentoEmprestimoSP.salvarParcelamento` será um XML montado programaticamente. A estrutura deve ser baseada no JSON da comunidade [2] e nos campos da UI.

Template do Payload XMLxml
requestBody
parcelas impressao=ADIANTEMP
parcela
CODEMP$${task.codemp}$CODEMP
CODPARC$${task.codparc}$CODPARC
VLRDESDOB$${task.vlrnota}$VLRDESDOB
DTNEG$${data_formatada}$DTNEG
DTVENC$${data_formatada}$DTVENC
HISTORICO$Adiantamento aut. ref. venda PIX NUNOTA ${task.nunota}$HISTORICO
RECDESP$-1$RECDESP CODTIPOPER$${param.topDesp}$CODTIPOPER
CODTIPTIT$${param.tipTitDesp}$CODTIPTIT
CODNAT$${param.natDesp}$CODNAT
CODCTABCOINT$${param.contaBco}$CODCTABCOINT
CODCENCUS$${task.codcencus}$CODCENCUS

```
        NUMNOTA$0$NUMNOTA
        PROVISAO$N$PROVISAO
        ORIGEM$F$ORIGEM
    parcela
    parcelas
```

requestBody

```

## 5. Plano de Testes e Validação

 Cenário  Passos  Resultado Esperado 
 ---  ---  --- 
 1. Caminho Feliz  Faturar uma venda com meio de pagamento PIX.  A venda é confirmada sem erros. Um novo adiantamento é criado e pode ser consultado na tela correspondente. Nenhum erro é registrado na tabela `AD_LOGERROAUT`. 
 2. Gatilho Ignorado  Faturar uma venda com meio de pagamento Boleto.  A venda é confirmada sem erros. Nenhum adiantamento é criado. Nenhum erro é registrado. 
 3. Falha de Regra de Negócio  Faturar uma venda PIX para um parceiro que excederá o limite de crédito.  A venda é confirmada com sucesso. Um registro de erro é inserido na `AD_LOGERROAUT` com a mensagem retornada pelo serviço (ex Limite de crédito excedido). Nenhum adiantamento é criado. 
 4. Falha de Configuração  Alterar o parâmetro `AUT.ADIANT.TOPDESP` para um código de TOP inativo e faturar uma venda PIX.  A venda é confirmada com sucesso. Um registro de erro é inserido na `AD_LOGERROAUT` com a mensagem retornada pelo serviço (ex Tipo de Operação inativo). Nenhum adiantamento é criado. 

## 6. Estrutura do Projeto

```

src
└── br
└── com
└── bellube
└── sankhya
└── eventos
└── VendaPixAdianta
├── async
│   ├── AsyncTaskProcessor.java
│   └── TaskQueueManager.java
├── event
│   └── AdiantamentoPIXEvento.java
├── model
│   └── ProcessamentoTask.java
├── service
│   └── AdiantamentoService.java
└── util
└── ParametroUtils.java

```

```

```
```