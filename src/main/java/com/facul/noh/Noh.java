package com.facul.noh;

import java.util.Random;
import java.util.UUID;

public class Noh {
    public enum Estado {
        CANDIDATO,
        SEGUIDOR,
        LIDER
    }

    private final int id;
    private int liderId;
    private int votouId;
    private Estado estado;
    private int termo;
    private int termoLog;
    private int indiceLog;

    // Duração em milissegundos
    private int duracaoTemporizador;

    private void calculaDuracaoTemporizador() {
        // Todo nó deve conter um temporizador único
        Random random = new Random();

        int tempoBase = 2000; // 2 segundos
        int tempoAdicional = random.nextInt(2000); // até 2 segundos adicionais

        duracaoTemporizador = tempoBase + tempoAdicional;
    }

    public Noh(int id) {
        this.id = id;
        liderId = -1;
        votouId = -1;
        estado = Estado.SEGUIDOR;
        termo = 0;
        indiceLog = 0;
        calculaDuracaoTemporizador();
    }

    public int getId() {
        return id;
    }

    public int getLiderId() {
        return liderId;
    }

    public int getTermo() {
        return termo;
    }

    public int getTermoLog() {
        return termoLog;
    }

    public int getIndiceLog() {
        return indiceLog;
    }

    public int getDuracaoTemporizador() {
        return duracaoTemporizador;
    }

    public void virarLider() {
        estado = Estado.LIDER;
    }

    public void virarCandidato() {
        estado = Estado.CANDIDATO;
        termo++;
    }

    public void virarSeguidor(int novoTermo) {
        estado = Estado.SEGUIDOR;
        termo = novoTermo;
    }
}
