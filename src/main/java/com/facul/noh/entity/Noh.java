package com.facul.noh.entity;

import com.facul.noh.repository.Database;
import com.facul.noh.repository.LogRepository;
import com.facul.noh.repository.NohPersistenteRepository;
import io.grpc.ManagedChannel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facul.util.ClienteNohUtil.criarCanais;
import static com.facul.util.Constantes.HEARTBEAT_TIMEOUT_MS;
import static com.facul.util.Constantes.QUORUM;

/**
 * Todos os métodos públicos são synchronized no próprio Noh. Handlers gRPC e
 * threads do escalonador podem, portanto, usar synchronized (noh) para
 * compor operações compostas de forma atômica.
 * Chamadas de rede NUNCA devem ser feitas segurando esse lock.
 */
public class Noh {
    private final int id;
    private final NohMemoria memoria;
    private final NohPersistente persistente;

    private final Map<Integer, ManagedChannel> canais;

    private final LogRepository logRepository;
    private final NohPersistenteRepository nohPersistenteRepository;

    // Estado volátil do líder (reiniciado a cada eleição)
    // Instante do último FetchRequest recebido de cada réplica
    private final Map<Integer, Long> ultimoFetchMs = new HashMap<>();
    // Maior índice do log confirmadamente igual ao do líder em cada réplica
    private final Map<Integer, Integer> indiceReplicado = new HashMap<>();

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

        // O índice de commit é recuperado do banco final (dados comitados)
        try {
            memoria.setIndiceCommit(logRepository.getUltimoIndiceComitado());
        } catch (SQLException e) {
            throw new IllegalStateException("[ERRO] Não foi possível recuperar o índice de commit", e);
        }
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public int getId() {
        return id;
    }

