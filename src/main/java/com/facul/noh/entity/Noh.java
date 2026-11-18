package com.facul.noh.entity;

import com.facul.noh.repository.Database;
import com.facul.noh.repository.LogRepository;
import com.facul.noh.repository.NohPersistenteRepository;
import io.grpc.ManagedChannel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facul.util.ClienteNohUtil.criarCanais;

public class Noh {
    private static final int QUORUM = 3;

    private final int id;
    private final NohMemoria memoria;
    private final NohPersistente persistente;

    Map<Integer, ManagedChannel> canais;

    private final LogRepository logRepository;
    private final NohPersistenteRepository nohPersistenteRepository;

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
        memoria = new NohMemoria();
        canais = criarCanais(gerarNohNums(num));

        Database database = new Database(id);
        logRepository = new LogRepository(database);
        nohPersistenteRepository = new NohPersistenteRepository(database);
        database.inicializar();

        // Recupera dados persistidos se houverem
        persistente = nohPersistenteRepository.recuperarNohPersistente(id);
        memoria.setIndiceCommit(persistente.getIndiceCommit());
    }

    public int getId() {
        return id;
    }

    public int getVotouId() {
        return persistente.getVotouId();
    }

    public void setLiderId(int liderId) {
        memoria.setLiderId(liderId);
    }

    public int getLiderId() {
        return memoria.getLiderId();
    }

    public Estado getEstado() {
        return memoria.getEstado();
    }

    public int getTermo() {
        return persistente.getTermo();
    }

    public int getUltimoTermoLog() {
        return persistente.getUltimoTermoLog();
    }

    public int getUltimoIndiceLog() {
        return persistente.getUltimoIndiceLog();
    }

    public int getIndiceCommit() {
        return memoria.getIndiceCommit();
    }

    public void setIndiceCommit(int indiceCommit) throws SQLException {
        if (indiceCommit <= persistente.getIndiceCommit())
            return;

        if (indiceCommit > persistente.getUltimoIndiceLog()) {
            throw new IllegalArgumentException(
                    "O índice de commit não pode ultrapassar o último índice do log local"
            );
        }

        persistirAtualizarNoh(new NohPersistente(
                id,
                null,
                null,
                null,
                null,
                indiceCommit
        ));
    }

    public void atualizarIndiceCommitNoh(int nohId, int indiceCommit) {
        memoria.adicionarIndiceCommitNoh(nohId, indiceCommit);


    }

    public int obterMaiorIndiceCommitNoh() {
        return memoria.obterMaiorIndiceCommitNoh(
                persistente.getUltimoIndiceLog(),
                QUORUM
        );
    }

    public Map<Integer, ManagedChannel> getCanais() {
        return canais;
    }

    public ManagedChannel getCanalLider() {
        if (getLiderId() == -1)
            return null;

        return canais.get(getLiderId());
    }

    public void virarLider() {
        memoria.limparIndicesReplicados();
        memoria.setEstado(Estado.LIDER);
        memoria.setLiderId(id);
        memoria.setVotosRecebidos(0);

        // Após a eleição, quem tem o log mais atualizado é o lider
        for (Integer id : canais.keySet())
            memoria.adicionarIndiceCommitNoh(id, 0);
    }

    public void virarCandidato() throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            persistente.getTermo() + 1,
            id,
            null,
            null
        ));

        memoria.setEstado(Estado.CANDIDATO);
        memoria.setVotosRecebidos(1); // Candidato vota em si próprio
    }

    public void virarSeguidor(int novoTermo) throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            novoTermo,
            novoTermo > persistente.getTermo() ? -1 : null,
            null,
            null
        ));

        memoria.setEstado(Estado.SEGUIDOR);
        memoria.setVotosRecebidos(0);
    }

    // Apenas seguidores chamam esse metodo
    // Líderes chamam realizarGravacao
    public void adicionarEntradaLog(EntradaLog entrada) throws SQLException {
        if (getEstado() == Estado.LIDER)
            return;

        logRepository.inserir(entrada);

        persistirAtualizarNoh(new NohPersistente(
                id,
                null,
                null,
                entrada.indice(),
                entrada.termo()
        ));
    }

    public void realizarGravacao(String gravacao) throws SQLException {
        // Somente líderes tem contato com o cliente,
        // Logo somente eles podem começar uma gravação
        if (memoria.getEstado() != Estado.LIDER)
            return;

        // Atualiza o próprio nó
        int novoIndice = persistente.getUltimoIndiceLog() + 1;

        logRepository.inserir(new EntradaLog(novoIndice, persistente.getTermo(), gravacao));

        persistirAtualizarNoh(new NohPersistente(
                id,
                null,
                null,
                novoIndice,
                persistente.getTermo()
        ));
    }

    public void truncarEntradas(int indice) throws SQLException {
        logRepository.truncarEntradas(indice);

        Optional<EntradaLog> entrada = getEntradaLogPorIndice(indice - 1);
        if (entrada.isPresent())
            persistirAtualizarNoh(new NohPersistente(
                    id,
                    null,
                    null,
                    entrada.get().indice(),
                    entrada.get().termo()
                )
            );
        else
            persistirAtualizarNoh(new NohPersistente(
                    id,
                    null,
                    null,
                    0,
                    0
                )
            );
    }

    public List<EntradaLog> getEntradasLog() throws SQLException {
        return logRepository.getEntradasLog();
    }

    public List<EntradaLog> getEntradasLogAPartirDe(int indiceInicial) throws SQLException {
        return logRepository.getEntradasLogAPartirDe(indiceInicial);
    }

    public Optional<EntradaLog> getEntradaLogPorIndice(int indice) throws SQLException {
        return logRepository.getEntradaLogPorIndice(indice);
    }

    /*
    * Metodo responsável por persistir e atualizar em memória os novos dados
    * Somente atualiza o nó em memória se a persistência ocorrer sem erros
    * Mudanças nos dados que não são persistidos (ex: estado), não devem chamar esse metodo
    * */
    public void persistirAtualizarNoh(NohPersistente nohPersistente) throws SQLException {
        // 1º persistência
        nohPersistenteRepository.persistirNoh(nohPersistente);

        // 2º memória
        if (nohPersistente.getTermo() != null) {
            if (nohPersistente.getTermo() > persistente.getTermo()) {
                memoria.setEstado(Estado.SEGUIDOR);
                memoria.setLiderId(-1);
            }
            persistente.setTermo(nohPersistente.getTermo());
        }
        if (nohPersistente.getVotouId() != null) {
            persistente.setVotouId(nohPersistente.getVotouId());
        }
        if (nohPersistente.getUltimoIndiceLog() != null) {
            persistente.setUltimoIndiceLog(nohPersistente.getUltimoIndiceLog());
        }
        if (nohPersistente.getUltimoTermoLog() != null) {
            persistente.setUltimoTermoLog(nohPersistente.getUltimoTermoLog());
        }
        if (nohPersistente.getIndiceCommit() != null) {
            persistente.setIndiceCommit(nohPersistente.getIndiceCommit());
            memoria.setIndiceCommit(nohPersistente.getIndiceCommit());
        }
    }
}
