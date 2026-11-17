package com.facul.noh.repository;

import com.facul.noh.entity.EntradaLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * Retorna as entradas do log a partir do índice informado, incluindo esse índice.
     */
    public List<EntradaLog> getEntradasLogAPartirDe(int indiceInicial) throws SQLException {
        List<EntradaLog> entradas = new ArrayList<>();

        String sql = """
            SELECT indice, termo, comando
            FROM log
            WHERE indice >= ?
            ORDER BY indice
        """;

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, indiceInicial);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entradas.add(new EntradaLog(
                            rs.getInt("indice"),
                            rs.getInt("termo"),
                            rs.getString("comando")
                    ));
                }
            }
        }

        return entradas;
    }

    public Optional<EntradaLog> getEntradaLogPorIndice(int indice) throws SQLException {
        String sql = """
            SELECT indice, termo, comando
            FROM log
            WHERE indice = ?
        """;

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, indice);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new EntradaLog(
                            rs.getInt("indice"),
                            rs.getInt("termo"),
                            rs.getString("comando")
                    ));
                }
            }
        }

        return Optional.empty();
    }
}
