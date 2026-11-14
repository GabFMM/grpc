package com.facul.cliente;

import com.facul.api.ApiServiceGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.facul.util.Util.criarCanais;

public class Main {
    private static int obterLiderId(Map<Integer, ManagedChannel> canais) throws InterruptedException {
        final int maxTentativas = 10;
        final int intervaloTentativas = 500; // em milissegundos

        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            for (ManagedChannel canal : canais.values()) {
                ApiServiceGrpc.ApiServiceBlockingStub stub =
                        ApiServiceGrpc.newBlockingStub(canal)
                                .withDeadlineAfter(2, TimeUnit.SECONDS);

                int liderId = stub.obterLiderId(Empty.getDefaultInstance()).getLiderId();
                if (liderId != -1) {
                    return liderId;
                }
            }

            if (tentativa < maxTentativas) {
                System.out.printf(
                        "[INFO] Líder ainda desconhecido; nova tentativa em %d ms (%d/%d).%n",
                        intervaloTentativas, tentativa, maxTentativas
                );
                Thread.sleep(intervaloTentativas);
            }
        }

        throw new IllegalStateException(
                "[ERRO] Nenhum nó informou o líder após " + maxTentativas + " tentativas."
        );
    }

    public static void main(String[] args) throws InterruptedException {
        Map<Integer, ManagedChannel> canais = criarCanais(
                new ArrayList<>(List.of(1, 2, 3, 4))
        );

        int liderId = obterLiderId(canais);

        canais.values().forEach(ManagedChannel::shutdown);
    }
}
