package com.facul.noh;

import com.facul.api.ApiService;
import com.facul.api.ApiServiceGrpc;
import com.facul.noh.entity.EntradaLog;
import com.facul.noh.entity.Noh;
import com.facul.noh.entity.Estado;
import com.facul.noh.entity.NohPersistente;
import com.google.protobuf.Empty;
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
import static com.facul.util.ServiceUtil.converterProtoParaEntradasLog;

public class Main {
    private static Noh noh;
    private static final int quorum = 3;

    private static final ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(1);
    private static final Runnable realizarEleicaoRunnable = Main::realizarEleicao;
    private static final Runnable realizarFetch = Main::realizarFetch;

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

    private static boolean descobrirLiderId() {
        for (Map.Entry<Integer, ManagedChannel> entrada : noh.getCanais().entrySet()) {
            try {
                // Mudar para NohService futuramente
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(entrada.getValue())
                                .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

                int liderId = stub.obterLiderId(Empty.getDefaultInstance()).getLiderId();
                if (liderId != -1 && noh.getCanais().containsKey(liderId)) {
                    noh.setLiderId(liderId);
                    return true;
                }
            }
            catch (StatusRuntimeException e) {
                System.err.printf(
                        "[AVISO] Nó (id %d) não respondeu (%s); tentando os demais nós para descobrir o líder.%n",
                        entrada.getKey(), e.getStatus().getCode()
                );
            }
        }
        return false;
    }

    private static void realizarEleicao() {
        try {
            noh.virarCandidato();
        }
        catch (SQLException e) {
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

    // Todo nó seguidor faz fetch
    private static void realizarFetch() {
        if (noh.getEstado() == Estado.LIDER)
            return;

        ManagedChannel canalLider = noh.getCanalLider();

        if (canalLider == null)
            if (!descobrirLiderId())
                return; // Espero eleição, pois não há líder
            else
                canalLider = noh.getCanalLider();

        try {
            NohServiceGrpc.NohServiceBlockingStub stub =
                    NohServiceGrpc.newBlockingStub(canalLider)
                            .withDeadlineAfter(DEADLINE_SEGUNDOS.value, TimeUnit.SECONDS);

            FetchRequest request = FetchRequest.newBuilder()
                    .setSeguidorId(noh.getId())
                    .setTermo(noh.getTermo())
                    .setUltimoIndiceLog(noh.getUltimoIndiceLog())
                    .setUltimoTermoLog(noh.getUltimoTermoLog())
                    .build();

            FetchResponse response = stub.fetch(request);

            int maiorTermo = Math.max(noh.getTermo(), response.getTermo());
            if (maiorTermo > noh.getTermo()) {
                try {
                    noh.virarSeguidor(maiorTermo);
                } catch (SQLException e) {
                    System.err.printf(
                            "[ERRO] Não foi possível mudar o estado do nó para seguidor (id %d): %s%n",
                            noh.getId(), e.getMessage()
                    );
                    return;
                }
            }

            if (response.hasRedirecionamento()) {
                FetchRedirect redirect = response.getRedirecionamento();
                noh.setLiderId(redirect.getLiderId());
            }
            else if (response.hasDados()) {
                FetchData data = response.getDados();

                if (data.getPrefixoValido()) {
                    try {
                        for (EntradaLog entrada : converterProtoParaEntradasLog(data.getEntradasList()))
                            noh.adicionarEntradaLog(entrada);
                    }
                    catch (SQLException e) {
                        System.err.printf(
                                "[ERRO] Não foi possível adicionar uma entrada ao log do nó seguidor (id %d): %s%n",
                                noh.getId(), e.getMessage()
                        );
                        return;
                    }
                }
                else {
                    try {
                        noh.truncarEntradas(data.getTruncarIndice());

                        for (EntradaLog entrada : converterProtoParaEntradasLog(data.getEntradasList()))
                            noh.adicionarEntradaLog(entrada);
                    }
                    catch (SQLException e) {
                        System.err.printf(
                                "[ERRO] Naõ foi possível adicionar uma entrada ao log do nó seguidor (id %d): %s%n",
                                noh.getId(), e.getMessage()
                        );
                        return;
                    }
                }
                try {
                    noh.setIndiceCommit(data.getIndiceCommit());
                } catch (SQLException e) {
                    System.err.printf(
                            "[ERRO] Não foi possível persistir o índice de commit do nó %d: %s%n",
                            noh.getId(), e.getMessage()
                    );
                }
            }
        }
        catch (StatusRuntimeException e) {
            System.err.printf(
                    "[AVISO] Nó líder (id %d) não respondeu o fetch request (%s).%n",
                    noh.getLiderId(), e.getStatus().getCode()
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
        escalonador.scheduleAtFixedRate(Main::realizarFetch, 0, INTERVALO_FETCH_MS.value, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Encerrando nó");
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
