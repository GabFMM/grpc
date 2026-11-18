package com.facul.noh;

import com.facul.api.ApiService;
import com.facul.noh.entity.Estado;
import com.facul.noh.entity.Noh;
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
import java.util.ArrayList;
import java.util.Collections;
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

    // 3 threads: monitor de eleição (pode bloquear durante uma eleição), fetch e status
    private static final ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(3);
    private static final Random random = new Random();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Temporizador de eleição aleatório: cada nó sorteia o seu próprio valor a cada reinício
    private static volatile long expiraEm = 0;

    // Usado somente pela thread de fetch
    private static int proximaSondagem = 0;
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
    // Temporizador e eleição
    // ------------------------------------------------------------------

    private static void reiniciarTemporizador() {
        // Entre 1,5 e 3 s
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
            // scheduleWithFixedDelay para de executar se a tarefa lançar exceção
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

        for (Map.Entry<Integer, ManagedChannel> entrada : noh.getCanais().entrySet()) {
            // Se já deixou de ser candidato desse termo (ex.: viu termo maior), desiste
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

    private static int escolherAlvoFetch() {
        int lider = noh.getLiderId();
        if (lider != -1 && lider != noh.getId() && noh.getCanais().containsKey(lider)) {
            return lider;
        }

        // Líder desconhecido: sonda os demais nós em rodízio.
        // Quem não é líder responde com um redirecionamento (e, se souber, o id do líder).
        List<Integer> ids = new ArrayList<>(noh.getCanais().keySet());
        if (ids.isEmpty()) {
            return -1;
        }
        Collections.sort(ids);
        return ids.get(proximaSondagem++ % ids.size());
    }

    private static boolean replicacaoIgnorada() {
        // Ferramenta de teste: se o arquivo existir, o nó continua enviando FetchRequest
        // (heartbeat), mas NÃO armazena as entradas nem avança o commit.
        return Files.exists(Path.of("data", "ignorar-replicacao-" + noh.getId()));
    }

    private static void executarFetch() {
        try {
            if (noh.getEstado() == Estado.LIDER) {
                return;
            }

            int alvo;
            FetchRequest request;
            synchronized (noh) {
                alvo = escolherAlvoFetch();
                request = FetchRequest.newBuilder()
                        .setSeguidorId(noh.getId())
                        .setTermo(noh.getTermo())
                        .setUltimoIndiceLog(noh.getUltimoIndiceLog())
                        .setUltimoTermoLog(noh.getUltimoTermoLog())
                        .build();
            }

            if (alvo == -1) {
                return;
            }

            ManagedChannel canal = noh.getCanais().get(alvo);
            FetchResponse resposta;

            try {
                resposta = NohServiceGrpc.newBlockingStub(canal)
                        .withDeadlineAfter(DEADLINE_NOH_MS.value, TimeUnit.MILLISECONDS)
                        .fetch(request);
            } catch (StatusRuntimeException e) {
                // Líder inalcançável: esquece o líder. O temporizador de eleição NÃO é
                // reiniciado, portanto expira e inicia uma eleição.
                noh.esquecerLider(alvo);
                canal.resetConnectBackoff();
                return;
            }

            if (resposta.hasRedirecionamento()) {
                FetchRedirect redirect = resposta.getRedirecionamento();
                noh.processarRedirecionamento(
                        redirect.getTermo(),
                        redirect.hasLiderId() ? redirect.getLiderId() : -1
                );
                return;
            }

            FetchData dados = resposta.getDados();
            boolean aceito;

            if (replicacaoIgnorada()) {
                // Somente heartbeat: confirma o líder, mas não armazena nada
                aceito = noh.aplicarFetchLider(alvo, dados.getTermo(), 0, true, null, List.of());
            } else {
                aceito = noh.aplicarFetchLider(
                        alvo,
                        dados.getTermo(),
                        dados.getIndiceCommit(),
                        dados.getPrefixoValido(),
                        dados.hasTruncarIndice() ? dados.getTruncarIndice() : null,
                        converterProtoParaEntradasLog(dados.getEntradasList())
                );
            }

            // Fetch bem-sucedido: reinicia o temporizador aleatório
            if (aceito) {
                reiniciarTemporizador();
            }

        } catch (SQLException e) {
            System.err.printf("[ERRO] Nó %d falhou ao aplicar fetch: %s%n", noh.getId(), e.getMessage());
        } catch (Throwable t) {
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
        escalonador.scheduleWithFixedDelay(Main::executarFetch,
                INTERVALO_FETCH_MS.value, INTERVALO_FETCH_MS.value, TimeUnit.MILLISECONDS);
        escalonador.scheduleWithFixedDelay(Main::imprimirStatusSeMudou, 200, 200, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando nó");
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
