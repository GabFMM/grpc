package com.facul.noh;

import com.facul.api.ApiService;
import com.facul.noh.entity.Noh;
import com.facul.noh.entity.Estado;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facul.util.ClienteNohUtil.getValorEnv;
import static com.facul.util.Constantes.*;

public class Main {
    private static Noh noh;
    private static final int quorum = 3;

    private static final ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(1);
    private static final Runnable realizarEleicaoRunnable = Main::realizarEleicao;

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

    private static int sortearDuracaoTemporizador() {
        // Todo nó deve conter um temporizador único
        Random random = new Random();

        // 2 segundos
        int tempoBase = 2000;

        // até 4 segundos adicionais
        int tempoAdicional = random.nextInt(4000);

        // Duração será um valor entre 2 e 6 segundos
        return tempoBase + tempoAdicional;
    }

    private static void realizarEleicao() {
        try {
            noh.virarCandidato();
        } catch (SQLException e) {
            System.err.printf(
                    "[ERRO] Nó, id %d, não conseguiu virar candidato: %s%n",
                    noh.getId(),
                    e.getMessage()
            );

            escalonador.schedule(
                    realizarEleicaoRunnable,
                    sortearDuracaoTemporizador(),
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        // O candidato vota em si próprio
        int votosPositivos = 1;

        // Notifica os demais nós sobre a candidatura
        for (Map.Entry<Integer, ManagedChannel> entrada : noh.getCanais().entrySet()) {
            try {
                NohServiceGrpc.NohServiceBlockingStub stub =
                        NohServiceGrpc.newBlockingStub(entrada.getValue())
                                .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

                RequestVoteRequest request = RequestVoteRequest
                        .newBuilder()
                        .setCandidateId(noh.getId())
                        .setTermo(noh.getTermo())
                        .setUltimoIndiceLog(noh.getUltimoIndiceLog())
                        .setUltimoTermoLog(noh.getUltimoTermoLog())
                        .build();

                RequestVoteResponse response = stub.requestVote(request);

                if (response.getTermo() > noh.getTermo()) {
                    try {
                        noh.virarSeguidor(response.getTermo());
                    }
                    catch (SQLException e) {
                        System.err.printf(
                                "[ERRO] Nó, id %d, não conseguiu virar seguidor: %s%n",
                                noh.getId(),
                                e.getMessage()
                        );

                        escalonador.schedule(
                                realizarEleicaoRunnable,
                                sortearDuracaoTemporizador(),
                                TimeUnit.MILLISECONDS
                        );

                        return;
                    }
                    // Ao sair do loop, no fim do metodo, será agendada uma nova eleição
                    break;
                }

                if (response.getVotou()) {
                    votosPositivos++;
                }

            } catch (StatusRuntimeException e) {
                System.err.printf(
                        "[AVISO] Nó (id %d) não respondeu (%s) durante eleição; tentando os demais nós.%n",
                        entrada.getKey(), e.getStatus().getCode()
                );
            }
        }

        if (noh.getEstado() == Estado.CANDIDATO && votosPositivos >= quorum) {
            noh.virarLider();
        }
        else {
            escalonador.schedule(
                    realizarEleicaoRunnable,
                    sortearDuracaoTemporizador(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // Preparativos
        int numeroNoh = Integer.parseInt(args[0]);
        int id = getValorEnv("ID", numeroNoh);
        int porta = getValorEnv("PORTA", numeroNoh);

        // Inicializa o nó com segurança
        noh = new Noh(numeroNoh, id);
        Server server = iniciarServidor(porta, noh);

        escalonador.schedule(realizarEleicaoRunnable, sortearDuracaoTemporizador(), TimeUnit.MILLISECONDS);

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
