#!/usr/bin/env bash
# tag-release.sh <version> — set the version in radix4j/build.gradle, commit it, and push the v<version> tag.
#
#   tag-release.sh 1.0.4

set -euo pipefail

RELEASE="${1:-}"

if [[ ! "$RELEASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: $0 <major.minor.patch>" >&2
  exit 1
fi

cd "$(git rev-parse --show-toplevel)"
perl -pi -e "s/^version = '.*'\$/version = '$RELEASE'/" radix4j/build.gradle
git commit -am "Release $RELEASE"
git push origin main
git tag "v$RELEASE"
git push origin "v$RELEASE"
