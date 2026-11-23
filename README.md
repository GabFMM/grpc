# gRPC com Java

## Requisitos

- JDK 21
- Maven

## Gerar as classes e compilar

Na raiz do projeto, execute:

```bash
mvn compile
```

O `protobuf-maven-plugin` gera as classes Java a partir dos arquivos `.proto` e o Maven compila essas classes junto com o código do projeto. Os arquivos gerados ficam em `target/generated-sources/protobuf/`; não edite esses arquivos manualmente.

## Executar 4 nós e 1 cliente (atalhos)

Na raiz do projeto (o `.env` é criado automaticamente com IDs 1..4 e portas 50051..50054 se não existir; os scripts exigem `NOH_N_ID=N`):

```bash
make cluster          # compila e sobe os 4 nós em segundo plano (logs em .run/noh-N.log)
make cliente          # cliente interativo
make status           # papel/termo/commit de cada nó
make kill N=2         # derruba o nó 2 com kill -9 (crash)
make restart N=2      # sobe o nó 2 de novo
make logs             # acompanha os logs de todos os nós
make stop             # encerra os nós
make clean            # encerra tudo e apaga ./data e .run
make noh N=3          # sobe só o nó 3 em primeiro plano
make unit             # testes unitários (JUnit)
make test             # bateria de testes dos cenários do enunciado (ver abaixo)
```

Sem `make`, os mesmos comandos existem em `scripts/cluster.sh` (`start|stop|status|kill N|restart N|logs|clean`), `scripts/run.sh noh N`, `scripts/run.sh cliente` e `scripts/testes.sh`.

Os scripts rodam as classes com `java -cp`, sem passar pelo Maven a cada execução. O comando manual equivalente continua válido:

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main -Dexec.args="1"
```

## Bateria de testes automatizada

`make test` (ou `scripts/testes.sh`) sobe 4 nós reais, derruba processos com `kill -9` e verifica os cenários obrigatórios do enunciado, imprimindo `[PASSOU]`/`[FALHOU]` por verificação. Leva alguns minutos e apaga `./data` e `.run`. Para rodar só alguns cenários: `scripts/testes.sh 2 4B 5` (cenários 1, 2, 3, 4A, 4B, 5). `MANTER=1` deixa os nós rodando ao final.

| Cenário | O que é verificado |
|---|---|
| Preparação | eleição inicial; console exibe papel, termo e commit |
| 1 | escrita no líder, leitura comitada nos 4 nós, redirecionamento ao gravar em um seguidor |
| 2 | queda do líder, novo líder com termo incrementado, nova escrita, reinício com dados em disco |
| 3 | 2 réplicas fora: líder recusa a escrita; com quórum de volta, a escrita volta |
| 4A | réplica fora perde escritas, ao voltar busca os dados via fetch |
| 4B | líder grava e cai antes de replicar; novo líder em termo maior; antigo líder volta, trunca a entrada órfã e ressincroniza |
| 5 | entrada recebida via fetch fica invisível para leitura até o commit do quórum |
| Persistência | arquivos H2 dos 4 nós em `./data` |

O cliente também aceita comandos não interativos (`estado`, `lider`, `gravar <txt>`, `gravar-em <id> <txt>`, `consultar <id>`, `log <id>`), usados pelos scripts, por exemplo `scripts/run.sh cliente estado`.

## Menu do cliente

1. Realizar uma gravação (só confirma depois do commit pelo quórum);
2. Consultar os dados **comitados** de um nó;
3. Consultar o log completo de um nó (entradas não comitadas aparecem marcadas).

Cada nó imprime no console, sempre que mudam, o papel, o termo, o índice de commit, o último índice do log e o líder.

## Roteiro de testes manuais

Use `jps -l` para obter o PID de um nó e `kill -9 PID` para simular um crash. Para zerar o estado, pare tudo e apague `./data`.

1. **Escrita normal e leitura distribuída:** grave `A` e consulte (opção 2) os 4 nós; todos devem mostrar `A` e `commit=1`.
2. **Queda do líder:** derrube o líder; em 2 a 4 s outro nó imprime `virou LÍDER` com termo maior. Grave `B`.
3. **Perda de quórum:** derrube 2 réplicas; em ~2,5 s o líder mostra `SEM QUÓRUM` e o cliente recebe a recusa ao gravar. Religue uma réplica e a escrita volta.
4. **A) Réplica desatualizada:** derrube um seguidor, grave `D1` e `D2`, religue-o e veja `[REPLICAÇÃO]` e `[COMMIT]` no console dele.
   **B) Truncamento:** `touch data/ignorar-replicacao-X` nos 3 seguidores (X = id de cada um; o nó continua enviando fetch, mas não armazena entradas). Grave `ORFA` (fica só no log do líder) e, em até 5 s, `kill -9` no líder N1. Apague os arquivos `ignorar-replicacao-*`, espere a eleição de N2, grave `NOVA`, religue N1: ele vira seguidor, imprime `[TRUNCAMENTO]`, e a opção 3 mostra o log igual ao dos demais.
5. **Isolamento de dados não comitados:** crie `ignorar-replicacao-X` em 2 seguidores e grave `X1`. Durante 5 s, a opção 3 mostra `X1` marcada como NÃO COMITADA no líder e na réplica que a recebeu, e a opção 2 não a mostra. Apague os arquivos e `X1` passa a aparecer em todos.

