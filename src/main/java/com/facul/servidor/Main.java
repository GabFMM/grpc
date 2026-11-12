package com.facul.servidor;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 50051;

        Server server = ServerBuilder.forPort(port)
                .addService(new HelloServiceImpl())
                .build()
                .start();

        System.out.println("Servidor gRPC iniciado na porta " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando servidor gRPC...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
