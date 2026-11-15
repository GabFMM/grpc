package com.facul.cliente;

public enum Constantes {
    MAX_TENTATIVAS(10),
    INTERVALO_TENTATIVAS_MS(500),
    DEADLINE_SEGUNDOS(3);

    public final int value;

    Constantes(int value) {
        this.value = value;
    }
}
