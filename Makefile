.PHONY: help build test unit cluster stop status logs clean noh cliente kill restart

N ?= 1

help:
	@echo "make build            compila (gera as classes do protobuf)"
	@echo "make unit             testes unitários (JUnit)"
	@echo "make cluster          sobe os 4 nós em segundo plano (logs em .run/)"
	@echo "make status           papel/termo/commit de cada nó"
	@echo "make cliente          cliente interativo"
	@echo "make noh N=2          sobe o nó N em primeiro plano"
	@echo "make kill N=2         derruba o nó N com kill -9"
	@echo "make restart N=2      sobe o nó N de novo"
	@echo "make logs             acompanha os logs dos nós"
	@echo "make test             bateria de testes dos cenários do enunciado"
	@echo "make stop             encerra os nós"
	@echo "make clean            encerra tudo e apaga ./data e .run"

build:
	mvn -q -B compile

unit:
	mvn -B test

cluster:
	@scripts/cluster.sh start

stop:
	@scripts/cluster.sh stop

status:
	@scripts/cluster.sh status

logs:
	@scripts/cluster.sh logs

clean:
	@scripts/cluster.sh clean

noh:
	@scripts/run.sh noh $(N)

cliente:
	@scripts/run.sh cliente

kill:
	@scripts/cluster.sh kill $(N)

restart:
	@scripts/cluster.sh restart $(N)

test:
	@scripts/testes.sh
