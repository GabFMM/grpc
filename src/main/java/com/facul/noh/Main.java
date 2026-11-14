package com.facul.noh;

import com.facul.api.ApiService;
import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facul.util.Util.criarCanais;
import static com.facul.util.Util.getValorEnv;

public class Main {
    private static List<Integer> gerarNohNums(int nohNumExcluido) {
        List<Integer> nohNums = new ArrayList<>();

        for (int i = 1; i <= 4; i++) {
            if (i != nohNumExcluido) {
                nohNums.add(i);
            }
        }

        return nohNums;
    }

    private static void iniciarServidor(int porta, Noh noh) throws IOException, InterruptedException {
        Server server = ServerBuilder
                .forPort(porta)
                .addService(new NohService())
                .addService(new ApiService(noh))
                .build()
                .start();

        System.out.println("Nó iniciado na porta " + porta);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando nó");
            server.shutdown();
        }));

        server.awaitTermination();
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // Preparativos
        final int quorum = 3;
        int numeroNoh = Integer.parseInt(args[0]);
        int id = getValorEnv("ID", numeroNoh);
        int porta = getValorEnv("PORTA", numeroNoh);

        Map<Integer, ManagedChannel> canais = criarCanais(gerarNohNums(numeroNoh));

        Database.inicializar();

        // Inicializa o nó com segurança
        Noh noh = new Noh(id);
        iniciarServidor(porta, noh);

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
                        .setTermo(noh.getTermo())
                        .setUltimoIndiceLog(noh.getIndiceLog())
                        .setUltimoTermoLog(noh.getTermoLog())
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
    }
}
