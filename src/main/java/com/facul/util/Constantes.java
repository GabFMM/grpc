package com.facul.util;

public enum Constantes {
    // Cliente
    MAX_TENTATIVAS(10),
    INTERVALO_TENTATIVAS_MS(500),
    DEADLINE_SEGUNDOS(3),
    // Maior que TIMEOUT_COMMIT_MS: o cliente espera o líder confirmar o commit
    DEADLINE_GRAVACAO_SEGUNDOS(8),

    // Comunicação entre nós
    INTERVALO_FETCH_MS(500),          // FetchRequest = heartbeat (o enunciado cita 100 ms como exemplo)
    DEADLINE_NOH_MS(500),
    HEARTBEAT_TIMEOUT_MS(2500),       // líder marca a réplica como inativa (5 intervalos de fetch)
    TIMEOUT_COMMIT_MS(5000),          // quanto o líder espera o commit antes de desistir

    // Temporizador de eleição: base + aleatório (2 a 4 s)
    ELEICAO_TIMEOUT_BASE_MS(2000),
    ELEICAO_TIMEOUT_ALEATORIO_MS(2000),

    // Maioria absoluta de 4 nós
    QUORUM(3);

    public final int value;

    Constantes(int value) {
        this.value = value;
    }
}
