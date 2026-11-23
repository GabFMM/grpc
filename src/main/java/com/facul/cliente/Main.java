package com.facul.cliente;

import com.facul.api.*;
import com.facul.proto.EntradaLog;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.facul.util.Constantes.*;
import static com.facul.util.ClienteNohUtil.criarCanais;

public class Main {
    private static Map<Integer, ManagedChannel> canais = null;
    private static int liderId = -1;

    private static final Scanner scanner = new Scanner(System.in);

    private static int lerInteiro() {
        while (true) {
            try {
                String entrada = scanner.nextLine().trim();
                return Integer.parseInt(entrada);
            } catch (NumberFormatException e) {
                System.out.println("Valor inválido! Digite novamente o número: ");
            }
        }
    }

    private static void limparBuffer() {
        try {
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (Exception e) {
            // Ignora erro de leitura
        }
    }

    private static void obterLiderId() throws InterruptedException {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS.value; tentativa++) {
            if (atualizarLiderId()) {
                return;
            }

            if (tentativa < MAX_TENTATIVAS.value) {
                System.out.printf(
                        "[INFO] Líder ainda desconhecido; nova tentativa em %d ms (%d/%d).%n",
                        INTERVALO_TENTATIVAS_MS.value, tentativa, MAX_TENTATIVAS.value
                );
                Thread.sleep(INTERVALO_TENTATIVAS_MS.value);
            }
        }

        throw new IllegalStateException(
                "[ERRO] Nenhum nó informou o líder após " + MAX_TENTATIVAS.value + " tentativas."
        );
    }

    private static boolean atualizarLiderId() {
        for (Map.Entry<Integer, ManagedChannel> entrada : canais.entrySet()) {
            try {
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(entrada.getValue())
                                .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

                int id = stub.obterLiderId(Empty.getDefaultInstance()).getLiderId();
                if (id != -1 && canais.containsKey(id)) {
                    liderId = id;
                    return true;
                }
            } catch (StatusRuntimeException e) {
                entrada.getValue().resetConnectBackoff();
                System.err.printf(
                        "[AVISO] Nó (id %d) não respondeu (%s); tentando os demais nós.%n",
                        entrada.getKey(), e.getStatus().getCode()
                );
            }
        }
        return false;
    }

    private static void mostrarMenu() {
        System.out.println("Escolha uma opção:");
        System.out.println("1 - Realizar uma gravação;");
        System.out.println("2 - Consultar dados comitados em um nó específico;");
        System.out.println("3 - Consultar o log completo de um nó (inclui entradas NÃO comitadas).");

        int opcao = lerInteiro();

        if (opcao == 1) {
            mostrarTelaGravacao();
        } else if (opcao == 2) {
            mostrarTelaConsulta(false);
        } else if (opcao == 3) {
            mostrarTelaConsulta(true);
        }
    }

    private static void mostrarTelaGravacao() {
        System.out.println("Digite a gravação:");
        String gravacao = scanner.nextLine();

        boolean sucesso = realizarGravacao(gravacao);

        limparBuffer();

        if (sucesso) {
            System.out.println("Feita a gravação (comitada pelo quórum)!");
        }
        System.out.println("Digite qualquer tecla para voltar ao menu.");
        scanner.nextLine();
    }

