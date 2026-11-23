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
import static com.facul.util.Constantes.HEARTBEAT_TIMEOUT_MS;
import static com.facul.util.Constantes.QUORUM;

/**
 * Todos os métodos públicos são synchronized no próprio Noh, então handlers gRPC
 * e threads do escalonador podem compor operações com synchronized (noh).
 * Nunca faça chamadas de rede segurando esse lock.
 */
public class Noh {
    private final int id;
    private final NohMemoria memoria;
    private final NohPersistente persistente;

    private final Map<Integer, ManagedChannel> canais;

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

        // Garante que o banco final reflete o índice de commit persistido
        // (cobre um crash entre aplicar os dados e gravar o índice)
        try {
            logRepository.aplicarCommit(persistente.getIndiceCommit());
        } catch (SQLException e) {
            throw new IllegalStateException("[ERRO] Não foi possível reconciliar o banco final", e);
        }
    }

    // ------------------------------------------------------------------
    // Getters / setters simples
    // ------------------------------------------------------------------

    public int getId() {
        return id;
    }

    public synchronized int getVotouId() {
        return persistente.getVotouId();
    }

    public synchronized void setLiderId(int liderId) {
        memoria.setLiderId(liderId);
    }

    public synchronized int getLiderId() {
        return memoria.getLiderId();
    }

    public synchronized Estado getEstado() {
        return memoria.getEstado();
    }

    public synchronized int getTermo() {
        return persistente.getTermo();
    }

    public synchronized int getUltimoTermoLog() {
        return persistente.getUltimoTermoLog();
    }

    public synchronized int getUltimoIndiceLog() {
        return persistente.getUltimoIndiceLog();
    }

    public synchronized int getIndiceCommit() {
        return memoria.getIndiceCommit();
    }

    public Map<Integer, ManagedChannel> getCanais() {
        return canais;
    }

    public synchronized ManagedChannel getCanalLider() {
        if (getLiderId() == -1)
            return null;

        return canais.get(getLiderId());
    }

    public synchronized String descricaoStatus() {
        String lider = memoria.getLiderId() == -1 ? "?" : String.valueOf(memoria.getLiderId());
        String texto = String.format(
                "[Nó %d] papel=%s | termo=%d | commit=%d | último índice do log=%d | líder=%s",
                id, memoria.getEstado(), persistente.getTermo(), memoria.getIndiceCommit(),
                persistente.getUltimoIndiceLog(), lider
        );

        if (memoria.getEstado() == Estado.LIDER) {
            texto += String.format(" | réplicas ativas=%d/%d%s",
                    getReplicasAtivas(), canais.size(), temQuorumAtivo() ? "" : " (SEM QUÓRUM)");
        }

        return texto;
    }

    // ------------------------------------------------------------------
    // Commit
    // ------------------------------------------------------------------

    /**
     * Avança o índice de commit: 1º aplica as entradas no banco final,
     * 2º persiste o índice (tabela noh) e atualiza a memória.
     */
    public synchronized void setIndiceCommit(int indiceCommit) throws SQLException {
        if (indiceCommit <= memoria.getIndiceCommit())
            return;

        if (indiceCommit > persistente.getUltimoIndiceLog()) {
            throw new IllegalArgumentException(
                    "O índice de commit não pode ultrapassar o último índice do log local"
            );
        }

        logRepository.aplicarCommit(indiceCommit);

        persistirAtualizarNoh(new NohPersistente(
                id,
                null,
                null,
                null,
                null,
                indiceCommit
        ));

        System.out.printf("[COMMIT] Nó %d: índice de commit avançou para %d%n", id, indiceCommit);
        notifyAll(); // acorda gravações aguardando o commit
    }

    // ------------------------------------------------------------------
    // Líder: heartbeat das réplicas, quórum e commit
    // ------------------------------------------------------------------

    /**
     * Chamado pelo líder a cada FetchRequest recebido: registra o heartbeat e o índice
     * confirmado da réplica (0 se o prefixo do log dela é inválido) e tenta avançar o commit.
     */
    public synchronized void registrarFetchSeguidor(int seguidorId, int indiceConfirmado) throws SQLException {
        memoria.registrarFetchNoh(seguidorId);
        memoria.adicionarIndiceCommitNoh(seguidorId, indiceConfirmado);

        if (memoria.getEstado() != Estado.LIDER)
            return;

        int candidato = memoria.obterMaiorIndiceCommitNoh(persistente.getUltimoIndiceLog(), QUORUM.value);

        if (candidato > memoria.getIndiceCommit()) {
            // Regra do Raft: só comita diretamente entradas do termo atual
            Optional<EntradaLog> entrada = logRepository.getEntradaLogPorIndice(candidato);
            if (entrada.isPresent() && entrada.get().termo() == persistente.getTermo()) {
                setIndiceCommit(candidato);
            }
        }
    }

    public synchronized int getReplicasAtivas() {
        return memoria.contarNosAtivos(canais.keySet(), HEARTBEAT_TIMEOUT_MS.value);
    }

    /** O líder (ele mesmo + réplicas ativas) alcança o quórum? */
    public synchronized boolean temQuorumAtivo() {
        return 1 + getReplicasAtivas() >= QUORUM.value;
    }

    /** Bloqueia até o commit alcançar o índice, ou até perder a liderança / estourar o tempo. */
    public synchronized boolean aguardarCommit(int indice, int termo, long timeoutMs) throws InterruptedException {
        long limite = System.currentTimeMillis() + timeoutMs;

        while (memoria.getIndiceCommit() < indice) {
            if (memoria.getEstado() != Estado.LIDER || persistente.getTermo() != termo) {
                return false;
            }

            long restante = limite - System.currentTimeMillis();
            if (restante <= 0) {
                return false;
            }

            wait(restante);
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Transições de papel
    // ------------------------------------------------------------------

    /** Só promove se ainda é candidato no mesmo termo da eleição. */
    public synchronized boolean tentarVirarLider(int termoEleicao) {
        if (memoria.getEstado() != Estado.CANDIDATO || persistente.getTermo() != termoEleicao)
            return false;

        memoria.limparIndicesReplicados();
        memoria.setEstado(Estado.LIDER);
        memoria.setLiderId(id);
        memoria.setVotosRecebidos(0);

        // Após a eleição, quem tem o log mais atualizado é o lider
        for (Integer outroId : canais.keySet())
            memoria.adicionarIndiceCommitNoh(outroId, 0);

        notifyAll();
        return true;
    }

    /** Retorna o novo termo. */
    public synchronized int virarCandidato() throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            persistente.getTermo() + 1,
            id,
            null,
            null
        ));

        memoria.setEstado(Estado.CANDIDATO);
        memoria.setVotosRecebidos(1); // Candidato vota em si próprio

        return persistente.getTermo();
    }

    public synchronized void virarSeguidor(int novoTermo) throws SQLException {
        persistirAtualizarNoh(new NohPersistente(
            id,
            novoTermo,
            novoTermo > persistente.getTermo() ? -1 : null,
            null,
            null
        ));

        memoria.setEstado(Estado.SEGUIDOR);
        memoria.setVotosRecebidos(0);
        notifyAll();
    }

    public synchronized void esquecerLider(int liderPerdido) {
        if (memoria.getEstado() != Estado.LIDER && memoria.getLiderId() == liderPerdido)
            memoria.setLiderId(-1);
    }

    /** Resposta de redirecionamento a um FetchRequest (termo e líder conhecido, ou -1). */
    public synchronized void processarRedirecionamento(int termo, int liderInformado) throws SQLException {
        if (termo > persistente.getTermo())
            virarSeguidor(termo);

        if (liderInformado != -1
                && liderInformado != id
                && memoria.getEstado() == Estado.SEGUIDOR
                && canais.containsKey(liderInformado)) {
            memoria.setLiderId(liderInformado);
        }
    }

    // ------------------------------------------------------------------
    // Log
    // ------------------------------------------------------------------

    // Apenas seguidores chamam esse metodo
    // Líderes chamam realizarGravacao
    public synchronized void adicionarEntradaLog(EntradaLog entrada) throws SQLException {
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

        System.out.printf("[REPLICAÇÃO] Nó %d recebeu entrada %d (termo %d) '%s' - NÃO comitada (commit=%d)%n",
                id, entrada.indice(), entrada.termo(), entrada.comando(), memoria.getIndiceCommit());
    }

    /** Retorna o índice da nova entrada (ainda NÃO comitada) ou -1 se não for líder. */
    public synchronized int realizarGravacao(String gravacao) throws SQLException {
        // Somente líderes tem contato com o cliente,
        // Logo somente eles podem começar uma gravação
        if (memoria.getEstado() != Estado.LIDER)
            return -1;

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

        System.out.printf("[LÍDER] Entrada %d (termo %d) gravada no log local; aguardando quórum.%n",
                novoIndice, persistente.getTermo());

        return novoIndice;
    }

    public synchronized void truncarEntradas(int indice) throws SQLException {
        if (indice > persistente.getUltimoIndiceLog())
            return; // nada a remover

        if (indice <= memoria.getIndiceCommit()) {
            System.err.printf(
                    "[ERRO] Nó %d: truncar a partir de %d violaria entradas comitadas (commit=%d); ignorado%n",
                    id, indice, memoria.getIndiceCommit());
            return;
        }

        int antes = persistente.getUltimoIndiceLog();
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

        System.out.printf("[TRUNCAMENTO] Nó %d removeu as entradas %d..%d do log (divergência com o líder)%n",
                id, indice, antes);
    }

    /**
     * Aplica a resposta de dados de um líder. Retorna true se foi um contato válido com o líder
     * (o temporizador de eleição deve ser reiniciado).
     * - prefixo inválido: apenas trunca; o próximo fetch revalida com o novo último índice
     * - prefixo válido: anexa as entradas e avança o commit para min(commitLider, último índice)
     */
    public synchronized boolean aplicarFetchLider(
            int liderId,
            int termoLider,
            int commitLider,
            boolean prefixoValido,
            Integer truncarIndice,
            List<EntradaLog> entradas
    ) throws SQLException {
        if (memoria.getEstado() == Estado.LIDER || termoLider < persistente.getTermo())
            return false;

        if (termoLider > persistente.getTermo() || memoria.getEstado() == Estado.CANDIDATO)
            virarSeguidor(termoLider);

        memoria.setLiderId(liderId);

        if (!prefixoValido) {
            if (truncarIndice != null)
                truncarEntradas(truncarIndice);
            return true;
        }

        for (EntradaLog entrada : entradas) {
            if (entrada.indice() != persistente.getUltimoIndiceLog() + 1)
                break; // defesa: nunca deixar buracos no log

            adicionarEntradaLog(entrada);
        }

        int novoCommit = Math.min(commitLider, persistente.getUltimoIndiceLog());
        if (novoCommit > memoria.getIndiceCommit())
            setIndiceCommit(novoCommit);

        return true;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Leitura eventual consistente: somente dados comitados (banco final). */
    public synchronized List<EntradaLog> getDadosComitados() throws SQLException {
        return logRepository.getDadosComitados();
    }

    public synchronized List<EntradaLog> getEntradasLog() throws SQLException {
        return logRepository.getEntradasLog();
    }

    public synchronized List<EntradaLog> getEntradasLogAPartirDe(int indiceInicial) throws SQLException {
        return logRepository.getEntradasLogAPartirDe(indiceInicial);
    }

    public synchronized Optional<EntradaLog> getEntradaLogPorIndice(int indice) throws SQLException {
        return logRepository.getEntradaLogPorIndice(indice);
    }

    /*
    * Metodo responsável por persistir e atualizar em memória os novos dados
    * Somente atualiza o nó em memória se a persistência ocorrer sem erros
    * Mudanças nos dados que não são persistidos (ex: estado), não devem chamar esse metodo
    * */
    public synchronized void persistirAtualizarNoh(NohPersistente nohPersistente) throws SQLException {
        // 1º persistência
        nohPersistenteRepository.persistirNoh(nohPersistente);

        // 2º memória
        if (nohPersistente.getTermo() != null) {
            if (nohPersistente.getTermo() > persistente.getTermo()) {
                memoria.setEstado(Estado.SEGUIDOR);
                memoria.setLiderId(-1);
                memoria.setVotosRecebidos(0);
                notifyAll(); // acorda gravações de um líder que perdeu a liderança
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
