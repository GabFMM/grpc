package com.facul.cliente;

import com.facul.api.ApiServiceGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.facul.util.Util.criarCanais;

public class Main {
    private static int obterLiderId(Map<Integer, ManagedChannel> canais) {
        ApiServiceGrpc.ApiServiceBlockingStub stub =
                ApiServiceGrpc.newBlockingStub(canais.get(1));

        return stub.obterLiderId(Empty.newBuilder().build()).getLiderId();
    }

    public static void main(String[] args) {
        Map<Integer, ManagedChannel> canais = criarCanais(new ArrayList<>(List.of(1, 2, 3, 4)));

        int liderId = obterLiderId(canais);


    }
}
