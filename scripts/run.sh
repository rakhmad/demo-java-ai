#!/usr/bin/env bash
# Usage: ./scripts/run.sh [langchain4j|camel|frontend|jaeger|all]   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env - run ./scripts/setup-dev.sh first"; exit 1; }
set -a; source .env; set +a
export JAVA_HOME="$HOME/.sdkman/candidates/java/$(sed -n 's/^java=//p' .sdkmanrc)"
export PATH="$JAVA_HOME/bin:$PATH"

langchain4j() { mvn -q -f backend-langchain4j/pom.xml quarkus:dev -Ddebug=5005 "$@"; }
camel()       { mvn -q -f backend-camel/pom.xml quarkus:dev -Ddebug=5006 "$@"; }
# Optional trace viewer. Set TRACES_EXPORTER=cdi in .env, then restart the backends.
jaeger()      { local c; c=$(command -v docker || command -v podman) || { echo "docker or podman is required"; exit 1; }
                echo "Jaeger UI: http://localhost:16686"
                "$c" run --rm --name java-tutor-jaeger -p 16686:16686 -p 4317:4317 docker.io/jaegertracing/jaeger:2.1.0; }
frontend()    { echo "Frontend: http://localhost:3000"; python3 -m http.server 3000 --bind 127.0.0.1 -d frontend; }

case "${1:-all}" in
  langchain4j|camel|frontend|jaeger) "$1" ;;
  all)
    trap 'kill 0' EXIT INT TERM
    # no interactive dev console when several apps share one terminal
    langchain4j -Dquarkus.console.enabled=false </dev/null &
    camel -Dquarkus.console.enabled=false </dev/null &
    frontend &
    wait ;;
  *) echo "Usage: $0 [langchain4j|camel|frontend|jaeger|all]"; exit 1 ;;
esac
