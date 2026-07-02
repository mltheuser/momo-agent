#!/usr/bin/env bash
# Auto-fix formatting findings via detekt, then report anything left over.
set -euo pipefail
cd "$(dirname "$0")"

if ! ./gradlew --console=plain -q detekt --auto-correct; then
    echo "fmt.sh: detekt fixed what it could; the findings above need manual fixes." >&2
    exit 1
fi