    private static boolean realizarGravacao(String gravacao) {
        final int maxRedirecionamentos = 10;

        for (int tentativa = 0; tentativa < maxRedirecionamentos; tentativa++) {
            ManagedChannel canalLider = canais.get(liderId);
            if (canalLider == null) {
                if (!atualizarLiderId()) {
                    aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                    continue;
                }
                canalLider = canais.get(liderId);
            }

            // Antes de enviar a gravação, confirma que o líder conhecido está alcançável.
            // Falha durante o envio da gravação NÃO é repetida, para não duplicá-la.
            if (!alcancavel(canalLider)) {
                liderId = -1;
                aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                continue;
            }

            try {
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(canalLider)
                                .withDeadlineAfter(DEADLINE_GRAVACAO_SEGUNDOS.value, TimeUnit.SECONDS);

                RealizarGravacaoResponse resposta = stub.realizarGravacao(
                        RealizarGravacaoRequest.newBuilder()
                                .setGravacao(gravacao)
                                .build()
                );

                switch (resposta.getResultado()) {
                    case SUCESSO -> {
                        return true;
                    }
                    case REDIRECIONAR -> {
                        if (resposta.hasLiderId() && canais.containsKey(resposta.getLiderId())) {
                            liderId = resposta.getLiderId();
                            System.out.println("[INFO] Redirecionado para o líder " + liderId);
                        } else if (!atualizarLiderId()) {
                            aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                        }
                    }
                    case LIDER_DESCONHECIDO -> {
                        if (!atualizarLiderId()) {
                            aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                        }
                    }
                    case SEM_QUORUM -> {
                        System.out.println("Gravação NÃO confirmada: o líder não tem quórum de réplicas.");
                        return false;
                    }
                    case ERRO_GRAVACAO -> {
                        System.out.println("Erro no servidor; tente novamente.");
                        return false;
                    }
                    default -> {
                        System.out.println("Resposta desconhecida do servidor.");
                        return false;
                    }
                }
            } catch (StatusRuntimeException e) {
                Status.Code codigo = e.getStatus().getCode();

                if (codigo == Status.Code.UNAVAILABLE || codigo == Status.Code.DEADLINE_EXCEEDED) {
                    // Não repete: a requisição pode ter chegado ao líder e duplicaria a gravação
                    canalLider.resetConnectBackoff();
                    System.out.println("Conexão com o líder perdida ou sem resposta; a gravação pode ou não ter sido efetivada. Consulte os nós.");
                    return false;
                } else {
                    System.out.println("Erro no servidor; tente novamente.");
                    return false;
                }
            }
        }

        System.out.println("Não foi possível localizar o líder. Tente novamente.");
        return false;
    }

