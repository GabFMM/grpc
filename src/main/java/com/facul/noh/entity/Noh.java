package com.facul.noh.entity;

import com.facul.noh.repository.Database;
import com.facul.noh.repository.LogRepository;
import com.facul.noh.repository.NohRepository;
import io.grpc.ManagedChannel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.facul.util.Util.criarCanais;

public class Noh {
    public enum Estado {
        CANDIDATO,
        SEGUIDOR,
        LIDER
    }

    private final int id;
    private Estado estado;

    // Relacionados a eleição
    private int liderId; // -1 indica ausência
    private int votouId; // -1 indica ausência
    private int termo;
    private int votosRecebidos;

    // Relacionados ao log
    private int ultimoIndiceLog;
    private int ultimoTermoLog;
    private int indiceCommit; // é menor ou igual ao ultimoIndiceLog

    Map<Integer, ManagedChannel> canais;

    // Duração em milissegundos
    private int duracaoTemporizador;

    private final LogRepository logRepository;
    private final NohRepository nohRepository;

    private void calculaDuracaoTemporizador() {
        // Todo nó deve conter um temporizador único
        Random random = new Random();

        int tempoBase = 2000; // 2 segundos
        int tempoAdicional = random.nextInt(2000); // até 2 segundos adicionais

        duracaoTemporizador = tempoBase + tempoAdicional;
    }

    private List<Integer> gerarNohNums(int nohNumExcluido) {
        List<Integer> nohNums = new ArrayList<>();

        for (int i = 1; i <= 4; i++) {
            if (i != nohNumExcluido) {
                nohNums.add(i);
            }
        }

        return nohNums;
    }

    public Noh(int num, int id) {
        this.id = id;

        // Parametrização padrão
        estado = Estado.SEGUIDOR;
        termo = 0;
        votouId = -1;
        votosRecebidos = 0;
        canais = criarCanais(gerarNohNums(num));
        calculaDuracaoTemporizador();

        Database database = new Database(id);
        logRepository = new LogRepository(database);
        nohRepository = new NohRepository(database);
        database.inicializar();

        // Recupera dados persistidos se houverem
        NohPersistente persistente = nohRepository.recuperarNohPersistente(id);
        votouId = persistente.votouId();
        termo = persistente.termo();
        ultimoIndiceLog = persistente.ultimoIndiceLog();
        ultimoTermoLog = persistente.ultimoTermoLog();
    }

    public int getId() {
        return id;
    }

    public int getVotouId() {
        return votouId;
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

    public Map<Integer, ManagedChannel> getCanais() {
        return canais;
    }

    public void virarLider() {
        estado = Estado.LIDER;
        liderId = id;
        votosRecebidos = 0;
    }

    public void virarCandidato() throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            termo + 1,
            id,
            null,
            null
        ));

        estado = Estado.CANDIDATO;
        termo++;
        liderId = -1;
        votosRecebidos = 1; // Candidato vota em si próprio
    }

    public void virarSeguidor(int novoTermo) throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            novoTermo,
            novoTermo > termo ? -1 : null,
            null,
            null
        ));

        estado = Estado.SEGUIDOR;
        votouId = novoTermo > termo ? -1 : votouId;
        termo = novoTermo;
        votosRecebidos = 0;
    }

    public void realizarGravacao(String gravacao) throws SQLException {
        // Atualiza o próprio nó
        int novoIndice = ultimoIndiceLog + 1;
        logRepository.inserir(new EntradaLog(novoIndice, termo, gravacao));
        ultimoIndiceLog = novoIndice;
        ultimoTermoLog = termo;

        // Agora é seguro para avançar o índice do commit
        // indiceCommit++;
    }

    public List<EntradaLog> getEntradasLog() throws SQLException {
        return logRepository.getEntradasLog();
    }

    /*
    * Metodo responsável por persistir e atualizar em memória os novos dados
    * Somente atualiza o nó em memória se a persistência ocorrer sem erros
    * Mudanças nos dados que não são persistidos (ex: estado), não devem chamar esse metodo
    * */
    public void persistirAtualizarNoh(NohPersistente nohPersistente) throws SQLException {
        // 1º persistência
        nohRepository.persistirNoh(nohPersistente);

        // 2º memória
        if (nohPersistente.termo() != null) {
            if (nohPersistente.termo() > termo) {
                estado = Estado.SEGUIDOR;
                liderId = -1;
            }
            termo = nohPersistente.termo();
        }
        if (nohPersistente.votouId() != null) {
            votouId = nohPersistente.votouId();
        }

    }
}
