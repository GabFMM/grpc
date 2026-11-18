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

    private static EntradaLog mapear(ResultSet rs) throws SQLException {
        return new EntradaLog(rs.getInt("indice"), rs.getInt("termo"), rs.getString("comando"));
    }

    private List<EntradaLog> consultarLista(String sql, Integer parametro) throws SQLException {
        List<EntradaLog> entradas = new ArrayList<>();

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (parametro != null) {
                stmt.setInt(1, parametro);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entradas.add(mapear(rs));
                }
            }
        }

        return entradas;
    }

    // ------------------------------------------------------------------
    // Log (inclui entradas ainda não comitadas)
    // ------------------------------------------------------------------

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
        return consultarLista("SELECT indice, termo, comando FROM log ORDER BY indice", null);
    }

    /**
     * Retorna as entradas do log a partir do índice informado, incluindo esse índice.
     */
    public List<EntradaLog> getEntradasLogAPartirDe(int indiceInicial) throws SQLException {
        return consultarLista("""
            SELECT indice, termo, comando
            FROM log
            WHERE indice >= ?
            ORDER BY indice
        """, indiceInicial);
    }

    public Optional<EntradaLog> getEntradaLogPorIndice(int indice) throws SQLException {
        List<EntradaLog> lista = consultarLista("""
            SELECT indice, termo, comando
            FROM log
            WHERE indice = ?
        """, indice);

        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.get(0));
    }

    public Optional<EntradaLog> getUltimaEntrada() throws SQLException {
        List<EntradaLog> lista = consultarLista("""
            SELECT indice, termo, comando
            FROM log
            ORDER BY indice DESC
            LIMIT 1
        """, null);

        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.get(0));
    }

    /**
     * Remove a entrada do índice informado e todas as posteriores.
     */
    public void truncarAPartirDe(int indice) throws SQLException {
        String sql = "DELETE FROM log WHERE indice >= ?";

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, indice);
            stmt.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Banco final (somente dados comitados)
    // ------------------------------------------------------------------

    /**
     * Copia do log para o banco final as entradas com indiceAnterior < indice <= novoIndice.
     * É um único comando SQL, portanto atômico.
     */
    public void aplicarCommit(int indiceAnterior, int novoIndice) throws SQLException {
        String sql = """
            INSERT INTO dados (indice, termo, comando)
            SELECT indice, termo, comando
            FROM log
            WHERE indice > ? AND indice <= ?
            ORDER BY indice
        """;

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, indiceAnterior);
            stmt.setInt(2, novoIndice);
            stmt.executeUpdate();
        }
    }

    public List<EntradaLog> getDadosComitados() throws SQLException {
        return consultarLista("SELECT indice, termo, comando FROM dados ORDER BY indice", null);
    }

    public int getUltimoIndiceComitado() throws SQLException {
        String sql = "SELECT COALESCE(MAX(indice), 0) AS maximo FROM dados";

        try (Connection conn = database.conectar();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            return rs.next() ? rs.getInt("maximo") : 0;
        }
    }
}