    private static boolean alcancavel(ManagedChannel canal) {
        try {
            ApiServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS)
                    .obterLiderId(Empty.getDefaultInstance());
            return true;
        } catch (StatusRuntimeException e) {
            canal.resetConnectBackoff();
            return false;
        }
    }

    private static void aguardarNovaTentativa(int intervaloMs) {
        try {
            Thread.sleep(intervaloMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean imprimirConsulta(int id, boolean incluirNaoComitados) {
        ConsultarNohResponse response = consultarNoh(id, incluirNaoComitados);

        if (response == null) {
            return false;
        }

        System.out.printf("Nó %d | papel=%s | termo=%d | commit=%d | último índice do log=%d%n",
                id, response.getPapel(), response.getTermo(),
                response.getIndiceCommit(), response.getUltimoIndiceLog());
        System.out.println(incluirNaoComitados ? "Log completo:" : "Dados comitados:");

        if (response.getEntradasCount() == 0) {
            System.out.println("(vazio)");
        }

        for (EntradaLog entrada : response.getEntradasList()) {
            String marca = entrada.getIndice() > response.getIndiceCommit() ? "  <-- NÃO COMITADA" : "";
            System.out.printf("- Indice: %d | Termo: %d | Comando: %s%s%n",
                    entrada.getIndice(),
                    entrada.getTermo(),
                    entrada.getComando(),
                    marca);
        }

        return true;
    }

    private static void mostrarTelaConsulta(boolean incluirNaoComitados) {
        mostrarNohs();

        System.out.println("Digite o ID do nó a ser consultado:");
        int id = lerInteiro();

        imprimirConsulta(id, incluirNaoComitados);

        limparBuffer();

        System.out.println("Digite qualquer tecla para voltar ao menu.");
        scanner.nextLine();
    }

    private static void mostrarNohs() {
        System.out.println("Lista de nós conhecidos identificados pelo ID:");

        for (Integer id : canais.keySet())
            if (id == liderId)
                System.out.println("- " + id + " (líder)");
            else
                System.out.println("- " + id);
    }

    private static ConsultarNohResponse consultarNoh(int id, boolean incluirNaoComitados) {
        ManagedChannel canal = canais.get(id);
        if (canal == null) {
            System.out.println("Nó desconhecido.");
            return null;
        }

        try {
            ApiServiceGrpc.ApiServiceBlockingStub stub =
                    ApiServiceGrpc.newBlockingStub(canal)
                            .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

            ConsultarNohRequest request = ConsultarNohRequest.newBuilder()
                    .setNohId(id)
                    .setIncluirNaoComitados(incluirNaoComitados)
                    .build();

            return stub.consultarNoh(request);

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.INTERNAL) {
                System.out.println("O nó não conseguiu consultar o log.");
            } else {
                canal.resetConnectBackoff();
                System.out.println("Não foi possível se comunicar com o nó.");
            }

            return null;
        }
    }

    // ------------------------------------------------------------------
    // Modo não interativo (usado pelos scripts de teste)
    //   estado              -> uma linha por nó: NO=1 PAPEL=LIDER TERMO=2 COMMIT=3 ULTIMO=3
    //   lider               -> LIDER=<id>
    //   gravar <texto>      -> código de saída 0 se comitada pelo quórum
    //   gravar-em <id> <t>  -> idem, mas começa pelo nó <id> (redirecionamento)
    //   consultar <id>      -> dados comitados do nó
    //   log <id>            -> log completo (inclui entradas não comitadas)
    // ------------------------------------------------------------------

    private static int imprimirEstadoCluster() {
        for (Integer id : new TreeSet<>(canais.keySet())) {
            try {
                ConsultarNohResponse r = ApiServiceGrpc.newBlockingStub(canais.get(id))
                        .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS)
                        .consultarNoh(ConsultarNohRequest.newBuilder().setNohId(id).build());

                System.out.printf("NO=%d PAPEL=%s TERMO=%d COMMIT=%d ULTIMO=%d%n",
                        id, r.getPapel(), r.getTermo(), r.getIndiceCommit(), r.getUltimoIndiceLog());
            } catch (StatusRuntimeException e) {
                canais.get(id).resetConnectBackoff();
                System.out.printf("NO=%d INDISPONIVEL%n", id);
            }
        }
        return 0;
    }

    private static int executarComando(String[] args) throws InterruptedException {
        String comando = args[0];

        if (comando.equals("estado")) {
            return imprimirEstadoCluster();
        }

        if (comando.equals("lider")) {
            obterLiderId();
            System.out.println("LIDER=" + liderId);
            return 0;
        }

        if (comando.equals("gravar") && args.length > 1) {
            obterLiderId();
            String texto = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            boolean sucesso = realizarGravacao(texto);
            System.out.println(sucesso ? "RESULTADO=SUCESSO" : "RESULTADO=FALHA");
            return sucesso ? 0 : 1;
        }

        if (comando.equals("gravar-em") && args.length > 2) {
            // Força o primeiro envio a um nó específico (testa o redirecionamento do seguidor)
            int id = Integer.parseInt(args[1]);
            if (!canais.containsKey(id)) {
                System.out.println("Nó desconhecido.");
                return 2;
            }
            liderId = id;
            String texto = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            boolean sucesso = realizarGravacao(texto);
            System.out.println(sucesso ? "RESULTADO=SUCESSO" : "RESULTADO=FALHA");
            return sucesso ? 0 : 1;
        }

        if ((comando.equals("consultar") || comando.equals("log")) && args.length > 1) {
            int id = Integer.parseInt(args[1]);
            return imprimirConsulta(id, comando.equals("log")) ? 0 : 1;
        }

        System.out.println("Uso: [estado | lider | gravar <texto> | gravar-em <id> <texto> | consultar <id> | log <id>]");
        return 2;
    }

    public static void main(String[] args) throws InterruptedException {
        canais = criarCanais(new ArrayList<>(List.of(1, 2, 3, 4)));

        if (args.length > 0) {
            int codigo;
            try {
                codigo = executarComando(args);
            } catch (IllegalStateException | NumberFormatException e) {
                System.out.println(e.getMessage());
                codigo = 2;
            } finally {
                canais.values().forEach(ManagedChannel::shutdown);
            }
            System.exit(codigo);
        }

        try {
            if (canais.isEmpty()) {
                throw new IllegalStateException("[ERRO] Não há nós configurados para consultar.");
            }

            obterLiderId();

            while (true) {
                mostrarMenu();
            }

        } finally {
            canais.values().forEach(ManagedChannel::shutdown);
        }
    }
}
