package com.facul.noh;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private static final String URL = "jdbc:h2:file:./data/raft";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    public static Connection conectar() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static void inicializar() {
        String sql = """
            CREATE TABLE IF NOT EXISTS log (
                indice INT PRIMARY KEY,
                termo INT NOT NULL,
                comando VARCHAR(255) NOT NULL
            )
        """;

        try (Connection conn = Database.conectar();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}