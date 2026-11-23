# Modelagem dos dados dos nós

## Isolamento entre nós

Cada processo do cluster recebe um identificador (`id`) e abre seu próprio banco H2 em arquivo. O caminho é definido em `Database`:

```text
./data/raft-<id>
```

Por exemplo, o nó de ID `1` usa `./data/raft-1` e o nó de ID `2` usa `./data/raft-2`. O H2 pode acrescentar a extensão do arquivo físico, como `.mv.db`.

Cada banco contém as tabelas `noh`, `log` e `dados`. Como os processos usam arquivos diferentes, uma consulta ou gravação de um nó não altera diretamente as tabelas dos outros nós. A cópia dos dados entre nós é responsabilidade do protocolo gRPC (`RequestVote` e `Fetch`).

```text
# Exemplo:
data/
├── raft-1.mv.db   banco do nó de ID 1
├── raft-2.mv.db   banco do nó de ID 2
├── raft-3.mv.db   banco do nó de ID 3
└── raft-4.mv.db   banco do nó de ID 4
```

Os nomes acima ilustram a separação lógica. A extensão e outros arquivos auxiliares são gerenciados pelo H2.

## Tabela `noh`

Armazena o estado persistente de eleição do próprio nó. Cada arquivo contém uma linha identificada pelo ID do nó.

| Coluna | Tipo | Significado |
|---|---|---|
| `id` | `INT`, chave primária | Identificador do nó dono desse banco. |
| `termo` | `INT`, obrigatório | Termo atual conhecido pelo nó. |
| `votou_id` | `INT`, obrigatório| ID do candidato que recebeu o voto do nó no termo atual; `-1` representa nenhum voto. |
| `indice_commit` | `INT`, obrigatório | Maior índice do log local considerado comitado pelo nó. |

O banco é inicializado com o ID do nó, termo `0`, `votou_id = -1` e `indice_commit = 0`. O código usa `-1` como sentinela para “nenhum voto”. O termo, o voto e o índice de commit são persistidos para que o nó recupere esses dados após reiniciar.

O estado (`SEGUIDOR`, `CANDIDATO` ou `LIDER`), o ID do líder conhecido e a contagem de votos recebidos são estado de execução, não colunas da tabela. `NohMemoria` mantém uma cópia em memória do índice de commit, mas a coluna `noh.indice_commit` é a fonte persistente.

## Tabela `log`

Armazena as operações recebidas pelo nó, na ordem em que foram registradas localmente.

| Coluna | Tipo | Significado |
|---|---|---|
| `indice` | `INT`, chave primária | Posição sequencial da entrada no log local. |
| `termo` | `INT`, obrigatório | Termo do líder que criou a entrada. |
| `comando` | `VARCHAR(255)`, obrigatório | Operação de gravação recebida. |

O índice é a chave primária para impedir duas entradas na mesma posição dentro do log daquele nó. O índice não precisa ser chave estrangeira de `noh`: as duas tabelas pertencem ao banco isolado do mesmo nó, e o log não possui uma linha de estado por entrada.

Ao iniciar, o nó recupera o último índice e o termo da última entrada para preencher `ultimoIndiceLog` e `ultimoTermoLog` em memória.

## Log local, replicação e commit

O log contém entradas armazenadas localmente. Uma entrada pode estar presente no log sem estar comitada; portanto, **estar na tabela `log` não significa que o dado já pode ser mostrado em consultas**.

O fluxo esperado é:

1. O líder registra uma operação em seu log.
2. As réplicas buscam entradas do líder por `FetchRequest` e as registram em seus próprios bancos.
3. O líder avança o índice de commit quando a entrada está armazenada no quórum de três dos quatro nós, contando o próprio líder.
4. As réplicas recebem o novo índice de commit em um `FetchRequest` e tornam visíveis apenas as entradas com índice menor ou igual a esse valor.

Assim, os quatro bancos têm cópias independentes do log, que podem estar temporariamente em índices diferentes. A consistência entre elas é mantida pela replicação gRPC, não por compartilhamento de arquivo ou transação SQL entre processos.

## Tabela `dados` (banco final)

Armazena somente as entradas **comitadas**, com as mesmas colunas de `log` (`indice` PK, `termo`, `comando`). É a única fonte usada nas consultas dos clientes (leitura eventual consistente).

Ao avançar o índice de commit, `Noh.setIndiceCommit` primeiro copia do `log` para `dados` as entradas ainda não aplicadas (`LogRepository.aplicarCommit`, idempotente) e só depois persiste `noh.indice_commit`. Ao iniciar, `Noh` reaplica o commit persistido, cobrindo um crash entre os dois passos.

## Correspondência entre classes e tabelas

- `NohPersistente` representa os campos persistidos da tabela `noh` e os metadados recuperados do último registro do log.
- `NohMemoria` guarda o estado volátil do nó, como papel na eleição, líder conhecido, votos recebidos e índice de commit em execução.
- `Estado` enumera os papéis possíveis do nó.
- `EntradaLog` representa uma linha da tabela `log`.
- `NohPersistenteRepository` lê e atualiza a tabela `noh`.
- `LogRepository` insere, trunca e lista entradas de `log` e aplica/lista a tabela `dados`.
- `Database` configura o arquivo H2 específico para o ID e cria as tabelas do nó.
- `Noh` coordena os dados persistidos e voláteis junto às dependências do nó.
