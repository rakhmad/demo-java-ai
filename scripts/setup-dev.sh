#!/usr/bin/env bash
# Installs JDK 25 (via sdkman) and checks Maven/Python for the frontend server.
set -euo pipefail
cd "$(dirname "$0")/.."
JDK=$(sed -n 's/^java=//p' .sdkmanrc)

if [ ! -d "$HOME/.sdkman" ]; then
  echo "Installing sdkman..."
  curl -s "https://get.sdkman.io?rcupdate=false" | bash
fi
set +u; source "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
[ -d "$HOME/.sdkman/candidates/java/$JDK" ] || echo n | sdk install java "$JDK"   # "n" = do not make it the global default
command -v mvn >/dev/null || sdk install maven < /dev/null
command -v python3 >/dev/null || { echo "python3 is required to serve the frontend"; exit 1; }

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env - put your OPENAI_API_KEY in it."
fi
echo "JDK $JDK ready. Run: ./scripts/run.sh all"
