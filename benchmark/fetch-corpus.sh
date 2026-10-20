#!/usr/bin/env bash
# Downloads trafilatura's evaluation corpus (tests/ at the ported version) into benchmark/data/.
# The pages belong to their sites, so they are fetched rather than committed.
set -euo pipefail
cd "$(dirname "$0")"
TAG=v2.3.1
DEST=data/trafilatura-$TAG
if [ -f "$DEST/tests/evaldata.json" ]; then
    echo "corpus already in $DEST"
    exit 0
fi
rm -rf "$DEST"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$TAG" --filter=blob:none --sparse https://github.com/adbar/trafilatura.git "$DEST"
git -C "$DEST" sparse-checkout set --no-cone /tests/eval/ /tests/cache/ /tests/evaldata.json /tests/eval_common.py
echo "corpus in $DEST: $(ls "$DEST/tests/eval" | wc -l) eval pages, $(ls "$DEST/tests/cache" | wc -l) cache pages"
