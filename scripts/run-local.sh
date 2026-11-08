#!/usr/bin/env bash
# Ejecuta localmente lo mismo que .github/workflows/qe-helper.yml.
# Secretos en .secrets y variables en .vars (formato KEY=VALUE, compatible con `act`).
#
#   ./scripts/run-local.sh QA-100              # contra el mock
#   ./scripts/run-local.sh QA-100 --dry-run
#   ./scripts/run-local.sh QA-100 --jira-cloud # usa JIRA_BASE_URL de .vars
set -euo pipefail
cd "$(dirname "$0")/.."

ISSUE_KEY="${1:-}"; shift || true
TARGET=mock; DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --jira-cloud) TARGET=jira-cloud ;;
    *) echo "Opción desconocida: $arg"; exit 1 ;;
  esac
done
[[ -n "$ISSUE_KEY" ]] || { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ -f .secrets ]] || { echo "Falta .secrets (cp .secrets.example .secrets)"; exit 1; }

set -a; source .secrets; [[ -f .vars ]] && source .vars; set +a

MVN="$(command -v mvn || echo "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn")"
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
QE_ISSUE_KEY="$ISSUE_KEY" DRY_RUN="$DRY_RUN" GITHUB_STEP_SUMMARY="$OUT/summary.md" \
  "$MVN" -B -ntp -q compile exec:java
CODE=$?
set -e
echo; echo "── Summary (lo que verías en Actions) ──"; cat "$OUT/summary.md"
exit $CODE
