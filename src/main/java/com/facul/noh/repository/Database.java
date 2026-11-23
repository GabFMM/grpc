package com.facul.noh.repository;

import java.sql.*;

public class Database {

    private final String url;
    private final int nohId;

    public Database(int nohId) {
        // DB_CLOSE_DELAY=-1: não fecha o arquivo a cada conexão (há fetch periódico)
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
                termo INT NOT NULL,
                indice_commit INT NOT NULL
            )
        """;

        String sqlInsertNoh = """
            INSERT INTO noh (id, votou_id, termo, indice_commit)
            SELECT ?, -1, 0, 0
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

        // Banco final: somente entradas comitadas (visíveis para leitura)
        String sqlDados = """
            CREATE TABLE IF NOT EXISTS dados (
                indice INT PRIMARY KEY,
                termo INT NOT NULL,
                comando VARCHAR(255) NOT NULL
            )
        """;

        try (Connection conn = conectar();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sqlNoh);
            stmt.execute(sqlLog);
            stmt.execute(sqlDados);

            // Só prepara o INSERT depois que as tabelas foram criadas
            try (PreparedStatement prepStmt = conn.prepareStatement(sqlInsertNoh)) {
                prepStmt.setInt(1, nohId);
                prepStmt.setInt(2, nohId);
                prepStmt.executeUpdate();
            }

        } catch (SQLException e) {
            System.err.println("[ERRO] Não foi possível inicializar o banco do nó: " + e.getMessage());
        }
    }
}