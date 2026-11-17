package com.facul.api;

import com.facul.noh.entity.EntradaLog;
import com.facul.noh.entity.Noh;
import com.facul.noh.entity.Estado;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;
import java.util.List;

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

    @Override
    public void realizarGravacao(RealizarGravacaoRequest request, StreamObserver<RealizarGravacaoResponse> responseObserver) {
        if (noh.getEstado() != Estado.LIDER) {
            RealizarGravacaoResponse.Builder resposta = RealizarGravacaoResponse.newBuilder();
            if (noh.getLiderId() == -1) {
                resposta.setResultado(ResultadoGravacao.LIDER_DESCONHECIDO);
            } else {
                resposta.setResultado(ResultadoGravacao.REDIRECIONAR)
                        .setLiderId(noh.getLiderId());
            }
            responseObserver.onNext(resposta.build());
            responseObserver.onCompleted();
            return;
        }

        try {
            noh.realizarGravacao(request.getGravacao());
            responseObserver.onNext(RealizarGravacaoResponse.newBuilder()
                    .setResultado(ResultadoGravacao.SUCESSO)
                    .build());
            responseObserver.onCompleted();
        } catch (SQLException e) {
            System.err.printf("[ERRO] Falha ao gravar no banco do nó %d: %s%n", noh.getId(), e.getMessage());

            responseObserver.onNext(RealizarGravacaoResponse.newBuilder()
                    .setResultado(ResultadoGravacao.ERRO_GRAVACAO)
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void consultarNoh(ConsultarNohRequest request, StreamObserver<ConsultarNohResponse> responseObserver) {
        try {
            List<EntradaLog> entradas = noh.getEntradasLog();

            ConsultarNohResponse.Builder response = ConsultarNohResponse.newBuilder();

            response.addAllEntradas(converterEntradasLogParaProto(entradas));

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();

        } catch (SQLException e) {
            responderOnError(
                    responseObserver,
                    "[ERRO] Falha ao consultar o log do nó " + noh.getId(),
                    e
            );
        }
    }
}
