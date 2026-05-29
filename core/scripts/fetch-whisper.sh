#!/usr/bin/env bash
# Fetch a pinned whisper.cpp source tree into core/third_party/whisper.cpp.
# Idempotent: re-running skips if the pinned commit is already checked out.

set -euo pipefail
cd "$(dirname "$0")/.."

# Pinned commit / tag. Update intentionally + re-run.
WHISPER_TAG="${WHISPER_TAG:-v1.7.5}"
DEST="third_party/whisper.cpp"

mkdir -p third_party

if [[ ! -d "$DEST/.git" ]]; then
    echo "==> Cloning whisper.cpp $WHISPER_TAG"
    git clone --depth 1 --branch "$WHISPER_TAG" \
        https://github.com/ggerganov/whisper.cpp "$DEST"
else
    echo "==> Updating whisper.cpp to $WHISPER_TAG"
    git -C "$DEST" fetch --depth 1 origin "refs/tags/$WHISPER_TAG:refs/tags/$WHISPER_TAG"
    git -C "$DEST" checkout "$WHISPER_TAG"
fi

# We only need: ggml + whisper.h + whisper.cpp + the small set of source files.
# Keep the full tree to make the Xcode + Android CMake configs simpler — gitignored.

echo "✓ whisper.cpp at $(git -C "$DEST" describe --tags)"
echo "  Path: $(pwd)/$DEST"
