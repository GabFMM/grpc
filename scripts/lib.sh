#!/usr/bin/env bash
# Funções compartilhadas pelos scripts (não executar diretamente).

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ" || exit 1

RUN_DIR="$RAIZ/.run"
CP_FILE="$RAIZ/target/cp.txt"
mkdir -p "$RUN_DIR"

# Cria um .env padrão (IDs 1..4, portas 50051..50054) se ele não existir
garantir_env() {
    if [ ! -f "$RAIZ/.env" ]; then
        echo "[INFO] .env não encontrado; criando com IDs 1..4 e portas 50051..50054"
        for i in 1 2 3 4; do
            echo "NOH_${i}_ID=${i}"
            echo "NOH_${i}_PORTA=$((50050 + i))"
            echo
        done > "$RAIZ/.env"
    fi

    # Os scripts assumem que o ID do nó N é N
    for i in 1 2 3 4; do
        if ! grep -q "^NOH_${i}_ID=${i}\s*$" "$RAIZ/.env"; then
            echo "[ERRO] Os scripts exigem NOH_${i}_ID=${i} no .env" >&2
            exit 1
        fi
    done
}

carregar_cp() {
    CP="$RAIZ/target/classes:$(cat "$CP_FILE")"
}

# Compila (gera as classes do protobuf) e calcula o classpath. NO_BUILD=1 pula a compilação.
preparar() {
    garantir_env

    if [ "${NO_BUILD:-0}" != "1" ] || [ ! -d "$RAIZ/target/classes" ]; then
        mvn -q -B compile || { echo "[ERRO] Falha na compilação" >&2; exit 1; }
    fi

    if [ ! -f "$CP_FILE" ] || [ "$RAIZ/pom.xml" -nt "$CP_FILE" ]; then
        mvn -q -B dependency:build-classpath -Dmdep.outputFile="$CP_FILE" \
            || { echo "[ERRO] Falha ao calcular o classpath" >&2; exit 1; }
    fi

    carregar_cp
}

noh_vivo() {
    local pidfile="$RUN_DIR/noh-$1.pid"
    [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null
}

iniciar_noh() {
    local n=$1
    if noh_vivo "$n"; then
        echo "nó $n já está rodando (pid $(cat "$RUN_DIR/noh-$n.pid"))"
        return 0
    fi
    echo "=== $(date +%T) iniciando nó $n ===" >> "$RUN_DIR/noh-$n.log"
    nohup java -cp "$CP" com.facul.noh.Main "$n" >> "$RUN_DIR/noh-$n.log" 2>&1 &
    echo $! > "$RUN_DIR/noh-$n.pid"
    echo "nó $n iniciado (pid $!, log em .run/noh-$n.log)"
}

# Simula um crash (kill -9)
matar_noh() {
    local n=$1 pidfile="$RUN_DIR/noh-$1.pid"
    if noh_vivo "$n"; then
        kill -9 "$(cat "$pidfile")" 2>/dev/null
        echo "=== $(date +%T) nó $n morto com kill -9 ===" >> "$RUN_DIR/noh-$n.log"
        echo "nó $n derrubado (kill -9)"
    fi
    rm -f "$pidfile"
}

# Encerramento normal (SIGTERM)
parar_noh() {
    local n=$1 pidfile="$RUN_DIR/noh-$1.pid"
    if noh_vivo "$n"; then
        kill "$(cat "$pidfile")" 2>/dev/null
        echo "nó $n encerrado"
    fi
    rm -f "$pidfile"
}

parar_todos() {
    for n in 1 2 3 4; do parar_noh "$n"; done
    # garante que nenhum java de nó sobrou
    pkill -f "com.facul.noh.Main" 2>/dev/null || true
}
