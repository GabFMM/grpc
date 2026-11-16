package com.facul.noh.entity;

public record NohPersistente(
        int id, // Não pode ser null
        Integer termo,
        Integer votouId,
        Integer ultimoIndiceLog,
        Integer ultimoTermoLog
) {
}
