package com.facul.noh;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private String url = "jdbc:h2:file:./data/raft";

    public Database(int nohId) {
        this.url = "jdbc:h2:file:./data/raft-" + nohId;
    }

    public Connection conectar() throws SQLException {
        String user = "sa";
        String password = "";
        return DriverManager.getConnection(url, user, password);
    }

    public void inicializar() {
        String sql = """
            CREATE TABLE IF NOT EXISTS log (
                indice INT PRIMARY KEY,
                termo INT NOT NULL,
                comando VARCHAR(255) NOT NULL
            )
        """;

        try (Connection conn = conectar();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}