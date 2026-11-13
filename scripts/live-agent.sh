#!/usr/bin/env bash
# Runs the agent harness against a real OpenRouter model (default: liquid/lfm-3-1.7b:free).
# The key comes from .env (OPENROUTER_API_KEY=...), which git ignores; it is never printed.
#   scripts/live-agent.sh                      # default model
#   MODEL=z-ai/glm-5.3-flash scripts/live-agent.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; . ./.env; set +a; fi
: "${OPENROUTER_API_KEY:?Add OPENROUTER_API_KEY=your-key to .env in the repo root}"
export MODEL="${MODEL:-liquid/lfm-3-1.7b:free}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
out=build/live
mkdir -p "$out"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp lib/json-20240303.jar -sourcepath src -d "$out" tests/LiveAgent.java
exec "$JAVA_HOME/bin/java" -cp "$out:lib/json-20240303.jar" LiveAgent
