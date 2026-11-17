package com.facul.noh;

import com.facul.noh.entity.Noh;
import com.facul.noh.entity.NohPersistente;
import com.facul.noh.entity.Estado;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;

import static com.facul.util.ServiceUtil.responderOnError;
import static com.facul.util.ServiceUtil.converterEntradasLogParaProto;

// Comunicações entre nós
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
            responderOnError(
                    responseObserver,
                    "[ERRO] Falha ao persistir nó durante requestVote",
                    e
            );
        }
    }

    @Override
    public void fetch(FetchRequest request, StreamObserver<FetchResponse> responseObserver) {
        // Caso 1: o termo do líder está desatualizado
        if (request.getTermo() > noh.getTermo()) {
            try {
                noh.virarSeguidor(request.getTermo());

                // Cria um redirecionamento sem líder conhecido,
                // Pois o lider virou seguidor, então acontecerá uma nova eleição
                FetchRedirect.Builder redirectBuilder = FetchRedirect.newBuilder();
                redirectBuilder.setTermo(request.getTermo());

                FetchResponse response = FetchResponse.newBuilder()
                        .setRedirecionamento(redirectBuilder.build())
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (SQLException e) {
                responderOnError(
                        responseObserver,
                        "[ERRO] Falha ao atualizar estado do nó para seguidor durante fetch",
                        e
                );
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
            FetchData.Builder fetchData = FetchData.newBuilder()
                    .setTermo(noh.getTermo())
                    .setIndiceCommit(noh.getIndiceCommit());

            int ultimoIndiceSeguidor = request.getUltimoIndiceLog();
            int ultimoIndiceLider = noh.getUltimoIndiceLog();

            // O prefixo é compatível se o seguidor está vazio ou se o termo
            // da entrada do líder no último índice do seguidor coincide.
            boolean prefixoValido;
            if (ultimoIndiceSeguidor == 0) {
                // Válido, pois o log do seguido está vazio
                // Então não ha o que comparar
                prefixoValido = true;
            }
            else if (ultimoIndiceSeguidor <= ultimoIndiceLider) {
                try {
                    prefixoValido = noh.getEntradaLogPorIndice(ultimoIndiceSeguidor)
                            .map(entrada -> entrada.termo() == request.getUltimoTermoLog())
                            .orElse(false);
                } catch (SQLException e) {
                    responderOnError(
                            responseObserver,
                            "[ERRO] Não foi possível validar o prefixo do log do seguidor.",
                            e
                    );
                    return;
                }
            } else {
                // O seguidor está além do fim do log do líder
                // Um caso improvável, pois o seguidor deveria ter sido o lider na eleição
                prefixoValido = false;
            }

            if (prefixoValido) {
                fetchData.setPrefixoValido(true);
                try {
                    fetchData.addAllEntradas(converterEntradasLogParaProto(
                            noh.getEntradasLogAPartirDe(ultimoIndiceSeguidor + 1)
                    ));
                } catch (SQLException e) {
                    responderOnError(
                            responseObserver,
                            "[ERRO] Não foi possível recuperar as entradas faltantes do log.",
                            e
                    );
                    return;
                }
            } else {
                // Em um índice conflitante, substitui a entrada e o sufixo.
                // Se o seguidor estiver além do log do líder, remove apenas
                // as entradas posteriores ao fim do log do líder.
                int indiceTruncamento = Math.min(ultimoIndiceSeguidor, ultimoIndiceLider + 1);
                fetchData
                        .setPrefixoValido(false)
                        .setTruncarIndice(indiceTruncamento);

                try {
                    fetchData.addAllEntradas(converterEntradasLogParaProto(
                            noh.getEntradasLogAPartirDe(indiceTruncamento)
                    ));
                } catch (SQLException e) {
                    responderOnError(
                            responseObserver,
                            "[ERRO] Não foi possível recuperar as entradas do log a partir de um índice.",
                            e
                    );
                    return;
                }
            }

            // Envia a resposta
            FetchResponse response = FetchResponse.newBuilder()
                    .setDados(fetchData.build())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
