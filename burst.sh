#!/usr/bin/env bash
# One-command on-sale stampede: ./burst.sh <BASE_URL> [extra args, e.g. --hot-users 1000]
# Requires Node 18+ (no npm install needed), or Docker as a fallback.
set -euo pipefail
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
shift || true
DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v node >/dev/null 2>&1 && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 18 ]; then
  exec node "$DIR/burst/burst.mjs" "$BASE_URL" "$@"
else
  exec docker run --rm --network host -e ADMIN_API_KEY="${ADMIN_API_KEY:-}" -v "$DIR/burst:/burst:ro" node:22-alpine \
    node /burst/burst.mjs "$BASE_URL" "$@"
fi
