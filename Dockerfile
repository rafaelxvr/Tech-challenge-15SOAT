# ========================
# STAGE 1: Build
# ========================
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

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
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/actuator/health || exit 1

# Variáveis de ambiente padrão
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
