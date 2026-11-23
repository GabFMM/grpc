#!/usr/bin/env bash
# Gerencia os 4 nós em segundo plano (PIDs e logs em .run/)
#   scripts/cluster.sh start [N...]   sobe os nós (padrão: 1 2 3 4)
#   scripts/cluster.sh stop           encerra todos
#   scripts/cluster.sh status         papel/termo/commit de cada nó
#   scripts/cluster.sh kill N         derruba o nó N com kill -9 (crash)
#   scripts/cluster.sh restart N      sobe o nó N de novo
#   scripts/cluster.sh logs           acompanha os logs de todos os nós
#   scripts/cluster.sh clean          encerra tudo e apaga ./data e .run
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case "${1:-}" in
    start)
        preparar
        nos=("${@:2}")
        [ ${#nos[@]} -eq 0 ] && nos=(1 2 3 4)
        for n in "${nos[@]}"; do iniciar_noh "$n"; done
        ;;
    stop)
        parar_todos
        ;;
    status)
        NO_BUILD=1 preparar
        java -cp "$CP" com.facul.cliente.Main estado 2>/dev/null | grep '^NO='
        ;;
    kill)
        [[ "${2:-}" =~ ^[1-4]$ ]] || { sed -n '2,9p' "$0"; exit 1; }
        matar_noh "$2"
        ;;
    restart)
        [[ "${2:-}" =~ ^[1-4]$ ]] || { sed -n '2,9p' "$0"; exit 1; }
        NO_BUILD=1 preparar
        parar_noh "$2"
        iniciar_noh "$2"
        ;;
    logs)
        exec tail -n 20 -F "$RUN_DIR"/noh-*.log
        ;;
    clean)
        parar_todos
        rm -rf "$RAIZ/data" "$RUN_DIR"
        echo "estado apagado"
        ;;
    *)
        sed -n '2,9p' "$0"
        exit 1
        ;;
esac
