package com.facul.noh.entity;

/** Estado volátil do nó, mantido apenas durante a execução. */
public class NohMemoria {
    private Estado estado;
    private int liderId;
    private int votosRecebidos;
    private int indiceCommit;

    public NohMemoria() {
        estado = Estado.SEGUIDOR;
        liderId = -1;
        votosRecebidos = 0;
        indiceCommit = 0;
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
}
