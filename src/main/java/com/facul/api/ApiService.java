package com.facul.api;

import com.facul.noh.Noh;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;

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
}