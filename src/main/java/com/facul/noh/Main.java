package com.facul.noh;

import com.facul.api.ApiService;
import com.facul.api.ApiServiceGrpc;
import com.facul.noh.entity.Estado;
import com.facul.noh.entity.Noh;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    // 3 threads: monitor de eleição (pode bloquear durante uma eleição), fetch e status.
    // Assim a eleição não atrasa o fetch (heartbeat) nem a exibição.
    private static final ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(3);
    private static final Random random = new Random();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Instante em que o temporizador de eleição expira. Cada nó sorteia o seu valor a cada reinício.
    private static volatile long expiraEm = 0;

    private static String ultimoStatus = "";

    private static Server iniciarServidor(int porta, Noh noh) throws IOException {
        Server server = ServerBuilder
                .forPort(porta)
                .addService(new NohService(noh, Main::reiniciarTemporizador))
                .addService(new ApiService(noh))
                .build()
                .start();

        System.out.println("Nó iniciado na porta " + porta);

        return server;
    }

    // ------------------------------------------------------------------
    // Temporizador aleatório e eleição
    // ------------------------------------------------------------------

    private static void reiniciarTemporizador() {
        // Entre 2 e 4 s: nós diferentes expiram em momentos diferentes (evita split vote)
        expiraEm = System.currentTimeMillis()
                + ELEICAO_TIMEOUT_BASE_MS.value
                + random.nextInt(ELEICAO_TIMEOUT_ALEATORIO_MS.value);
    }

    private static void monitorarEleicao() {
        try {
            if (noh.getEstado() == Estado.LIDER) {
                // Se perder a liderança, começa com um temporizador novo
                reiniciarTemporizador();
                return;
            }

            if (System.currentTimeMillis() >= expiraEm) {
                realizarEleicao();
            }
        } catch (Throwable t) {
            // scheduleWithFixedDelay deixa de executar se a tarefa lançar exceção
            System.err.println("[ERRO] Monitor de eleição: " + t);
        }
    }

    private static void realizarEleicao() {
        int termoEleicao;
        int ultimoIndiceLog;
        int ultimoTermoLog;

        synchronized (noh) {
            try {
                termoEleicao = noh.virarCandidato();
            } catch (SQLException e) {
                System.err.printf(
                        "[ERRO] Nó, id %d, não conseguiu virar candidato: %s%n",
                        noh.getId(),
                        e.getMessage()
                );
                reiniciarTemporizador();
                return;
            }

            ultimoIndiceLog = noh.getUltimoIndiceLog();
            ultimoTermoLog = noh.getUltimoTermoLog();
        }

        // Se esta rodada falhar, o próximo temporizador (aleatório) dispara outra eleição
        reiniciarTemporizador();

        System.out.printf("[ELEIÇÃO] Nó %d virou CANDIDATO no termo %d%n", noh.getId(), termoEleicao);

        // O candidato vota em si próprio
        int votosPositivos = 1;

        // Notifica os demais nós sobre a candidatura
        for (Map.Entry<Integer, ManagedChannel> entrada : noh.getCanais().entrySet()) {
            // Se deixou de ser candidato deste termo (ex.: viu um termo maior), desiste
            if (noh.getEstado() != Estado.CANDIDATO || noh.getTermo() != termoEleicao) {
                return;
            }

            try {
                NohServiceGrpc.NohServiceBlockingStub stub =
                        NohServiceGrpc.newBlockingStub(entrada.getValue())
                                .withDeadlineAfter(DEADLINE_NOH_MS.value, TimeUnit.MILLISECONDS);

                RequestVoteRequest request = RequestVoteRequest
                        .newBuilder()
                        .setCandidateId(noh.getId())
                        .setTermo(termoEleicao)
                        .setUltimoIndiceLog(ultimoIndiceLog)
                        .setUltimoTermoLog(ultimoTermoLog)
                        .build();

                RequestVoteResponse response = stub.requestVote(request);

                if (response.getTermo() > termoEleicao) {
                    try {
                        noh.virarSeguidor(response.getTermo());
                    } catch (SQLException e) {
                        System.err.printf(
                                "[ERRO] Nó, id %d, não conseguiu virar seguidor: %s%n",
                                noh.getId(),
                                e.getMessage()
                        );
                    }
                    return;
                }

                if (response.getVotou()) {
                    votosPositivos++;
                }

            } catch (StatusRuntimeException e) {
                System.err.printf(
                        "[AVISO] Nó (id %d) não respondeu (%s) durante eleição; tentando os demais nós.%n",
                        entrada.getKey(), e.getStatus().getCode()
                );
                // Evita ficar preso no backoff de reconexão quando o nó voltar
                entrada.getValue().resetConnectBackoff();
            }
        }

        if (votosPositivos >= QUORUM.value && noh.tentarVirarLider(termoEleicao)) {
            System.out.printf("[ELEIÇÃO] Nó %d virou LÍDER no termo %d com %d votos%n",
                    noh.getId(), termoEleicao, votosPositivos);
        }
    }

    // ------------------------------------------------------------------
    // Loop de fetch das réplicas (modelo Pull)
    // ------------------------------------------------------------------

    private static boolean descobrirLiderId() {
        for (Map.Entry<Integer, ManagedChannel> entrada : noh.getCanais().entrySet()) {
            try {
                // Mudar para NohService futuramente
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(entrada.getValue())
                                .withDeadlineAfter(DEADLINE_NOH_MS.value, TimeUnit.MILLISECONDS);

                int liderId = stub.obterLiderId(Empty.getDefaultInstance()).getLiderId();
                if (liderId != -1 && noh.getCanais().containsKey(liderId)) {
                    noh.setLiderId(liderId);
                    return true;
                }
            }
            catch (StatusRuntimeException e) {
                entrada.getValue().resetConnectBackoff();
            }
        }
        return false;
    }

    private static boolean replicacaoIgnorada() {
        // Ferramenta de teste: se o arquivo existir, o nó continua enviando FetchRequest
        // (heartbeat), mas NÃO armazena as entradas nem avança o commit.
        return Files.exists(Path.of("data", "ignorar-replicacao-" + noh.getId()));
    }

    // Todo nó seguidor (ou candidato) faz fetch
    private static void realizarFetch() {
        try {
            if (noh.getEstado() == Estado.LIDER)
                return;

            ManagedChannel canalLider = noh.getCanalLider();

            if (canalLider == null) {
                if (!descobrirLiderId())
                    return; // Espero eleição, pois não há líder
                canalLider = noh.getCanalLider();
                if (canalLider == null)
                    return;
            }

            int liderAlvo = noh.getLiderId();

            FetchRequest request;
            synchronized (noh) {
                request = FetchRequest.newBuilder()
                        .setSeguidorId(noh.getId())
                        .setTermo(noh.getTermo())
                        .setUltimoIndiceLog(noh.getUltimoIndiceLog())
                        .setUltimoTermoLog(noh.getUltimoTermoLog())
                        .build();
            }

            FetchResponse response;
            try {
                response = NohServiceGrpc.newBlockingStub(canalLider)
                        .withDeadlineAfter(DEADLINE_NOH_MS.value, TimeUnit.MILLISECONDS)
                        .fetch(request);
            } catch (StatusRuntimeException e) {
                // Líder inalcançável: esquece o líder. O temporizador de eleição NÃO é
                // reiniciado, portanto expira e inicia uma eleição.
                noh.esquecerLider(liderAlvo);
                canalLider.resetConnectBackoff();
                return;
            }

            if (response.hasRedirecionamento()) {
                // Quem eu achava que era líder não é mais
                noh.esquecerLider(liderAlvo);
                noh.processarRedirecionamento(
                        response.getTermo(),
                        response.getRedirecionamento().getLiderId()
                );
                return;
            }

            FetchData data = response.getDados();
            boolean aceito;

            if (replicacaoIgnorada()) {
                // Somente heartbeat: confirma o líder, mas não armazena nada
                aceito = noh.aplicarFetchLider(liderAlvo, response.getTermo(), 0, true, null, List.of());
            } else {
                aceito = noh.aplicarFetchLider(
                        liderAlvo,
                        response.getTermo(),
                        data.getIndiceCommit(),
                        data.getPrefixoValido(),
                        data.hasTruncarIndice() ? data.getTruncarIndice() : null,
                        converterProtoParaEntradasLog(data.getEntradasList())
                );
            }

            // Fetch bem-sucedido: reinicia o temporizador aleatório de eleição
            if (aceito) {
                reiniciarTemporizador();
            }

        } catch (SQLException e) {
            System.err.printf("[ERRO] Nó %d falhou ao aplicar o fetch: %s%n", noh.getId(), e.getMessage());
        } catch (Throwable t) {
            // scheduleWithFixedDelay deixa de executar se a tarefa lançar exceção
            System.err.println("[ERRO] Loop de fetch: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Exibição: papel, termo e índice de commit (impresso quando algo muda)
    // ------------------------------------------------------------------

    private static void imprimirStatusSeMudou() {
        try {
            String status = noh.descricaoStatus();
            if (!status.equals(ultimoStatus)) {
                ultimoStatus = status;
                System.out.println(LocalTime.now().format(HORA) + " " + status);
            }
        } catch (Throwable t) {
            System.err.println("[ERRO] Status: " + t);
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

        reiniciarTemporizador();
        escalonador.scheduleWithFixedDelay(Main::monitorarEleicao, 50, 50, TimeUnit.MILLISECONDS);
        escalonador.scheduleWithFixedDelay(Main::realizarFetch, 0, INTERVALO_FETCH_MS.value, TimeUnit.MILLISECONDS);
        escalonador.scheduleWithFixedDelay(Main::imprimirStatusSeMudou, 200, 200, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Encerrando nó");
            escalonador.shutdownNow();
            noh.getCanais().values().forEach(ManagedChannel::shutdown);
            server.shutdown();
        }));

        try {
            server.awaitTermination();
        } finally {
            escalonador.shutdownNow();
            noh.getCanais().values().forEach(ManagedChannel::shutdown);
            server.shutdown();
        }
    }
}