    public synchronized int getVotouId() {
        return persistente.getVotouId();
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

    public synchronized String descricaoStatus() {
        String lider = memoria.getLiderId() == -1 ? "?" : String.valueOf(memoria.getLiderId());
        String base = String.format(
                "[Nó %d] papel=%s | termo=%d | commit=%d | último índice do log=%d | líder=%s",
                id, memoria.getEstado(), persistente.getTermo(), memoria.getIndiceCommit(),
                persistente.getUltimoIndiceLog(), lider
        );

        if (memoria.getEstado() == Estado.LIDER) {
            base += String.format(" | réplicas ativas=%d/%d%s",
                    getReplicasAtivas(), canais.size(), temQuorumAtivo() ? "" : " (SEM QUÓRUM)");
        }

        return base;
    }

    // ------------------------------------------------------------------
    // Transições de papel
    // ------------------------------------------------------------------

    /**
     * Só promove se ainda é candidato no mesmo termo da eleição
     * (evita virar líder após ter visto um termo maior).
     */
    public synchronized boolean tentarVirarLider(int termoEleicao) {
        if (memoria.getEstado() != Estado.CANDIDATO || persistente.getTermo() != termoEleicao) {
            return false;
        }

        memoria.setEstado(Estado.LIDER);
        memoria.setLiderId(id);
        memoria.setVotosRecebidos(0);

        ultimoFetchMs.clear();
        indiceReplicado.clear();

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
        memoria.setLiderId(-1);
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
        if (memoria.getEstado() != Estado.LIDER && memoria.getLiderId() == liderPerdido) {
            memoria.setLiderId(-1);
        }
    }

    /** Usado quando outro nó responde "o líder é X" a um FetchRequest. */
    public synchronized void processarRedirecionamento(int termo, int liderInformado) throws SQLException {
        if (termo > persistente.getTermo()) {
            virarSeguidor(termo);
        }

        if (liderInformado != -1
                && liderInformado != id
                && memoria.getEstado() == Estado.SEGUIDOR
                && canais.containsKey(liderInformado)) {
            memoria.setLiderId(liderInformado);
        }
    }

    // ------------------------------------------------------------------
    // Líder: gravação, quórum e commit
    // ------------------------------------------------------------------

    /** Retorna o índice da nova entrada (ainda NÃO comitada). */
    public synchronized int realizarGravacao(String gravacao) throws SQLException {
        int novoIndice = persistente.getUltimoIndiceLog() + 1;
        logRepository.inserir(new EntradaLog(novoIndice, persistente.getTermo(), gravacao));
        persistente.setUltimoIndiceLog(novoIndice);
        persistente.setUltimoTermoLog(persistente.getTermo());

        System.out.printf("[LÍDER] Entrada %d (termo %d) gravada no log local; aguardando quórum.%n",
                novoIndice, persistente.getTermo());

        // O índice de commit só avança quando o quórum confirmar via FetchRequest
        return novoIndice;
    }

    public synchronized int getReplicasAtivas() {
        long agora = System.currentTimeMillis();
        int ativas = 0;

        for (Integer replicaId : canais.keySet()) {
            Long ultimo = ultimoFetchMs.get(replicaId);
            if (ultimo != null && agora - ultimo <= HEARTBEAT_TIMEOUT_MS.value) {
                ativas++;
            }
        }

        return ativas;
    }

    /** O líder (ele mesmo + réplicas ativas) alcança o quórum? */
    public synchronized boolean temQuorumAtivo() {
        return 1 + getReplicasAtivas() >= QUORUM.value;
    }

    /**
     * Chamado pelo líder a cada FetchRequest recebido.
     * indiceConfirmado = índice que a réplica comprovadamente tem igual ao do líder
     * (0 se o prefixo do log dela é inválido).
     */
    public synchronized void registrarFetchSeguidor(int seguidorId, int indiceConfirmado) {
        ultimoFetchMs.put(seguidorId, System.currentTimeMillis());
        indiceReplicado.put(seguidorId, indiceConfirmado);
        recalcularCommit();
    }

    private void recalcularCommit() {
        if (memoria.getEstado() != Estado.LIDER) {
            return;
        }

        List<Integer> indices = new ArrayList<>();
        indices.add(persistente.getUltimoIndiceLog());
        for (Integer replicaId : canais.keySet()) {
            indices.add(indiceReplicado.getOrDefault(replicaId, 0));
        }

        if (indices.size() < QUORUM.value) {
            return;
        }

        // O maior índice presente em pelo menos QUORUM nós é o QUORUM-ésimo maior valor
        indices.sort(Comparator.reverseOrder());
        int candidato = indices.get(QUORUM.value - 1);

        if (candidato <= memoria.getIndiceCommit()) {
            return;
        }

        try {
            // Regra do Raft: só comita diretamente entradas do termo atual
            Optional<EntradaLog> entrada = logRepository.getEntradaLogPorIndice(candidato);
            if (entrada.isPresent() && entrada.get().termo() == persistente.getTermo()) {
                avancarCommit(candidato);
            }
        } catch (SQLException e) {
            System.err.printf("[ERRO] Nó %d não conseguiu avançar o commit: %s%n", id, e.getMessage());
        }
    }

    private void avancarCommit(int novoCommit) throws SQLException {
        logRepository.aplicarCommit(memoria.getIndiceCommit(), novoCommit);
        memoria.setIndiceCommit(novoCommit);
        notifyAll();

        System.out.printf("[COMMIT] Nó %d: índice de commit avançou para %d%n", id, novoCommit);
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
    // Réplica: aplica a resposta do líder
    // ------------------------------------------------------------------

    /**
     * Retorna true se a resposta foi aceita (contato válido com o líder).
     * - prefixoValido=false: trunca o log a partir de truncarIndice (sem anexar nada;
     *   o próximo fetch revalida o prefixo com o novo último índice)
     * - prefixoValido=true: anexa as entradas e avança o commit para
     *   min(commitLider, último índice local)
     */
    public synchronized boolean aplicarFetchLider(
            int liderId,
            int termoLider,
            int commitLider,
            boolean prefixoValido,
            Integer truncarIndice,
            List<EntradaLog> entradas
    ) throws SQLException {
        if (memoria.getEstado() == Estado.LIDER || termoLider < persistente.getTermo()) {
            return false;
        }

        if (termoLider > persistente.getTermo() || memoria.getEstado() == Estado.CANDIDATO) {
            virarSeguidor(termoLider);
        }
        memoria.setLiderId(liderId);

        if (!prefixoValido) {
            if (truncarIndice != null) {
                truncarLogAPartirDe(truncarIndice);
            }
            return true;
        }

        for (EntradaLog entrada : entradas) {
            if (entrada.indice() != persistente.getUltimoIndiceLog() + 1) {
                break; // defesa: nunca deixar buracos no log
            }

            logRepository.inserir(entrada);
            persistente.setUltimoIndiceLog(entrada.indice());
            persistente.setUltimoTermoLog(entrada.termo());

            System.out.printf("[REPLICAÇÃO] Nó %d recebeu entrada %d (termo %d) '%s' - NÃO comitada (commit=%d)%n",
                    id, entrada.indice(), entrada.termo(), entrada.comando(), memoria.getIndiceCommit());
        }

        int novoCommit = Math.min(commitLider, persistente.getUltimoIndiceLog());
        if (novoCommit > memoria.getIndiceCommit()) {
            avancarCommit(novoCommit);
        }

        return true;
    }

    private void truncarLogAPartirDe(int indice) throws SQLException {
        if (indice > persistente.getUltimoIndiceLog()) {
            return; // nada a remover
        }

        if (indice <= memoria.getIndiceCommit()) {
            System.err.printf("[ERRO] Nó %d: truncamento a partir de %d violaria entradas comitadas (commit=%d); ignorado%n",
                    id, indice, memoria.getIndiceCommit());
            return;
        }

        int antes = persistente.getUltimoIndiceLog();
        logRepository.truncarAPartirDe(indice);

        Optional<EntradaLog> ultima = logRepository.getUltimaEntrada();
        persistente.setUltimoIndiceLog(ultima.map(EntradaLog::indice).orElse(0));
        persistente.setUltimoTermoLog(ultima.map(EntradaLog::termo).orElse(0));

        System.out.printf("[TRUNCAMENTO] Nó %d removeu entradas %d..%d do log (divergência com o líder)%n",
                id, indice, antes);
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Leitura eventual consistente: somente dados comitados. */
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
                notifyAll(); // acorda gravações pendentes de um líder que perdeu a liderança
            }
            persistente.setTermo(nohPersistente.getTermo());
        }
        if (nohPersistente.getVotouId() != null) {
            persistente.setVotouId(nohPersistente.getVotouId());
        }
    }
}
