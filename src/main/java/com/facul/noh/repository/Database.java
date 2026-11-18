package com.facul.noh.repository;

import java.sql.*;

public class Database {

    private final String url;
    private final int nohId;

    public Database(int nohId) {
        // DB_CLOSE_DELAY=-1: não fecha o banco a cada conexão (fetch roda a cada 100ms)
        // WRITE_DELAY=0: grava em disco imediatamente (sobrevive a kill -9)
        url = "jdbc:h2:file:./data/raft-" + nohId + ";DB_CLOSE_DELAY=-1;WRITE_DELAY=0";
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

        // Banco final: somente entradas comitadas.
        // O índice de commit é recuperado como MAX(indice) desta tabela.
        String sqlDados = """
            CREATE TABLE IF NOT EXISTS dados (
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
            stmt.execute(sqlDados);

            prepStmt.setInt(1, nohId);
            prepStmt.setInt(2, nohId);
            prepStmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("[ERRO] Não foi possível inicializar o banco do nó: " + e.getMessage());
        }
    }
}
