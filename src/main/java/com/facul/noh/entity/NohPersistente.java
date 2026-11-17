package com.facul.noh.entity;

public class NohPersistente {
    private final int id;
    private Integer termo;
    private Integer votouId;
    private Integer ultimoIndiceLog;
    private Integer ultimoTermoLog;

    public NohPersistente(
            int id,
            Integer termo,
            Integer votouId,
            Integer ultimoIndiceLog,
            Integer ultimoTermoLog
    ) {
        this.id = id;
        this.termo = termo;
        this.votouId = votouId;
        this.ultimoIndiceLog = ultimoIndiceLog;
        this.ultimoTermoLog = ultimoTermoLog;
    }

    public int id() {
        return id;
    }

    public Integer getTermo() {
        return termo;
    }

    public void setTermo(Integer termo) {
        this.termo = termo;
    }

    public Integer getVotouId() {
        return votouId;
    }

    public void setVotouId(Integer votouId) {
        this.votouId = votouId;
    }

    public Integer getUltimoIndiceLog() {
        return ultimoIndiceLog;
    }

    public void setUltimoIndiceLog(Integer ultimoIndiceLog) {
        this.ultimoIndiceLog = ultimoIndiceLog;
    }

    public Integer getUltimoTermoLog() {
        return ultimoTermoLog;
    }

    public void setUltimoTermoLog(Integer ultimoTermoLog) {
        this.ultimoTermoLog = ultimoTermoLog;
    }
}
