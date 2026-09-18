FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests compile dependency:copy-dependencies -DoutputDirectory=target/bootstrap-libs

FROM eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb

RUN addgroup -S -g 10001 oficina && adduser -S -D -u 10001 -G oficina oficina
WORKDIR /opt/oficina
COPY --from=builder /build/target/classes ./classes
COPY --from=builder /build/target/bootstrap-libs ./libs
COPY docker/bootstrap/entrypoint.sh ./entrypoint.sh
RUN chmod 0555 ./entrypoint.sh && chown -R oficina:oficina /opt/oficina
USER oficina

# The output directory is an emptyDir supplied by the Job. No credentials are
# embedded in this image or passed as command-line values.
ENTRYPOINT ["/opt/oficina/entrypoint.sh"]
