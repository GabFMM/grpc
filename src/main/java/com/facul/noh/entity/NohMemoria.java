package com.facul.noh.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Estado volátil do nó, mantido apenas durante a execução. */
public class NohMemoria {
    private Estado estado;
    private int liderId;
    private int votosRecebidos;
    private int indiceCommit;

    // Chave: id de um noh
    // Valor: maior índice do log que o noh correspondente confirmou ter igual ao do líder
    private Map<Integer, Integer> indiceCommitPorNoh;

    // Chave: id de um noh
    // Valor: instante (ms) do último FetchRequest recebido dele (usado só pelo líder)
    private final Map<Integer, Long> ultimoFetchPorNoh;

    public NohMemoria() {
        estado = Estado.SEGUIDOR;
        liderId = -1;
        votosRecebidos = 0;
        indiceCommit = 0;
        indiceCommitPorNoh = new HashMap<>();
        ultimoFetchPorNoh = new HashMap<>();
    }

    public Estado getEstado() {
        return estado;
    }

    public void setEstado(Estado estado) {
        this.estado = estado;
    }

    public int getLiderId() {
        return liderId;
    }

    public void setLiderId(int liderId) {
        this.liderId = liderId;
    }

    public int getVotosRecebidos() {
        return votosRecebidos;
    }

    public void setVotosRecebidos(int votosRecebidos) {
        this.votosRecebidos = votosRecebidos;
    }

    public int getIndiceCommit() {
        return indiceCommit;
    }

    public void setIndiceCommit(int indiceCommit) {
        this.indiceCommit = indiceCommit;
    }

    public Map<Integer, Integer> getIndiceCommitPorNoh() {
        return indiceCommitPorNoh;
    }

    public void adicionarIndiceCommitNoh(int nohId, int indiceCommit) {
        indiceCommitPorNoh.merge(nohId, indiceCommit, Math::max);
    }

    /**
     * Retorna o maior índice acima do commit atual que foi armazenado
     * pelo líder e por pelo menos quorum - 1 réplicas.
     * O mapa contém apenas as confirmações das réplicas; o líder é contado aqui.
     */
    public int obterMaiorIndiceCommitNoh(int ultimoIndiceLocal, int quorum) {
        for (int indiceCandidato = ultimoIndiceLocal;
             indiceCandidato > indiceCommit;
             indiceCandidato--) {

            int nosComEntrada = 1; // O log local do líder contém esse índice.
            for (int indiceReplica : indiceCommitPorNoh.values()) {
                if (indiceReplica >= indiceCandidato) {
                    nosComEntrada++;
                }
            }

            if (nosComEntrada >= quorum) {
                return indiceCandidato;
            }
        }

        return indiceCommit;
    }

    public void registrarFetchNoh(int nohId) {
        ultimoFetchPorNoh.put(nohId, System.currentTimeMillis());
    }

    /** Quantas réplicas enviaram FetchRequest dentro do heartbeat timeout. */
    public int contarNosAtivos(Collection<Integer> ids, long timeoutMs) {
        long agora = System.currentTimeMillis();
        int ativos = 0;

        for (Integer nohId : ids) {
            Long ultimo = ultimoFetchPorNoh.get(nohId);
            if (ultimo != null && agora - ultimo <= timeoutMs) {
                ativos++;
            }
        }

        return ativos;
    }

    public void limparIndicesReplicados() {
        indiceCommitPorNoh.clear();
        ultimoFetchPorNoh.clear();
    }
}
