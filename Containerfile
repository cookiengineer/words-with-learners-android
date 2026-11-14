FROM docker.io/library/debian:bookworm-slim

ARG DEBIAN_FRONTEND=noninteractive
ARG ANDROID_CMDLINE_TOOLS=commandlinetools-linux-16111833_latest.zip
ARG ANDROID_CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
ARG GO_VERSION=1.27.4
ARG GO_SHA256=7226736c2339719cda28fbf3a54d71aa63bcefe376e98cef00034268dab24840
ARG GRADLE_VERSION=8.9
ARG GRADLE_SHA256=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
ARG NDK_VERSION=29.0.14206865

ENV LANG=C.UTF-8
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION}
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV GRADLE_USER_HOME=/root/.gradle

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        file \
        git \
        make \
        openjdk-17-jdk-headless \
        unzip \
        zip; \
    rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    mkdir -p /opt/android-sdk/cmdline-tools; \
    curl -fsSL -o /tmp/android-cmdline-tools.zip "https://dl.google.com/android/repository/${ANDROID_CMDLINE_TOOLS}"; \
    echo "${ANDROID_CMDLINE_TOOLS_SHA1}  /tmp/android-cmdline-tools.zip" | sha1sum -c -; \
    unzip -q /tmp/android-cmdline-tools.zip -d /tmp/android-cmdline-tools; \
    mv /tmp/android-cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest; \
    rm -rf /tmp/android-cmdline-tools /tmp/android-cmdline-tools.zip

ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/gradle-${GRADLE_VERSION}/bin:/usr/local/go/bin:${PATH}

RUN set -eux; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        "ndk;${NDK_VERSION}"

RUN set -eux; \
    curl -fsSL -o /tmp/go.tar.gz "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz"; \
    echo "${GO_SHA256}  /tmp/go.tar.gz" | sha256sum -c -; \
    tar -C /usr/local -xzf /tmp/go.tar.gz; \
    rm -f /tmp/go.tar.gz; \
    go version

RUN set -eux; \
    curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c -; \
    mkdir -p /opt/gradle; \
    unzip -q /tmp/gradle.zip -d /opt/gradle; \
    rm -f /tmp/gradle.zip; \
    gradle --version

WORKDIR /workspace

CMD ["bash"]
