package com.facul.api;

import com.facul.noh.entity.EntradaLog;
import com.facul.noh.entity.Noh;
import com.facul.noh.entity.Estado;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;
import java.util.List;

import static com.facul.util.Constantes.TIMEOUT_COMMIT_MS;
import static com.facul.util.ServiceUtil.converterEntradasLogParaProto;
import static com.facul.util.ServiceUtil.responderOnError;

// Classe responsável por conter métodos implementados de comunicação entre cliente e um nó

public class ApiService extends ApiServiceGrpc.ApiServiceImplBase {

    private final Noh noh;

    public ApiService(Noh noh) {
        this.noh = noh;
    }

    @Override
    public void obterLiderId(Empty request, StreamObserver<ObterLiderIdResponse> responseObserver) {

        // Se o liderId for -1, então o nó não sabe quem é o líder
        ObterLiderIdResponse response = ObterLiderIdResponse.newBuilder()
                .setLiderId(noh.getLiderId())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void enviar(StreamObserver<RealizarGravacaoResponse> responseObserver,
                        RealizarGravacaoResponse.Builder resposta) {
        responseObserver.onNext(resposta.build());
        responseObserver.onCompleted();
    }

    @Override
    public void realizarGravacao(RealizarGravacaoRequest request, StreamObserver<RealizarGravacaoResponse> responseObserver) {
        RealizarGravacaoResponse.Builder resposta = RealizarGravacaoResponse.newBuilder();
        int indice;
        int termo;

        try {
            synchronized (noh) {
                // 1) Somente o líder aceita escrita
                if (noh.getEstado() != Estado.LIDER) {
                    if (noh.getLiderId() == -1) {
                        resposta.setResultado(ResultadoGravacao.LIDER_DESCONHECIDO);
                    } else {
                        resposta.setResultado(ResultadoGravacao.REDIRECIONAR)
                                .setLiderId(noh.getLiderId());
                    }
                    enviar(responseObserver, resposta);
                    return;
                }

                // 2) Sem quórum, sem escrita: nem grava no log
                if (!noh.temQuorumAtivo()) {
                    System.out.printf("[GRAVAÇÃO] Rejeitada: apenas %d réplica(s) ativa(s); sem quórum.%n",
                            noh.getReplicasAtivas());
                    resposta.setResultado(ResultadoGravacao.SEM_QUORUM);
                    enviar(responseObserver, resposta);
                    return;
                }

                // 3) Atribui termo atual + índice sequencial e grava no log local
                indice = noh.realizarGravacao(request.getGravacao());
                termo = noh.getTermo();
            }

            // 4) Só confirma ao cliente depois que o índice de commit alcançar a entrada
            boolean confirmado = noh.aguardarCommit(indice, termo, TIMEOUT_COMMIT_MS.value);

            if (!confirmado) {
                System.out.printf("[GRAVAÇÃO] Entrada %d não foi comitada a tempo; cliente não será confirmado.%n", indice);
            }

            resposta.setResultado(confirmado ? ResultadoGravacao.SUCESSO : ResultadoGravacao.SEM_QUORUM);

        } catch (SQLException e) {
            System.err.printf("[ERRO] Falha ao gravar no banco do nó %d: %s%n", noh.getId(), e.getMessage());
            resposta.setResultado(ResultadoGravacao.ERRO_GRAVACAO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resposta.setResultado(ResultadoGravacao.ERRO_GRAVACAO);
        }

        enviar(responseObserver, resposta);
    }

    @Override
    public void consultarNoh(ConsultarNohRequest request, StreamObserver<ConsultarNohResponse> responseObserver) {
        try {
            // Leitura eventual consistente: por padrão, somente dados comitados
            List<EntradaLog> entradas = request.getIncluirNaoComitados()
                    ? noh.getEntradasLog()
                    : noh.getDadosComitados();

            ConsultarNohResponse.Builder response = ConsultarNohResponse.newBuilder()
                    .addAllEntradas(converterEntradasLogParaProto(entradas))
                    .setIndiceCommit(noh.getIndiceCommit())
                    .setTermo(noh.getTermo())
                    .setPapel(noh.getEstado().name())
                    .setUltimoIndiceLog(noh.getUltimoIndiceLog());

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();

        } catch (SQLException e) {
            responderOnError(
                    responseObserver,
                    "[ERRO] Falha ao consultar o nó " + noh.getId(),
                    e
            );
        }
    }
}
