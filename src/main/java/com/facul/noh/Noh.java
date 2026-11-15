package com.facul.noh;

import java.sql.SQLException;
import java.util.Random;

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
    private int ultimoTermoLog;
    private int ultimoIndiceLog;

    // Duração em milissegundos
    private int duracaoTemporizador;

    private final LogRepository logRepository;

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
        ultimoIndiceLog = 0;
        calculaDuracaoTemporizador();

        Database database = new Database(id);
        database.inicializar();
        logRepository = new LogRepository(database);
    }

    public int getId() {
        return id;
    }

    public int getLiderId() {
        return liderId;
    }

    public Estado getEstado() {
        return estado;
    }

    public int getTermo() {
        return termo;
    }

    public int getUltimoTermoLog() {
        return ultimoTermoLog;
    }

    public int getUltimoIndiceLog() {
        return ultimoIndiceLog;
    }

    public int getDuracaoTemporizador() {
        return duracaoTemporizador;
    }

    public void virarLider() {
        estado = Estado.LIDER;
        liderId = id;
    }

    public void virarCandidato() {
        estado = Estado.CANDIDATO;
        termo++;
    }

    public void virarSeguidor(int novoTermo) {
        estado = Estado.SEGUIDOR;
        termo = novoTermo;
    }

    public void realizarGravacao(String gravacao) throws SQLException {
        int novoIndice = ultimoIndiceLog + 1;
        logRepository.inserir(new EntradaLog(novoIndice, termo, gravacao));
        ultimoIndiceLog = novoIndice;
        ultimoTermoLog = termo;
    }
}
