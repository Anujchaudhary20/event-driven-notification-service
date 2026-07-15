# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy full project for Maven multi-module build (parent pom requires all modules)
COPY pom.xml .
COPY shared/ shared/
COPY api-service/ api-service/
COPY worker-service/ worker-service/

# Build api-service and its dependencies (shared)
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/* && \
    mvn -B package -pl api-service -am -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/api-service/target/api-service-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
