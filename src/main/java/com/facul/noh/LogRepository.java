package com.facul.noh;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class LogRepository {

    private final Database database;

    public LogRepository(Database database) {
        this.database = database;
    }

    public void inserir(EntradaLog entrada) throws SQLException {
        String sql = """
            INSERT INTO log (indice, termo, comando)
            VALUES (?, ?, ?)
        """;

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, entrada.indice());
            stmt.setInt(2, entrada.termo());
            stmt.setString(3, entrada.comando());

            stmt.executeUpdate();
        }
    }

    public void listar() {
        String sql = "SELECT * FROM log ORDER BY indice";

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                int indice = rs.getInt("indice");
                int termo = rs.getInt("termo");
                String comando = rs.getString("comando");

                System.out.println(
                        indice + " | " + termo + " | " + comando
                );
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
