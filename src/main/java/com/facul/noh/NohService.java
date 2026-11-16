package com.facul.noh;

import com.facul.noh.entity.Noh;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;

public class NohService extends NohServiceGrpc.NohServiceImplBase {

    private final Noh noh;

    public NohService(Noh noh) {
        this.noh = noh;
    }

    @Override
    public synchronized void requestVote(RequestVoteRequest request, StreamObserver<RequestVoteResponse> responseObserver) {
        int termoAtualizado = Math.max(noh.getTermo(), request.getTermo());
        int votoAtualizado = request.getTermo() > noh.getTermo()
                ? -1
                : noh.getVotouId();

        boolean logCandidatoAtualizado =
                request.getUltimoTermoLog() > noh.getUltimoTermoLog() ||
                (request.getUltimoTermoLog() == noh.getUltimoTermoLog() &&
                request.getUltimoIndiceLog() >= noh.getUltimoIndiceLog());

        boolean concedeVoto = request.getTermo() == termoAtualizado
                && (votoAtualizado == -1 || votoAtualizado == request.getCandidateId())
                && logCandidatoAtualizado;

        if (concedeVoto) {
            votoAtualizado = request.getCandidateId();
        }

        try {
            if (termoAtualizado != noh.getTermo() || votoAtualizado != noh.getVotouId()) {
                noh.atualizarTermoEVotoPersistidos(termoAtualizado, votoAtualizado);
            }

            RequestVoteResponse response = RequestVoteResponse.newBuilder()
                    .setTermo(noh.getTermo())
                    .setVotou(concedeVoto)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (SQLException e) {
            System.err.println(
                "[ERRO] Não foi possível persistir o nó no banco durante requestVote: " +
                e.getMessage()
            );

            responseObserver.onError(Status.INTERNAL
                .withDescription("Falha ao persistir nó")
                .withCause(e)
                .asRuntimeException());
        }
    }
}
