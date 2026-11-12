package com.facul.cliente;

import com.facul.hello.HelloRequest;
import com.facul.hello.HelloResponse;
import com.facul.hello.HelloServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class Main {
    public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        HelloServiceGrpc.HelloServiceBlockingStub stub =
                HelloServiceGrpc.newBlockingStub(channel);

        HelloRequest request = HelloRequest
                .newBuilder()
                .setMessage("Olá!")
                .build();

        HelloResponse response = stub.hello(request);
        System.out.println(response.getMessage());
    }
}
