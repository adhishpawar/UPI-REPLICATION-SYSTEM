#!/usr/bin/env bash
# Serve the backend showcase.
#
# jwebserver ships with the JDK (18+), so this needs no Node, no npm, and no
# build step. That is not a limitation being worked around -- a single static
# page needs nothing more, and having no toolchain is what keeps this folder
# genuinely disposable.
#
# The showcase talks to the backend over HTTP only. Deleting this folder has
# no effect on the platform.

PORT="${PORT:-8090}"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Backend showcase:  http://localhost:$PORT"
echo "Expects the payment orchestrator at http://localhost:8083"
echo
exec jwebserver -p "$PORT" -d "$DIR" -b 127.0.0.1
