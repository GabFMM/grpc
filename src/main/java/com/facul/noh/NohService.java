package com.facul.noh;

import com.facul.noh.entity.Noh;
import com.facul.noh.entity.NohPersistente;
import com.facul.noh.entity.Estado;
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

    @Override
    public void fetch(FetchRequest request, StreamObserver<FetchResponse> responseObserver) {
        // Caso 1: o termo do líder está desatualizado
        if (request.getTermo() > noh.getTermo()) {
            try {
                noh.virarSeguidor(request.getTermo());
                // faz coisas?

            } catch (SQLException e) {
                System.err.println(
                        "[ERRO] Não foi possível atualizar o estado do nó para seguidor durante fetch: " +
                                e.getMessage()
                );

                responseObserver.onError(Status.INTERNAL
                        .withDescription("Falha ao atualizar estado do nó")
                        .withCause(e)
                        .asRuntimeException());
            }
        }
        // Caso 2: o nó receptor não é líder
        else if (noh.getEstado() != Estado.LIDER) {
            int maiorTermo = Math.max(noh.getTermo(), request.getTermo());
            int liderId = noh.getLiderId();

            FetchRedirect.Builder redirectBuilder = FetchRedirect.newBuilder();
            if (liderId != -1)
                redirectBuilder.setLiderId(liderId);
            redirectBuilder.setTermo(maiorTermo);

            FetchResponse response = FetchResponse.newBuilder()
                    .setRedirecionamento(redirectBuilder.build())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        // Caso 3: o no receptor é líder
        else {
            // faz coisas
        }
    }
}
