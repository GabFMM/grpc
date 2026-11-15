package com.facul.noh;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

    public List<EntradaLog> getEntradasLog() throws SQLException {
        List<EntradaLog> entradas = new ArrayList<>();

        String sql = "SELECT * FROM log ORDER BY indice";

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                int indice = rs.getInt("indice");
                int termo = rs.getInt("termo");
                String comando = rs.getString("comando");

                entradas.add(new EntradaLog(indice, termo, comando));
            }
        }

        return entradas;
    }
}
