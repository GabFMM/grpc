package com.facul.noh;

import com.facul.noh.entity.Noh;
import com.facul.noh.entity.NohPersistente;
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
        int votouIdAtualizado = request.getTermo() > noh.getTermo()
                ? -1
                : noh.getVotouId();

        boolean logCandidatoAtualizado =
                request.getUltimoTermoLog() > noh.getUltimoTermoLog() ||
                (request.getUltimoTermoLog() == noh.getUltimoTermoLog() &&
                request.getUltimoIndiceLog() >= noh.getUltimoIndiceLog());

        boolean concedeVoto = request.getTermo() == termoAtualizado
                && (votouIdAtualizado == -1 || votouIdAtualizado == request.getCandidateId())
                && logCandidatoAtualizado;

        if (concedeVoto) {
            votouIdAtualizado = request.getCandidateId();
        }

        try {
            if (termoAtualizado != noh.getTermo() || votouIdAtualizado != noh.getVotouId()) {
                noh.persistirAtualizarNoh(new NohPersistente(
                        noh.getId(),
                        termoAtualizado,
                        votouIdAtualizado,
                        null,
                        null
                ));
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
