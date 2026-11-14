SHELL := /bin/bash

IMAGE ?= localhost/words-with-learners-android-builder:latest
GAME_DIR ?= $(abspath $(CURDIR)/../words-with-learners-game)
CONTAINER_RUN := IMAGE=$(IMAGE) GAME_DIR=$(GAME_DIR) ./scripts/container-run.sh

.PHONY: image shell server debug apk test lint check clean install

image:
	podman build -t $(IMAGE) -f Containerfile .

shell:
	$(CONTAINER_RUN) bash

server:
	$(CONTAINER_RUN) bash scripts/build-server.sh

debug apk:
	$(CONTAINER_RUN) gradle assembleDebug

test:
	$(CONTAINER_RUN) bash -c 'gradle testDebugUnitTest --no-daemon && cd /game && GOFLAGS=-mod=readonly go test ./...'

lint:
	$(CONTAINER_RUN) gradle lintDebug

check: test lint

clean:
	rm -rf app/build build .gradle

install: apk
	adb install -r app/build/outputs/apk/debug/app-debug.apk
