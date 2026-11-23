#!/usr/bin/env bash
# Bateria de testes automatizada: cenários obrigatórios do enunciado (Trab3 - Replicação, gRPC, modelo Pull)
#
#   scripts/testes.sh            roda tudo (leva alguns minutos)
#   scripts/testes.sh 2 4        roda só os cenários indicados (1, 2, 3, 4A, 4B, 5)
#   MANTER=1 scripts/testes.sh   não encerra os nós ao final
#
# Apaga ./data e .run. Os nós são processos reais (java), derrubados com kill -9.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PASSOU=0
FALHOU=0
CENARIOS=("$@")

ok()     { echo "  [PASSOU] $*"; PASSOU=$((PASSOU + 1)); }
erro()   { echo "  [FALHOU] $*"; FALHOU=$((FALHOU + 1)); }
titulo() { echo; echo "=== $* ==="; }
info()   { echo "  ... $*"; }

verifica() { local desc=$1; shift; if "$@"; then ok "$desc"; else erro "$desc"; fi; }
nega()     { ! "$@"; }
silencioso() { "$@" >/dev/null 2>&1; }
contem()     { grep -q "$2" <<< "$1"; }

deve_rodar() {
    [ ${#CENARIOS[@]} -eq 0 ] && return 0
    local c
    for c in "${CENARIOS[@]}"; do [ "${c^^}" = "${1^^}" ] && return 0; done
    return 1
}

# Repete um comando até dar certo ou estourar o tempo (segundos)
esperar() {
    local timeout=$1; shift
    local fim=$((SECONDS + timeout))
    while [ $SECONDS -lt $fim ]; do
        if "$@"; then return 0; fi
        sleep 1
    done
    return 1
}

# ------------------------------------------------------------------
# Acesso ao cluster pelo cliente (modo não interativo)
# ------------------------------------------------------------------
cli()    { java -cp "$CP" com.facul.cliente.Main "$@" 2>/dev/null; }
estado() { cli estado | grep '^NO='; }

campo() { echo "$1" | tr ' ' '\n' | grep "^$2=" | cut -d= -f2; }
linha_no()  { estado | grep "^NO=$1 "; }
papel_no()  { campo "$(linha_no "$1")" PAPEL; }
termo_no()  { campo "$(linha_no "$1")" TERMO; }
commit_no() { campo "$(linha_no "$1")" COMMIT; }
papel_e()   { [ "$(papel_no "$1")" = "$2" ]; }

# Líder com o maior termo entre os nós que respondem
lider_atual() {
    estado | awk '/PAPEL=LIDER/ {
        for (i = 1; i <= NF; i++) { split($i, k, "="); v[k[1]] = k[2] }
        if (v["TERMO"] + 0 >= m) { m = v["TERMO"] + 0; id = v["NO"] }
    } END { if (id != "") print id }'
}
tem_lider()             { [ -n "$(lider_atual)" ]; }
novo_lider_diferente()  { local l; l=$(lider_atual); [ -n "$l" ] && [ "$l" != "$1" ]; }

# Leitura eventual consistente (só dados comitados)
tem_comando() { cli consultar "$1" | grep -qE "Comando: $2( |$)"; }
# Log completo (inclui entradas não comitadas)
tem_no_log()  { cli log "$1" | grep -qE "Comando: $2( |$)"; }
nao_comitada_no_log() { cli log "$1" | grep -E "Comando: $2( |$)" | grep -q "NÃO COMITADA"; }

seguidores() { local n; for n in 1 2 3 4; do [ "$n" != "$1" ] && echo "$n"; done; }

gravar() { silencioso cli gravar "$@"; }

arquivo_banco() { compgen -G "$RAIZ/data/raft-$1*" >/dev/null; }
limpar_ignorar() { rm -f "$RAIZ"/data/ignorar-replicacao-*; }

encerrar() {
    limpar_ignorar
    if [ "${MANTER:-0}" != "1" ]; then
        parar_todos >/dev/null 2>&1
    fi
}
trap encerrar EXIT

# ------------------------------------------------------------------
# Preparação
# ------------------------------------------------------------------
titulo "Preparação"
preparar
parar_todos >/dev/null 2>&1
rm -rf "$RAIZ/data" "$RUN_DIR"; mkdir -p "$RUN_DIR" "$RAIZ/data"

for n in 1 2 3 4; do iniciar_noh "$n" >/dev/null; done
verifica "os 4 nós elegem um líder (até 40 s)" esperar 40 tem_lider
sleep 3   # dá tempo das réplicas descobrirem o líder e enviarem FetchRequest

L=$(lider_atual)
info "líder inicial: nó $L, termo $(termo_no "$L")"
verifica "console dos nós exibe papel, termo e índice de commit" \
    grep -qE "papel=.*termo=.*commit=" "$RUN_DIR/noh-$L.log"

# ------------------------------------------------------------------
# Cenário 1 - Escrita normal e leitura distribuída
# ------------------------------------------------------------------
if deve_rodar 1; then
    titulo "Cenário 1 - Escrita normal e leitura distribuída"
    L=$(lider_atual)
    verifica "gravação C1-A no líder (nó $L) confirmada somente após o commit" gravar C1-A
    for n in 1 2 3 4; do
        verifica "nó $n lê C1-A (dado comitado)" esperar 15 tem_comando "$n" C1-A
    done
    F=$(seguidores "$L" | head -1)
    SAIDA=$(cli gravar-em "$F" C1-B); RC=$?
    verifica "gravar em um seguidor (nó $F) redireciona ao líder $L e confirma" [ "$RC" -eq 0 ]
    verifica "cliente foi informado de quem é o líder" contem "$SAIDA" "Redirecionado para o líder $L"
    for n in 1 2 3 4; do
        verifica "nó $n lê C1-B" esperar 15 tem_comando "$n" C1-B
    done
fi

# ------------------------------------------------------------------
# Cenário 2 - Eleição automática por queda do líder
# ------------------------------------------------------------------
if deve_rodar 2; then
    titulo "Cenário 2 - Eleição automática por queda do líder"
    L=$(lider_atual); T=$(termo_no "$L")
    info "derrubando o líder (nó $L, termo $T) com kill -9"
    matar_noh "$L"

    verifica "novo líder eleito automaticamente (até 40 s)" esperar 40 novo_lider_diferente "$L"
    L2=$(lider_atual); T2=$(termo_no "$L2")
    info "novo líder: nó $L2, termo $T2"
    verifica "termo incrementado ($T -> $T2)" [ "${T2:-0}" -gt "${T:-0}" ]

    verifica "novo líder aceita gravação C2-B" esperar 40 gravar C2-B

    info "reiniciando o nó $L (testa persistência em disco)"
    iniciar_noh "$L" >/dev/null
    verifica "nó $L volta como SEGUIDOR" esperar 30 papel_e "$L" SEGUIDOR
    verifica "nó $L manteve em disco os dados antigos (C1-A)" esperar 30 tem_comando "$L" C1-A
    verifica "nó $L recebe C2-B do novo líder" esperar 30 tem_comando "$L" C2-B
fi

# ------------------------------------------------------------------
# Cenário 3 - Bloqueio por perda de quórum
# ------------------------------------------------------------------
if deve_rodar 3; then
    titulo "Cenário 3 - Bloqueio por perda de quórum"
    esperar 40 tem_lider; L=$(lider_atual)
    SEG=($(seguidores "$L"))
    info "líder: nó $L; derrubando as réplicas ${SEG[0]} e ${SEG[1]} (restam 2 nós < quórum 3)"
    matar_noh "${SEG[0]}"; matar_noh "${SEG[1]}"
    sleep 5   # heartbeat timeout do líder

    SAIDA=$(cli gravar C3-X); RC=$?
    verifica "líder recusa a gravação sem quórum (código de saída != 0)" [ "$RC" -ne 0 ]
    verifica "cliente é informado da falta de quórum" contem "$SAIDA" "quórum"
    verifica "líder continua LÍDER (não aceita dado que não pode comitar)" papel_e "$L" LIDER
    verifica "C3-X não existe no log do líder" nega tem_no_log "$L" C3-X
    verifica "console do líder sinaliza SEM QUÓRUM" grep -q "SEM QUÓRUM" "$RUN_DIR/noh-$L.log"

    info "religando a réplica ${SEG[0]} (3 nós ativos = quórum)"
    iniciar_noh "${SEG[0]}" >/dev/null
    verifica "com quórum restabelecido a escrita volta (C3-Y)" esperar 40 gravar C3-Y
    iniciar_noh "${SEG[1]}" >/dev/null
    verifica "réplica ${SEG[0]} recebe C3-Y" esperar 30 tem_comando "${SEG[0]}" C3-Y
    verifica "réplica ${SEG[1]} recebe C3-Y" esperar 30 tem_comando "${SEG[1]}" C3-Y
fi

# ------------------------------------------------------------------
# Cenário 4A - Réplica desatualizada
# ------------------------------------------------------------------
if deve_rodar 4A; then
    titulo "Cenário 4A - Recuperação de réplica desatualizada"
    esperar 40 tem_lider; L=$(lider_atual)
    for n in 1 2 3 4; do iniciar_noh "$n" >/dev/null; done   # garante todos de pé
    sleep 4
    F=$(seguidores "$L" | head -1)
    info "derrubando o seguidor $F e gravando com os 3 nós restantes"
    matar_noh "$F"
    sleep 1
    verifica "gravação C4A-1 com um seguidor fora" esperar 40 gravar C4A-1
    verifica "gravação C4A-2 com um seguidor fora" esperar 40 gravar C4A-2

    info "religando o nó $F"
    iniciar_noh "$F" >/dev/null
    verifica "nó $F busca C4A-1 via FetchRequest" esperar 40 tem_comando "$F" C4A-1
    verifica "nó $F busca C4A-2 via FetchRequest" esperar 40 tem_comando "$F" C4A-2
    verifica "log do nó $F registra a replicação recebida" grep -q "REPLICAÇÃO.*C4A-1" "$RUN_DIR/noh-$F.log"
fi

# ------------------------------------------------------------------
# Cenário 4B - Truncamento por divergência
# ------------------------------------------------------------------
if deve_rodar 4B; then
    titulo "Cenário 4B - Truncamento de log por divergência"
    for n in 1 2 3 4; do iniciar_noh "$n" >/dev/null; done
    esperar 40 tem_lider; sleep 4
    L=$(lider_atual); T=$(termo_no "$L")
    SEG=($(seguidores "$L"))
    info "líder N1=$L (termo $T). Réplicas ${SEG[*]} passam a ignorar a replicação (só heartbeat)"
    for s in "${SEG[@]}"; do touch "$RAIZ/data/ignorar-replicacao-$s"; done
    sleep 2

    cli gravar C4B-ORFA >/dev/null &
    CPID=$!
    verifica "líder $L grava C4B-ORFA localmente" esperar 10 tem_no_log "$L" C4B-ORFA
    info "crash do líder $L antes da replicação"
    matar_noh "$L"
    wait "$CPID" 2>/dev/null
    limpar_ignorar

    for s in "${SEG[@]}"; do
        verifica "réplica $s NÃO recebeu C4B-ORFA" nega tem_no_log "$s" C4B-ORFA
    done

    verifica "novo líder N2 eleito (até 40 s)" esperar 40 novo_lider_diferente "$L"
    L2=$(lider_atual); T2=$(termo_no "$L2")
    verifica "N2 (nó $L2) está em termo maior ($T -> $T2)" [ "${T2:-0}" -gt "${T:-0}" ]
    verifica "nova escrita C4B-NOVA comitada por N2" esperar 40 gravar C4B-NOVA

    info "religando o antigo líder N1 (nó $L)"
    iniciar_noh "$L" >/dev/null
    verifica "N1 muda para SEGUIDOR" esperar 40 papel_e "$L" SEGUIDOR
    verifica "N1 ressincroniza e recebe C4B-NOVA" esperar 40 tem_no_log "$L" C4B-NOVA
    verifica "N1 truncou (deletou) a entrada não comitada C4B-ORFA" nega tem_no_log "$L" C4B-ORFA
    verifica "log do N1 registra o [TRUNCAMENTO]" grep -q "TRUNCAMENTO" "$RUN_DIR/noh-$L.log"
    verifica "N1 lê C4B-NOVA como dado comitado" esperar 30 tem_comando "$L" C4B-NOVA
fi

# ------------------------------------------------------------------
# Cenário 5 - Isolamento de dados uncommitted
# ------------------------------------------------------------------
if deve_rodar 5; then
    titulo "Cenário 5 - Isolamento de dados não comitados"
    for n in 1 2 3 4; do iniciar_noh "$n" >/dev/null; done
    esperar 40 tem_lider; sleep 4
    L=$(lider_atual)
    SEG=($(seguidores "$L"))
    info "líder $L; réplicas ${SEG[0]} e ${SEG[1]} ignoram a replicação; só ${SEG[2]} replica (líder + 1 = 2 < quórum)"
    touch "$RAIZ/data/ignorar-replicacao-${SEG[0]}" "$RAIZ/data/ignorar-replicacao-${SEG[1]}"
    sleep 2

    cli gravar C5-X1 >/dev/null &
    CPID=$!
    verifica "réplica ${SEG[2]} recebeu C5-X1 via FetchRequest (está no log)" esperar 15 tem_no_log "${SEG[2]}" C5-X1
    verifica "no log da réplica ${SEG[2]} C5-X1 está marcada como NÃO COMITADA" nao_comitada_no_log "${SEG[2]}" C5-X1
    verifica "leitura na réplica ${SEG[2]} NÃO mostra C5-X1" nega tem_comando "${SEG[2]}" C5-X1
    verifica "leitura no líder NÃO mostra C5-X1 (sem quórum)" nega tem_comando "$L" C5-X1
    verifica "réplica ${SEG[0]} (ignorando replicação) não armazenou C5-X1" nega tem_no_log "${SEG[0]}" C5-X1
    wait "$CPID" 2>/dev/null

    info "liberando a replicação nas réplicas ${SEG[0]} e ${SEG[1]}"
    limpar_ignorar
    for n in 1 2 3 4; do
        verifica "após o commit pelo quórum o nó $n passa a ler C5-X1" esperar 40 tem_comando "$n" C5-X1
    done
fi

# ------------------------------------------------------------------
# Persistência
# ------------------------------------------------------------------
titulo "Persistência obrigatória"
for n in 1 2 3 4; do
    verifica "banco H2 do nó $n existe em disco (termo, voto, log e dados)" arquivo_banco "$n"
done

titulo "Resumo"
echo "  Passaram: $PASSOU   Falharam: $FALHOU"
echo "  Logs dos nós em .run/noh-N.log"
[ "$FALHOU" -eq 0 ]
