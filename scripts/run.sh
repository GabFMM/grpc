#!/usr/bin/env bash
# Uso:
#   scripts/run.sh noh <1-4>        sobe um nó em primeiro plano
#   scripts/run.sh cliente          cliente interativo
#   scripts/run.sh cliente estado   (comandos não interativos: estado | lider | gravar <txt> | consultar <id> | log <id>)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

uso() { sed -n '2,6p' "$0"; exit 1; }

case "${1:-}" in
    noh)
        [[ "${2:-}" =~ ^[1-4]$ ]] || uso
        preparar
        exec java -cp "$CP" com.facul.noh.Main "$2"
        ;;
    cliente)
        shift
        preparar
        exec java -cp "$CP" com.facul.cliente.Main "$@"
        ;;
    *) uso ;;
esac
