# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
FROM golang:1.26-alpine AS build
WORKDIR /src

# No third-party dependencies on purpose, so there is nothing to download and
# no go.sum to drift.
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal

ARG VERSION=0.1.0
# CGO off so the result is a static binary that runs on scratch.
# -trimpath keeps build paths out of the binary; -s -w strip debug info, which
# matters because the image is pushed to people's NAS over home upload.
ENV LDFLAGS="-s -w -X kapi/sync/internal/store.Version=${VERSION}"

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="$LDFLAGS" -o /out/kapi-sync ./cmd/kapi-sync
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="$LDFLAGS" -o /out-arm64/kapi-sync ./cmd/kapi-sync

# ---- runtime ----------------------------------------------------------------
FROM scratch AS runtime
COPY --from=build /out/kapi-sync /kapi-sync
VOLUME ["/data"]
EXPOSE 8787
ENV KAPI_DATA=/data KAPI_ADDR=0.0.0.0:8787

# Runs as root deliberately, despite the usual advice.
#
# This image is `scratch`: no shell, no package manager, no coreutils, one
# static binary. The blast radius of root here is exactly "whatever this one
# program does with /data".
#
# The alternative — USER 65534 — requires the operator to chown the host
# directory before first start, because the volume mount is created root-owned
# by Docker. That turns `docker run` into a two-step-plus-a-prerequisite
# install, and "zero-config startup on the NAS" is a hard requirement of this
# product, not a nicety. One requirement wins.
#
# To harden it instead: create the dir as the user you want and pass
#   --user 65534:65534
# after `chown -R 65534:65534 /path/to/data`.
ENTRYPOINT ["/kapi-sync"]
