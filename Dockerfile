# ========================
# STAGE 1: Build
# ========================
FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS builder

WORKDIR /app

# Copiar apenas o pom.xml primeiro para aproveitar cache de dependências
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar o código-fonte e compilar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ========================
# STAGE 2: Runtime
# ========================
FROM eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb AS runtime

# Criar usuário não-root por segurança
RUN addgroup -S oficina && adduser -S oficina -G oficina

WORKDIR /app

# Copiar o JAR gerado
COPY --from=builder /app/target/*.jar app.jar

# Ajustar permissões
RUN chown oficina:oficina app.jar

USER oficina

# Porta da aplicação
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=2s --start-period=120s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/actuator/health/liveness || exit 1

# Variáveis de ambiente padrão
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE=prod
# The image never embeds an agent or ingest key. If the reviewed New Relic agent is supplied by the runtime,
# its own log forwarding stays disabled because the Kubernetes forwarder is the single application-log sender.
ENV NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=false
ENV NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true
ENV NEW_RELIC_SPAN_EVENTS_MAX_SAMPLES_STORED=500

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
