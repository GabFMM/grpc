package com.facul.noh;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class LogRepository {

    public void inserir(int indice, int termo, String comando) {
        String sql = """
            INSERT INTO log (indice, termo, comando)
            VALUES (?, ?, ?)
        """;

        try (Connection conn = Database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, indice);
            stmt.setInt(2, termo);
            stmt.setString(3, comando);

            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void listar() {
        String sql = "SELECT * FROM log ORDER BY indice";

        try (Connection conn = Database.conectar();
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