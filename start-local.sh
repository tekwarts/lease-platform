#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

for command in docker npm java curl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing prerequisite: $command. See README.md."
    exit 1
  fi
done

if ! docker info >/dev/null 2>&1; then
  echo "Docker is unavailable. Start Docker Desktop and run this script from your normal terminal."
  exit 1
fi

if command -v lsof >/dev/null 2>&1; then
  for port in 8080 5173; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Port $port is already in use. Stop its existing server before starting LeaseFlow."
      exit 1
    fi
  done
fi

docker compose up -d --wait postgres redis
npm --prefix web install --no-audit --no-fund
mkdir -p target/local

app_pids=()
cleanup() {
  for pid in "${app_pids[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "${app_pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_until_ready() {
  local name="$1" url="$2" pid="$3" log="$4"
  local attempt
  for attempt in {1..120}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$name stopped during startup. Read $log for details."
      return 1
    fi
    if curl --silent --fail --max-time 1 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready within the startup window. Read $log for details."
  return 1
}

echo "Starting backend…"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local >target/local/backend.log 2>&1 &
backend_pid=$!
app_pids+=("$backend_pid")
wait_until_ready "Backend" "http://127.0.0.1:8080/api/v1/csrf" "$backend_pid" "target/local/backend.log"

echo "Starting frontend…"
npm --prefix web run dev >target/local/frontend.log 2>&1 &
frontend_pid=$!
app_pids+=("$frontend_pid")
wait_until_ready "Frontend" "http://127.0.0.1:5173" "$frontend_pid" "target/local/frontend.log"

echo ""
echo "LeaseFlow is ready: http://127.0.0.1:5173"
echo "Backend: http://127.0.0.1:8080"
echo "Logs: target/local/backend.log and target/local/frontend.log"
echo "Press Ctrl+C to stop both app processes. PostgreSQL and Redis remain running."

while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 2
done
echo "An app process stopped. Check its log; stopping the other app process."
exit 1
