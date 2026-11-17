package com.facul.util;

// Possui métodos usados pelo cliente e pelo nó

import io.github.cdimascio.dotenv.Dotenv;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClienteNohUtil {

    // Perceba que nohsNum não é um array de ids
    // Retorna um mapa que relaciona o id de um nó com seu canal
    public static Map<Integer, ManagedChannel> criarCanais(List<Integer> nohsNum) {
        Map<Integer, ManagedChannel> canais = new HashMap<>();

        Dotenv dotenv = Dotenv.load();
        for (int i = 1; i <= 4; i++) {
            if (nohsNum.contains(i)) {
                String porta = dotenv.get("NOH_" + i + "_PORTA");

                // Se não tiver porta, não há problema,
                // pois o programa deve funcionar com menos de 4 nós
                if (porta != null) {

                    // Usa o getValorEnv, pois sem o idAtual não há como identificar o nó,
                    // logo trata como erro
                    int idAtual = getValorEnv("ID", i);

                    ManagedChannel canal = ManagedChannelBuilder
                            .forAddress("localhost", Integer.parseInt(porta))
                            .usePlaintext()
                            .build();

                    canais.put(idAtual, canal);
                }
            }
        }

        return canais;
    }

    public static int getValorEnv(String nomeCampo, int numeroNoh) {
        Dotenv dotenv = Dotenv.load();

        String str = dotenv.get(String.format("NOH_%d_%s", numeroNoh, nomeCampo));

        if (str == null)
            throw new RuntimeException(
                    String.format(
                            "[ERRO] Não foi possível recuperar o %s do nó %d no .env",
                            nomeCampo,
                            numeroNoh
                    )
            );
        else
            return Integer.parseInt(str);
    }
}
