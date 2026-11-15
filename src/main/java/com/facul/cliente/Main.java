package com.facul.cliente;

import com.facul.api.*;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import static com.facul.cliente.Constantes.*;
import static com.facul.util.Util.criarCanais;

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
                "[ERRO] Nenhum nó informou o líder após " + MAX_TENTATIVAS + " tentativas."
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
        System.out.println("2 - Consultar gravações em um nó especifíco.");

        int opcao = lerInteiro();

        if (opcao == 1) {
            mostrarTelaGravacao();
        } else if (opcao == 2) {
            mostrarTelaConsulta();
        }
    }

    private static void mostrarTelaGravacao() {
        System.out.println("Digite a gravação:");
        String gravacao = scanner.nextLine();

        boolean sucesso = realizarGravacao(gravacao);

        limparBuffer();

        if (sucesso) {
            System.out.println("Feita a gravação!");
        }
        System.out.println("Digite qualquer tecla para voltar ao menu.");
        scanner.nextLine();
    }

    private static boolean realizarGravacao(String gravacao) {
        final int maxRedirecionamentos = 5;

        for (int tentativa = 0; tentativa < maxRedirecionamentos; tentativa++) {
            ManagedChannel canalLider = canais.get(liderId);
            if (canalLider == null) {
                if (!atualizarLiderId()) {
                    aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                    continue;
                }
                canalLider = canais.get(liderId);
            }

            try {
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(canalLider)
                                .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

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
                        } else if (!atualizarLiderId()) {
                            aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                        }
                    }
                    case LIDER_DESCONHECIDO -> {
                        if (!atualizarLiderId()) {
                            aguardarNovaTentativa(INTERVALO_TENTATIVAS_MS.value);
                        }
                    }
                    case ERRO_GRAVACAO -> {
                        System.out.println("Erro no servidor; tente novamente.");
                        return false;
                    }
                }
            } catch (StatusRuntimeException e) {
                System.out.println("Erro no servidor; tente novamente.");
                return false;
            }
        }

        System.out.println("Não foi possível localizar o líder. Tente novamente.");
        return false;
    }

    private static void aguardarNovaTentativa(int intervaloMs) {
        try {
            Thread.sleep(intervaloMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void mostrarTelaConsulta() {
        mostrarNohs();

        System.out.println("Digite o ID do nó a ser consultado:");
        int id = lerInteiro();

        ConsultarNohResponse response = consultarNoh(id);

        if (response != null) {
            System.out.println("Log:");
            for (EntradaLog entrada : response.getEntradasList())
                System.out.printf("- Indice: %d | Termo: %d | Comando: %s%n",
                        entrada.getIndice(),
                        entrada.getTermo(),
                        entrada.getComando());
        }

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

    private static ConsultarNohResponse consultarNoh(int id) {
        try {
            ApiServiceGrpc.ApiServiceBlockingStub stub =
                    ApiServiceGrpc.newBlockingStub(canais.get(id))
                            .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

            ConsultarNohRequest request = ConsultarNohRequest.newBuilder()
                    .setNohId(id)
                    .build();

            return stub.consultarNoh(request);

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.INTERNAL) {
                System.out.println("O nó não conseguiu consultar o log.");
            } else {
                System.out.println("Não foi possível se comunicar com o nó.");
            }

            return null;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        canais = criarCanais(new ArrayList<>(List.of(1, 2, 3, 4)));

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
