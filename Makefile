# WorkStream v3 benchmark harness.
#
# Runs the Go benchmark server (from the exile/exile repo) against the sati
# Java benchmark harness to measure end-to-end throughput and latency.
#
# Usage:
#   make benchmark                          # runs all scenarios, 60 s each
#   make benchmark SCENARIO=mixed-steady   # run one scenario
#   make benchmark DURATION=30             # shorter run
#
# Env overrides:
#   EXILE_REPO            path to exile/exile git repo
#                         (default: ../../tcn/exile/exile)
#   BENCH_CERTS           cert output dir (default: /tmp/sati-benchmark/certs)
#   BENCH_REPORTS         JSON report dir (default: benchmarks/results)
#   MAX_CONCURRENCY       client maxConcurrency (default: 5 — the current sati default)
#   JOB_LATENCY_MS        plugin latency per job
#   EVENT_LATENCY_MS      plugin latency per event
#   PLUGIN_ERROR_RATE     0..1 probability the plugin throws per item
#
# Java is provisioned via SDKMAN using this repo's .sdkmanrc (currently
# 21.0.9-amzn). Requires SDKMAN installed at $HOME/.sdkman/
# (`curl -s https://get.sdkman.io | bash`). Works with the stock macOS
# `make` (GNU Make 3.81) — recipes wrap their bodies in `bash -c '...'`
# so SDKMAN's `sdk env` stays in effect for the whole recipe.

EXILE_REPO        ?= $(abspath $(CURDIR)/../../tcn/exile/exile)
BENCH_CERTS       ?= /tmp/sati-benchmark/certs
BENCH_REPORTS     ?= $(CURDIR)/benchmarks/results
BENCH_SRV_BIN     ?= $(CURDIR)/benchmarks/build/gatev3-benchmark-server
BENCH_SRV_LOG     ?= $(CURDIR)/benchmarks/build/gatev3-benchmark-server.log
BENCH_SRV_PID     ?= $(CURDIR)/benchmarks/build/gatev3-benchmark-server.pid
GRPC_PORT         ?= 50051
HTTP_PORT         ?= 50052
DURATION          ?= 60
SCENARIO          ?= all
MAX_CONCURRENCY   ?= 5
JOB_LATENCY_MS    ?= 10
EVENT_LATENCY_MS  ?= 5
PLUGIN_ERROR_RATE ?= 0.0

.PHONY: benchmark build-bench-server bench-clean check-sdkman

check-sdkman:
	@bash -c ' \
		set -e; \
		if [[ ! -s "$$HOME/.sdkman/bin/sdkman-init.sh" ]]; then \
			echo "ERROR: SDKMAN not found at $$HOME/.sdkman/. Install: curl -s https://get.sdkman.io | bash"; \
			exit 1; \
		fi; \
		source "$$HOME/.sdkman/bin/sdkman-init.sh"; \
		sdk env >/dev/null; \
		echo "sdkman ok — JAVA_HOME=$$JAVA_HOME"; \
		echo "          java: $$(java -version 2>&1 | head -1)"; \
	'

benchmark: build-bench-server
	@bash -c ' \
		set -eo pipefail; \
		if [[ ! -s "$$HOME/.sdkman/bin/sdkman-init.sh" ]]; then \
			echo "ERROR: SDKMAN not found. Run: make check-sdkman"; exit 1; \
		fi; \
		source "$$HOME/.sdkman/bin/sdkman-init.sh"; \
		sdk env >/dev/null; \
		echo "==> JAVA_HOME=$$JAVA_HOME"; \
		echo "==> generating certs"; \
		mkdir -p "$(BENCH_CERTS)"; \
		"$(BENCH_SRV_BIN)" --generate-certs-only --certs="$(BENCH_CERTS)"; \
		echo "==> starting benchmark server"; \
		mkdir -p "$(dir $(BENCH_SRV_LOG))"; \
		"$(BENCH_SRV_BIN)" \
			--certs="$(BENCH_CERTS)" \
			--grpc=":$(GRPC_PORT)" \
			--http=":$(HTTP_PORT)" \
			> "$(BENCH_SRV_LOG)" 2>&1 & \
		srv_pid=$$!; \
		echo $$srv_pid > "$(BENCH_SRV_PID)"; \
		trap "kill $$srv_pid 2>/dev/null || true; rm -f \"$(BENCH_SRV_PID)\"" EXIT INT TERM; \
		echo "==> running benchmark harness (scenario=$(SCENARIO), duration=$(DURATION)s)"; \
		./gradlew :benchmarks:run -q --args="--certs=$(BENCH_CERTS) --grpc-host=localhost --grpc-port=$(GRPC_PORT) --control-url=http://localhost:$(HTTP_PORT) --duration=$(DURATION) --reports=$(BENCH_REPORTS) --scenario=$(SCENARIO) --max-concurrency=$(MAX_CONCURRENCY) --job-latency-ms=$(JOB_LATENCY_MS) --event-latency-ms=$(EVENT_LATENCY_MS) --plugin-error-rate=$(PLUGIN_ERROR_RATE)"; \
		echo "==> server log: $(BENCH_SRV_LOG)"; \
	'

build-bench-server:
	@bash -c ' \
		set -e; \
		if [[ ! -d "$(EXILE_REPO)" ]]; then \
			echo "ERROR: EXILE_REPO=$(EXILE_REPO) not found. Set it to the path of your exile/exile clone."; \
			exit 1; \
		fi; \
		mkdir -p "$(dir $(BENCH_SRV_BIN))"; \
		echo "==> building benchmark server from $(EXILE_REPO)"; \
		cd "$(EXILE_REPO)" && go build -o "$(BENCH_SRV_BIN)" ./gatev3-benchmark-server; \
	'

bench-clean:
	@rm -rf "$(BENCH_CERTS)" "$(BENCH_SRV_BIN)" "$(BENCH_SRV_PID)" "$(BENCH_SRV_LOG)"
	@rm -rf "$(BENCH_REPORTS)"
	@echo "benchmark artifacts cleaned"
