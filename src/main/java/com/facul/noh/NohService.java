package com.facul.noh;

import io.grpc.stub.StreamObserver;

public class NohService extends NohServiceGrpc.NohServiceImplBase {

    @Override
    public void requestVote(RequestVoteRequest request, StreamObserver<RequestVoteResponse> responseObserver) {

    }
}
