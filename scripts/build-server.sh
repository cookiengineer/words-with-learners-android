#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GAME_DIR="${GAME_DIR:-${ROOT_DIR}/../words-with-learners-game}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/app/build/generated/wwl-jniLibs}"

if [[ ! -d "${GAME_DIR}" ]]; then
    echo "game directory not found: ${GAME_DIR}" >&2
    exit 1
fi

if [[ ! -f "${GAME_DIR}/cmd/server/main.go" ]]; then
    echo "game server entrypoint not found: ${GAME_DIR}/cmd/server/main.go" >&2
    exit 1
fi

build_abi() {
    local abi="$1"
    local goarch="$2"
    local goarm="${3:-}"
    local output="${OUT_DIR}/${abi}/libwwlserver.so"

    mkdir -p "$(dirname "${output}")"

    echo "building ${abi} (GOARCH=${goarch}${goarm:+, GOARM=${goarm}})"
    local build_env=(GOOS=android GOARCH="${goarch}" GOFLAGS=-mod=readonly)
    if [[ -n "${goarm}" ]]; then
        build_env+=(GOARM="${goarm}")
    fi
    if [[ "${goarch}" == "amd64" ]]; then
        if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
            echo "ANDROID_NDK_HOME is required for android/amd64 PIE linking" >&2
            exit 1
        fi
        build_env+=(
            CGO_ENABLED=1
            CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"
        )
    else
        build_env+=(CGO_ENABLED=0)
    fi
    (
        cd "${GAME_DIR}"
        env "${build_env[@]}" go build \
            -buildvcs=false \
            -trimpath \
            -tags netgo \
            -ldflags='-s -w -buildid=' \
            -o "${output}" \
            ./cmd/server
    )

    chmod 0755 "${output}"
    file "${output}"
}

build_abi arm64-v8a arm64
build_abi x86_64 amd64

echo "server binaries written to ${OUT_DIR}"
