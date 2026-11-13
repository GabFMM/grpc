package com.facul.noh;

import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static void iniciarServidor(int porta) throws IOException, InterruptedException {
        Server server = ServerBuilder
                .forPort(porta)
                .addService(new Service())
                .build()
                .start();

        System.out.println("Nó iniciado na porta " + porta);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando nó");
            server.shutdown();
        }));

        server.awaitTermination();
    }

    private static Map<Integer, ManagedChannel> criarCanais(int id) {
        Map<Integer, ManagedChannel> canais = new HashMap<>();

        Dotenv dotenv = Dotenv.load();
        for (int i = 1; i <= 4; i++) {
            if (i != id) {
                String porta = dotenv.get("NOH_" + i + "_PORTA");

                // Se não tiver porta, não há problema,
                // pois o programa deve funcionar com menos de 4 nós
                if (porta != null) {

                    // Usa o getValorEnv, pois sem o idAtual não há como identificar o nó,
                    // logo trata como erro
                    int idAtual = getValorEnv("ID", i);

                    ManagedChannel canal = ManagedChannelBuilder
                        .forAddress("localhost", Integer.parseInt(porta))
                        .usePlaintext()
                        .build();

                    canais.put(idAtual, canal);
                }
            }
        }

        return canais;
    }

    private static int getValorEnv(String nomeCampo, int numeroNoh) {
        Dotenv dotenv = Dotenv.load();

        String str = dotenv.get(String.format("NOH_%d_%s", numeroNoh, nomeCampo));

        if (str == null)
            throw new RuntimeException(
                    String.format(
                            "[ERRO] Não foi possível recuperar o %s do nó %d no .env",
                            nomeCampo,
                            numeroNoh
                    )
            );
        else
            return Integer.parseInt(str);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final int quorum = 3;
        int numeroNoh = Integer.parseInt(args[0]);
        int id = getValorEnv("ID", numeroNoh);
        int porta = getValorEnv("PORTA", numeroNoh);

        iniciarServidor(porta);
        Map<Integer, ManagedChannel> canais = criarCanais(id);

        Noh noh = new Noh(id);

        // O nó deve esperar a notificação do líder,
        // senão vira candidato
        ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(1);

        Runnable tarefa = () -> {
            noh.virarCandidato();

            int votosPositivos = 0;

            // Notifica os demais nós sobre a candidatura
            for (ManagedChannel canal : canais.values()) {
                NohServiceGrpc.NohServiceBlockingStub stub =
                        NohServiceGrpc.newBlockingStub(canal);

                RequestVoteRequest request = RequestVoteRequest
                        .newBuilder()
                        .setCandidateId(noh.getId())
                        .setTerm(noh.getTermo())
                        .setLastLogIndex(noh.getIndiceLog())
                        .setLastLogTerm(noh.getTermoLog())
                        .build();

                RequestVoteResponse response = stub.requestVote(request);

                if (response.getVoteGranted()) {
                    votosPositivos++;
                }
                else if (response.getTerm() > noh.getTermo()) {
                    noh.virarSeguidor(response.getTerm());
                    break;
                }
            }

            if (votosPositivos >= quorum)
                noh.virarLider();
        };

        escalonador.schedule(tarefa, noh.getDuracaoTemporizador(), TimeUnit.MILLISECONDS);
    }
}
