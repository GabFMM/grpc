package com.facul.noh.repository;

import com.facul.noh.entity.NohPersistente;

import java.sql.*;

public class NohRepository {
    private final Database database;

    public NohRepository(Database database) {
        this.database = database;
    }

    public NohPersistente recuperarNohPersistente(int nohId) {
        String sqlNoh = """
            SELECT termo, votou_id
            FROM noh
            WHERE id = ?
        """;

        String sqlLog = """
            SELECT indice, termo
            FROM log
            ORDER by indice DESC
            LIMIT 1
        """;

        try (Connection conn = database.conectar();
             Statement stmt = conn.createStatement();
             PreparedStatement prepStmt = conn.prepareStatement(sqlNoh)) {

            // Tabela noh
            int termo = 0;
            int votouId = -1;

            prepStmt.setInt(1, nohId);
            try (ResultSet rs = prepStmt.executeQuery()) {
                if (rs.next()) {
                    termo = rs.getInt("termo");
                    votouId = rs.getInt("votou_id");
                }
            }

            // Tabela log
            int ultimoIndiceLog = 0;
            int ultimoTermoLog = 0;
            try (ResultSet rs = stmt.executeQuery(sqlLog)) {
                if (rs.next()) {
                    ultimoIndiceLog = rs.getInt("indice");
                    ultimoTermoLog = rs.getInt("termo");
                }
            }

            return new NohPersistente(
                    nohId,
                    termo,
                    votouId,
                    ultimoIndiceLog,
                    ultimoTermoLog
            );
        } catch (SQLException e) {
            System.err.println("[ERRO] Persistência do nó não encontrado no banco: " + e.getMessage());
        }

        return null;
    }

    /*
    * Atualiza a tabela noh
    * Todos os campos null, com exceção do id, do nohPersistente
    * não devem mudar o valor correspondente no banco
    * */
    public void persistirNoh(NohPersistente nohPersistente) throws SQLException {
        String sql = """
            UPDATE noh
            SET votou_id = COALESCE(?, votou_id),
                termo = COALESCE(?, termo)
            WHERE id = ?
        """;

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, nohPersistente.votouId());
            stmt.setObject(2, nohPersistente.termo());
            stmt.setObject(3, nohPersistente.id());

            stmt.executeUpdate();
        }
    }
}
