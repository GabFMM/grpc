package com.facul.noh.entity;

public record NohPersistente(
        int id,
        int termo,
        int votouId,
        int ultimoIndiceLog,
        int ultimoTermoLog
) {
}
