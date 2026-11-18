package com.facul.util;

import com.facul.noh.entity.EntradaLog;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

public class ServiceUtil {

    public static List<com.facul.proto.EntradaLog> converterEntradasLogParaProto(List<EntradaLog> entradas) {
        List<com.facul.proto.EntradaLog> entradasProto = new ArrayList<>(entradas.size());

        for (EntradaLog entrada : entradas) {
            entradasProto.add(com.facul.proto.EntradaLog.newBuilder()
                    .setIndice(entrada.indice())
                    .setTermo(entrada.termo())
                    .setComando(entrada.comando())
                    .build());
        }

        return entradasProto;
    }

    public static List<EntradaLog> converterProtoParaEntradasLog(List<com.facul.proto.EntradaLog> entradasProto) {
        List<EntradaLog> entradas = new ArrayList<>(entradasProto.size());

        for (com.facul.proto.EntradaLog proto : entradasProto) {
            entradas.add(new EntradaLog(proto.getIndice(), proto.getTermo(), proto.getComando()));
        }

        return entradas;
    }

    public static <T> void responderOnError(
            StreamObserver<T> responseObserver,
            String mensagem,
            Throwable excecao
    ) {
        System.err.printf("[ERRO] %s: %s%n", mensagem, excecao.getMessage());

        responseObserver.onError(Status.INTERNAL
                .withDescription(mensagem)
                .withCause(excecao)
                .asRuntimeException());
    }
}
