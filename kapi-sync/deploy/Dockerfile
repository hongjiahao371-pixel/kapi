# Prebuilt Go sync binary plus the exact Android core import reader.
# Build locally with tools/build-nas-import.py and cross-compile the Go binary.
# The fixed official Java runtime is fetched once, then reused from the NAS cache.
FROM eclipse-temurin:17-jre-alpine@sha256:3c472129dc75a8d1d7a3f2df5b2093a8077e4493deff046754d4764d0371de63

COPY kapi-sync /kapi-sync
COPY ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY guizhang-import.jar /opt/guizhang/guizhang-import.jar
COPY import-licenses /opt/guizhang/licenses

VOLUME ["/data"]
EXPOSE 8787

ENV KAPI_DATA=/data \
    KAPI_ADDR=0.0.0.0:8787 \
    KAPI_IMPORT_JAR=/opt/guizhang/guizhang-import.jar

ENTRYPOINT ["/kapi-sync"]
