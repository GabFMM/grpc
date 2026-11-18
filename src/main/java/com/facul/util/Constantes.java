package com.facul.util;

public enum Constantes {
    // Cliente
    MAX_TENTATIVAS(10),
    INTERVALO_TENTATIVAS_MS(500),
    DEADLINE_SEGUNDOS(3),
    // Maior que TIMEOUT_COMMIT_MS, para o cliente esperar a resposta do líder
    DEADLINE_GRAVACAO_SEGUNDOS(8),

    // Comunicação entre nós
    DEADLINE_NOH_MS(500),
    INTERVALO_FETCH_MS(100),          // FetchRequest = heartbeat
    HEARTBEAT_TIMEOUT_MS(1500),       // líder marca réplica como inativa
    TIMEOUT_COMMIT_MS(5000),          // quanto o líder espera o commit antes de desistir

    // Temporizador de eleição: base + aleatório (1,5 a 3 s)
    ELEICAO_TIMEOUT_BASE_MS(1500),
    ELEICAO_TIMEOUT_ALEATORIO_MS(1500),

    // Maioria absoluta de 4 nós
    QUORUM(3);

    public final int value;

    Constantes(int value) {
        this.value = value;
    }
}
