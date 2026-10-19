# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM gradle:9.8.0-jdk17 AS import-build
WORKDIR /work
COPY import-worker/ ./
RUN gradle --no-daemon -Pkotlin.compiler.execution.strategy=in-process jar

FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS go-build
WORKDIR /src
RUN apk add --no-cache ca-certificates
COPY go.mod ./
COPY cmd/ ./cmd/
COPY internal/ ./internal/
ARG VERSION=0.5.5
ARG TARGETOS=linux
ARG TARGETARCH=amd64
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w -X kapi/sync/internal/store.Version=${VERSION}" -o /out/kapi-sync ./cmd/kapi-sync

FROM eclipse-temurin:17-jre-alpine@sha256:3c472129dc75a8d1d7a3f2df5b2093a8077e4493deff046754d4764d0371de63
ARG VERSION=0.5.5
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="归账 NAS" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/hongjiahao371-pixel/kapi" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"
COPY --from=go-build /out/kapi-sync /kapi-sync
COPY --from=go-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=import-build /work/build/libs/guizhang-import.jar /opt/guizhang/guizhang-import.jar
COPY import-worker/licenses/ /opt/guizhang/licenses/
COPY LICENSE NOTICE /opt/guizhang/project-license/
VOLUME ["/data"]
EXPOSE 8787 8789
ENV KAPI_DATA=/data KAPI_ADDR=0.0.0.0:8787 KAPI_IMPORT_JAR=/opt/guizhang/guizhang-import.jar
ENTRYPOINT ["/kapi-sync"]
