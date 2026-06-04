FROM golang:1.26-bookworm AS go

FROM registry.gitlab.com/fdroid/docker-executable-fdroidserver:master

USER root

ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/usr/local/go/bin:${PATH}"

COPY --from=go /usr/local/go /usr/local/go

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        autoconf \
        automake \
        bison \
        build-essential \
        ca-certificates \
        cmake \
        curl \
        file \
        flex \
        fuse-overlayfs \
        fuse3 \
        gawk \
        gettext \
        git \
        jq \
        libtool \
        lzip \
        meson \
        ninja-build \
        patch \
        perl \
        pkg-config \
        python3 \
        python3-pip \
        ruby \
        rsync \
        sudo \
        texinfo \
        unzip \
        wget \
        xz-utils \
        zstd \
    && ln -sf /usr/local/go/bin/go /usr/local/bin/go \
    && ln -sf /usr/local/go/bin/gofmt /usr/local/bin/gofmt \
    && rm -rf /var/lib/apt/lists/*
