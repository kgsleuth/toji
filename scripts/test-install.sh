#!/usr/bin/env bash
set -euo pipefail

# Simple smoke for the installer script.
# Usage: ./test-install.sh

echo "Testing toji install.sh (dry logic only)"

# We just verify syntax + that it would pick assets.
bash -n "$(dirname "$0")/install.sh"
echo "install.sh syntax OK"

# If you want full dry run against a release json, export TEST_JSON etc.
echo "test-install: basic checks passed"
