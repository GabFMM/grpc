package com.facul.noh;

import com.facul.noh.entity.Estado;
import com.facul.noh.entity.Noh;
import com.facul.noh.entity.NohPersistente;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;

import static com.facul.util.ServiceUtil.converterEntradasLogParaProto;
import static com.facul.util.ServiceUtil.responderOnError;

// Comunicações entre nós
public class NohService extends NohServiceGrpc.NohServiceImplBase {

    private final Noh noh;
    // Reinicia o temporizador de eleição quando um voto é concedido
    private final Runnable aoConcederVoto;

    public NohService(Noh noh, Runnable aoConcederVoto) {
        this.noh = noh;
        this.aoConcederVoto = aoConcederVoto;
    }

    @Override
    public void requestVote(RequestVoteRequest request, StreamObserver<RequestVoteResponse> responseObserver) {
        // Mesmo lock do Noh: leitura + decisão + persistência de forma atômica
        synchronized (noh) {
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

                if (concedeVoto) {
                    aoConcederVoto.run();
                }

                System.out.printf("[VOTO] Nó %d %s voto ao candidato %d (termo %d)%n",
                        noh.getId(), concedeVoto ? "concedeu" : "negou",
                        request.getCandidateId(), request.getTermo());

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
    }

    @Override
    public void fetch(FetchRequest request, StreamObserver<FetchResponse> responseObserver) {
        try {
            FetchResponse response;
            synchronized (noh) {
                response = processarFetch(request);
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (SQLException e) {
            responderOnError(
                    responseObserver,
                    "[ERRO] Falha ao processar fetch",
                    e
            );
        }
    }

    private FetchResponse processarFetch(FetchRequest request) throws SQLException {
        // Caso 1: quem pediu tem termo maior -> este nó (inclusive se for líder) vira seguidor
        if (request.getTermo() > noh.getTermo()) {
            noh.virarSeguidor(request.getTermo());

            // Sem líder conhecido: acontecerá uma nova eleição
            return redirecionar(request.getTermo(), -1);
        }

        // Caso 2: o nó receptor não é líder
        if (noh.getEstado() != Estado.LIDER) {
            return redirecionar(Math.max(noh.getTermo(), request.getTermo()), noh.getLiderId());
        }

        // Caso 3: o nó receptor é líder
        return responderComoLider(request);
    }

    private FetchResponse redirecionar(int termo, int liderId) {
        FetchRedirect.Builder redirect = FetchRedirect.newBuilder().setTermo(termo);
        if (liderId != -1) {
            redirect.setLiderId(liderId);
        }

        return FetchResponse.newBuilder()
                .setRedirecionamento(redirect.build())
                .build();
    }

    private FetchResponse responderComoLider(FetchRequest request) throws SQLException {
        int ultimoIndiceSeguidor = request.getUltimoIndiceLog();
        int ultimoIndiceLider = noh.getUltimoIndiceLog();

        // O prefixo é compatível se o seguidor está vazio ou se o termo da
        // entrada do líder no último índice do seguidor coincide.
        boolean prefixoValido;
        if (ultimoIndiceSeguidor == 0) {
            prefixoValido = true;
        } else if (ultimoIndiceSeguidor <= ultimoIndiceLider) {
            prefixoValido = noh.getEntradaLogPorIndice(ultimoIndiceSeguidor)
                    .map(entrada -> entrada.termo() == request.getUltimoTermoLog())
                    .orElse(false);
        } else {
            // Seguidor além do fim do log do líder
            prefixoValido = false;
        }

        // O FetchRequest é o heartbeat da réplica e também a confirmação de recebimento:
        // só conta para o quórum o índice cujo prefixo foi comprovado igual ao do líder.
        // Isso pode avançar o índice de commit ANTES de montar a resposta.
        noh.registrarFetchSeguidor(request.getSeguidorId(), prefixoValido ? ultimoIndiceSeguidor : 0);

        FetchData.Builder fetchData = FetchData.newBuilder()
                .setTermo(noh.getTermo())
                .setIndiceCommit(noh.getIndiceCommit())
                .setPrefixoValido(prefixoValido);

        if (prefixoValido) {
            fetchData.addAllEntradas(converterEntradasLogParaProto(
                    noh.getEntradasLogAPartirDe(ultimoIndiceSeguidor + 1)
            ));
        } else {
            // Só manda truncar. Não envia entradas: após o truncamento o seguidor pede
            // de novo com o novo último índice e o líder revalida o prefixo.
            // (Anexar logo após truncar um único índice poderia mascarar conflitos
            // em índices anteriores.)
            int indiceTruncamento = Math.min(ultimoIndiceSeguidor, ultimoIndiceLider + 1);
            fetchData.setTruncarIndice(indiceTruncamento);
        }

        return FetchResponse.newBuilder()
                .setDados(fetchData.build())
                .build();
    }
}
