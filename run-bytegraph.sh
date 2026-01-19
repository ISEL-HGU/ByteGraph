#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# ByteGraph Runner (macOS/Linux)
# Usage: ./run-bytegraph.sh <classpath_root>
#
# - arg1 (required): Absolute path to the class or directory
# ============================================================

# 0) Check if the required argument is provided
if [[ $# -lt 1 ]]; then
    echo "[ERROR] Target class path is required."
    echo "Usage: $0 /absolute/path/to/your/classes"
    exit 1
fi

CLASS_PATH="$1"

echo "[INFO] Initializing ByteGraph Analysis..."
echo "[INFO] Class Path: $CLASS_PATH"

# 1) Execute Gradle task
gradle run --console=plain -q --args="$CLASS_PATH" -Dfile.encoding=UTF-8

# 2) Capture and check the exit code
RC=$?
if [ $RC -ne 0 ]; then
    echo "[ERROR] Gradle run failed (exit code=$RC)"
    exit $RC
fi

echo "[INFO] Done. Please check the 'out/' directory for JSON results."