#!/bin/bash
# Local CI: tests, then the package build. Prints failures only.
set -u
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
fail=0
out=$(python3 tools/test.py 2>&1) || { echo "FAIL: tests"; echo "$out" | grep -vE '^(Note:|\.+$|-+$|Ran |OK$|$)' | head -30; fail=1; }
out=$(python3 tools/build.py 2>&1) || { echo "FAIL: build"; echo "$out" | tail -20; fail=1; }
# Public-tree scan: private terms, LAN addresses, broken links (placeholders too when PUBLISH_CHECK=1).
# The scanner lives in private/, which the public repo does not have, so the release workflow skips it.
if [ -x private/scripts/public-snapshot.sh ]; then
  snap=$(mktemp -d)
  out=$(private/scripts/public-snapshot.sh "$snap/out" 2>&1) || { echo "FAIL: public snapshot"; echo "$out" | head -30; fail=1; }
  rm -rf "$snap"
fi
exit $fail
