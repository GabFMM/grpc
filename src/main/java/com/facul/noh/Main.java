package com.facul.noh;

import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static void iniciarServidor(int porta) throws IOException, InterruptedException {
        Server server = ServerBuilder
                .forPort(porta)
                .addService(new Service())
                .build()
                .start();

        System.out.println("Nó iniciado na porta " + porta);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Encerrando nó");
            server.shutdown();
        }));

        server.awaitTermination();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Dotenv dotenv = Dotenv.load();
        int id = Integer.parseInt(dotenv.get("NOH_" + args[0] + "ID"));
        int porta = Integer.parseInt(dotenv.get("NOH_" + args[0] + "PORTA"));

        iniciarServidor(porta);

        Noh noh = new Noh(id);

        // O nó deve esperar a notificação do líder,
        // senão vira candidato
        ScheduledExecutorService escalonador = Executors.newScheduledThreadPool(1);

        Runnable tarefa = () -> {

        };

        escalonador.schedule(tarefa, noh.getDuracaoTemporizador(), TimeUnit.MILLISECONDS);
    }
}
