#!/usr/bin/env bash
# Ejecuta localmente lo mismo que .github/workflows/qe-helper.yml.
# Secretos en .secrets y variables en .vars (formato KEY=VALUE, compatible con `act`).
#
#   ./scripts/run-local.sh QA-100              # contra el mock
#   ./scripts/run-local.sh QA-100 --jira-cloud # usa JIRA_BASE_URL de .vars
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

ISSUE_KEY="${1:-}"; shift || true
TARGET=mock
for arg in "$@"; do
  case "$arg" in
    --jira-cloud) TARGET=jira-cloud ;;
    *) echo "Opción desconocida: $arg"; exit 1 ;;
  esac
done
[[ -n "$ISSUE_KEY" ]] || { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ -f .secrets ]] || { echo "Falta .secrets (cp .secrets.example .secrets)"; exit 1; }

set -a; source .secrets; [[ -f .vars ]] && source .vars; set +a

"$MVN" -B -ntp -q package
OUT="target/local-run"; mkdir -p "$OUT"; : > "$OUT/summary.md"

if [[ "$TARGET" == mock ]]; then
  MOCK_JIRA_EMAIL="${JIRA_EMAIL:-}" MOCK_JIRA_TOKEN="${JIRA_API_TOKEN:-}" \
    python3 mock/jira_mock.py > "$OUT/mock.log" 2>&1 &
  MOCK_PID=$!; trap 'kill $MOCK_PID 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8089/health >/dev/null && break; sleep 0.3; done
  curl -sf http://127.0.0.1:8089/health >/dev/null || { cat "$OUT/mock.log"; exit 1; }
  export JIRA_BASE_URL=http://127.0.0.1:8089
fi

set +e
QE_ISSUE_KEY="$ISSUE_KEY" GITHUB_STEP_SUMMARY="$OUT/summary.md" java -jar target/qe-helper.jar
CODE=$?
set -e
echo; echo "── Summary (lo que verías en Actions) ──"; cat "$OUT/summary.md"
exit $CODE
