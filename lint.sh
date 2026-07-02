#!/usr/bin/env bash
# Lint the project (detekt, including detekt-formatting). Exits non-zero on findings.
set -euo pipefail
cd "$(dirname "$0")"

exec ./gradlew --console=plain -q detekt
