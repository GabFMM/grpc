package com.facul.noh.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regras de quórum, índice de commit e heartbeat do líder (enunciado: quórum = 3 de 4). */
class NohMemoriaTest {

    private static final int QUORUM = 3;

    @Test
    void semConfirmacaoDasReplicasNaoHaCommit() {
        NohMemoria m = new NohMemoria();
        // só o líder tem as 5 entradas: 1 nó < quórum
        assertEquals(0, m.obterMaiorIndiceCommitNoh(5, QUORUM));
    }

    @Test
    void umaReplicaNaoBastaParaOQuorum() {
        NohMemoria m = new NohMemoria();
        m.adicionarIndiceCommitNoh(2, 5);
        // líder + 1 réplica = 2 nós < 3
        assertEquals(0, m.obterMaiorIndiceCommitNoh(5, QUORUM));
    }

    @Test
    void commitEOMaiorIndiceReplicadoNoQuorum() {
        NohMemoria m = new NohMemoria();
        m.adicionarIndiceCommitNoh(1, 3);
        m.adicionarIndiceCommitNoh(2, 2);
        m.adicionarIndiceCommitNoh(3, 0);
        // índice 3: líder + nó 1 (2 nós); índice 2: líder + nós 1 e 2 (3 nós)
        assertEquals(2, m.obterMaiorIndiceCommitNoh(5, QUORUM));
    }

    @Test
    void liderMaisDuasReplicasEmDiaComitamTudo() {
        NohMemoria m = new NohMemoria();
        m.adicionarIndiceCommitNoh(1, 5);
        m.adicionarIndiceCommitNoh(2, 5);
        m.adicionarIndiceCommitNoh(3, 0);
        assertEquals(5, m.obterMaiorIndiceCommitNoh(5, QUORUM));
    }

    @Test
    void commitAtualNaoRetrocede() {
        NohMemoria m = new NohMemoria();
        m.setIndiceCommit(4);
        assertEquals(4, m.obterMaiorIndiceCommitNoh(5, QUORUM));
    }

    @Test
    void confirmacaoDeUmaReplicaNuncaDiminui() {
        NohMemoria m = new NohMemoria();
        m.adicionarIndiceCommitNoh(1, 5);
        m.adicionarIndiceCommitNoh(1, 3);
        assertEquals(5, m.getIndiceCommitPorNoh().get(1));
    }

    @Test
    void replicaSemFetchRecenteEInativa() {
        NohMemoria m = new NohMemoria();
        m.registrarFetchNoh(1);

        assertEquals(1, m.contarNosAtivos(List.of(1, 2, 3), 60_000)); // 2 e 3 nunca enviaram fetch
        assertEquals(0, m.contarNosAtivos(List.of(1, 2, 3), -1));     // heartbeat timeout estourado
    }

    @Test
    void novaLiderancaLimpaConfirmacoesEHeartbeats() {
        NohMemoria m = new NohMemoria();
        m.adicionarIndiceCommitNoh(1, 5);
        m.registrarFetchNoh(1);

        m.limparIndicesReplicados();

        assertEquals(0, m.getIndiceCommitPorNoh().size());
        assertEquals(0, m.contarNosAtivos(List.of(1), 60_000));
    }
}
