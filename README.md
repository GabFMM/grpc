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

## Executar 4 nós e 1 cliente

Na raiz do projeto, crie o arquivo `.env` a partir do exemplo:

```bash
cp .env.example .env
```

Preencha o `.env` com um ID e uma porta livre para cada nó.

Compile o projeto uma vez:

```bash
mvn compile
```

Abra cinco terminais na raiz do projeto. Inicie um nó em cada um, passando o número do nó como argumento:

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main -Dexec.args="1"
```

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main -Dexec.args="2"
```

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main -Dexec.args="3"
```

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main -Dexec.args="4"
```

No quinto terminal, inicie o cliente:

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.cliente.Main
```