#!/usr/bin/env bash
# Usage: ./scripts/run.sh [langchain4j|camel|frontend|all]   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env - run ./scripts/setup-dev.sh first"; exit 1; }
set -a; source .env; set +a
export JAVA_HOME="$HOME/.sdkman/candidates/java/$(sed -n 's/^java=//p' .sdkmanrc)"
export PATH="$JAVA_HOME/bin:$PATH"

langchain4j() { mvn -q -f backend-langchain4j/pom.xml quarkus:dev -Ddebug=5005 "$@"; }
camel()       { mvn -q -f backend-camel/pom.xml quarkus:dev -Ddebug=5006 "$@"; }
frontend()    { echo "Frontend: http://localhost:3000"; python3 -m http.server 3000 --bind 127.0.0.1 -d frontend; }

case "${1:-all}" in
  langchain4j|camel|frontend) "$1" ;;
  all)
    trap 'kill 0' EXIT INT TERM
    # no interactive dev console when several apps share one terminal
    langchain4j -Dquarkus.console.enabled=false </dev/null &
    camel -Dquarkus.console.enabled=false </dev/null &
    frontend &
    wait ;;
  *) echo "Usage: $0 [langchain4j|camel|frontend|all]"; exit 1 ;;
esac
