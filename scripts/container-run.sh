#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GAME_DIR="${GAME_DIR:-${ROOT_DIR}/../words-with-learners-game}"
IMAGE="${IMAGE:-localhost/words-with-learners-android-builder:latest}"

if [[ ! -d "${GAME_DIR}" ]]; then
    echo "game directory not found: ${GAME_DIR}" >&2
    exit 1
fi

exec podman run --rm \
    -v "${ROOT_DIR}":/workspace:Z \
    -v "${GAME_DIR}":/game:ro,Z \
    -v wwl-android-gradle:/root/.gradle \
    -v wwl-android-home:/root/.android \
    -v wwl-android-go-build:/root/.cache/go-build \
    -v wwl-android-go-mod:/root/go/pkg/mod \
    -e GAME_DIR=/game \
    -w /workspace \
    "${IMAGE}" \
    "$@"
