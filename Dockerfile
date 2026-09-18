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

# The agent is fetched as a separate immutable artifact and checksum-verified before it enters
# the runtime image. Credentials are supplied only by the reviewed runtime Secret.
FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS newrelic-agent

ARG NEW_RELIC_JAVA_AGENT_VERSION=9.4.0
ARG NEW_RELIC_JAVA_AGENT_SHA256=1f8f42d25e6565a1a7088543deeeefacdd2b028761ac94743dd1715cf7ddf5f4

RUN mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
      -Dartifact=com.newrelic.agent.java:newrelic-agent:${NEW_RELIC_JAVA_AGENT_VERSION}:jar \
      -DoutputDirectory=/opt/newrelic && \
    mv /opt/newrelic/newrelic-agent-${NEW_RELIC_JAVA_AGENT_VERSION}.jar /opt/newrelic/newrelic.jar && \
    echo "${NEW_RELIC_JAVA_AGENT_SHA256}  /opt/newrelic/newrelic.jar" | sha256sum -c -

# ========================
# STAGE 2: Runtime
# ========================
FROM eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb AS runtime

# Criar usuário não-root por segurança
RUN addgroup -S oficina && adduser -S oficina -G oficina

WORKDIR /app

# Copiar o JAR gerado
COPY --from=builder /app/target/*.jar app.jar
COPY --from=newrelic-agent --chown=oficina:oficina /opt/newrelic/newrelic.jar /app/newrelic/newrelic.jar

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
# The JVM automatically consumes JAVA_TOOL_OPTIONS, so this remains active even when callers
# add memory flags through JAVA_OPTS. The image intentionally contains no New Relic credential.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/newrelic/newrelic.jar"
ENV SPRING_PROFILES_ACTIVE=prod
# The image never embeds an agent or ingest key. If the reviewed New Relic agent is supplied by the runtime,
# its own log forwarding stays disabled because the Kubernetes forwarder is the single application-log sender.
ENV NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=false
ENV NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true
ENV NEW_RELIC_SPAN_EVENTS_MAX_SAMPLES_STORED=500

# One runtime value labels both JSON logs and Java-agent Transaction events. It is finite and
# nonsecret; Kubernetes supplies staging or production through DEPLOYMENT_ENVIRONMENT.
ENTRYPOINT ["sh", "-c", "export OFICINA_ENVIRONMENT=\"${DEPLOYMENT_ENVIRONMENT:?DEPLOYMENT_ENVIRONMENT is required}\"; export NEW_RELIC_LABELS=\"environment:${DEPLOYMENT_ENVIRONMENT}\"; exec java $JAVA_OPTS -jar app.jar"]
