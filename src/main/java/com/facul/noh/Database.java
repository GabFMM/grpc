package com.facul.noh;

import com.facul.noh.entity.NohPersistente;

import java.sql.*;

public class Database {

    private final String url;
    private final int nohId;

    public Database(int nohId) {
        url = "jdbc:h2:file:./data/raft-" + nohId;
        this.nohId = nohId;
    }

    public Connection conectar() throws SQLException {
        String user = "sa";
        String password = "";
        return DriverManager.getConnection(url, user, password);
    }


    public void inicializar() {
        String sqlNoh = """
            CREATE TABLE IF NOT EXISTS noh (
                id INT PRIMARY KEY,
                votouId INT,
                termo INT NOT NULL
            )
        """;

        String sqlInsertNoh = """
            INSERT INTO noh (id, votouId, termo)
            SELECT ?, -1, 0
            WHERE NOT EXISTS (
                SELECT 1 FROM noh WHERE id = ?
            )
        """;

        String sqlLog = """
            CREATE TABLE IF NOT EXISTS log (
                indice INT PRIMARY KEY,
                termo INT NOT NULL,
                comando VARCHAR(255) NOT NULL
            )
        """;

        try (Connection conn = conectar();
             Statement stmt = conn.createStatement();
             PreparedStatement prepStmt =
                     conn.prepareStatement(sqlInsertNoh)) {

            stmt.execute(sqlNoh);
            stmt.execute(sqlLog);

            prepStmt.setInt(1, nohId);
            prepStmt.setInt(2, nohId);
            prepStmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("[ERRO] Não foi possível inicializar o banco do nó: " + e.getMessage());
        }
    }

    public NohPersistente recuperarNohPersistente() {
        String sqlNoh = """
            SELECT termo, votouId
            FROM noh
            WHERE id = ?
        """;

        String sqlLog = """
            SELECT indice, termo
            FROM log
            ORDER by indice DESC
            LIMIT 1    
        """;

        try (Connection conn = conectar();
             Statement stmt = conn.createStatement();
             PreparedStatement prepStmt = conn.prepareStatement(sqlNoh)) {

            // Tabela noh
            int termo = 0;
            int votouId = -1;

            prepStmt.setInt(1, nohId);
            try (ResultSet rs = prepStmt.executeQuery()) {
                if (rs.next()) {
                    termo = rs.getInt("termo");
                    votouId = rs.getInt("votouId");
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

}