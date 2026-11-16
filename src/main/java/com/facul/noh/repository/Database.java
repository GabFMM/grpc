package com.facul.noh.repository;

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
                votou_id INT,
                termo INT NOT NULL
            )
        """;

        String sqlInsertNoh = """
            INSERT INTO noh (id, votou_id, termo)
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
}
