package com.facul.noh;

import com.facul.api.ApiService;
import com.facul.noh.entity.Noh;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facul.util.Util.getValorEnv;

public class Main {
    private static Server iniciarServidor(int porta, Noh noh) throws IOException {
        Server server = ServerBuilder
                .forPort(porta)
                .addService(new NohService(noh))
                .addService(new ApiService(noh))
                .build()
                .start();

        System.out.println("Nó iniciado na porta " + porta);

        return server;
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // Preparativos
        final int quorum = 3;
        int numeroNoh = Integer.parseInt(args[0]);
        int id = getValorEnv("ID", numeroNoh);
        int porta = getValorEnv("PORTA", numeroNoh);

        // Inicializa o nó com segurança
        Noh noh = new Noh(numeroNoh, id);
        Server server = iniciarServidor(porta, noh);

        // O nó deve esperar a notificação do líder,
        // senão vira candidato
        ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(1);

        Runnable tarefa = () -> {
            noh.virarCandidato();

            int votosPositivos = 0;

            // Notifica os demais nós sobre a candidatura
            for (ManagedChannel canal : noh.getCanais().values()) {
                NohServiceGrpc.NohServiceBlockingStub stub =
                        NohServiceGrpc.newBlockingStub(canal);

                RequestVoteRequest request = RequestVoteRequest
                        .newBuilder()
                        .setCandidateId(noh.getId())
                        .setTermo(noh.getTermo())
                        .setUltimoIndiceLog(noh.getUltimoIndiceLog())
                        .setUltimoTermoLog(noh.getUltimoTermoLog())
                        .build();

                RequestVoteResponse response = stub.requestVote(request);

                if (response.getVotou()) {
                    votosPositivos++;
                }
                else if (response.getTermo() > noh.getTermo()) {
                    noh.virarSeguidor(response.getTermo());
                    break;
                }
            }

            if (votosPositivos >= quorum)
                noh.virarLider();
        };

        escalonador.schedule(tarefa, noh.getDuracaoTemporizador(), TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando nó");
            escalonador.shutdownNow();
            noh.getCanais().values().forEach(ManagedChannel::shutdown);
            server.shutdown();
        }));

        try {
            // O servidor fica aguardando enquanto o escalonador executa a eleição.
            server.awaitTermination();
        } finally {
            escalonador.shutdownNow();
            noh.getCanais().values().forEach(ManagedChannel::shutdown);
            server.shutdown();
        }
    }
}
