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

## Executar

Abra dois terminais na raiz do projeto. Inicie primeiro o servidor:

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.noh.Main
```

Em outro terminal, execute o cliente:

```bash
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.facul.cliente.Main
```