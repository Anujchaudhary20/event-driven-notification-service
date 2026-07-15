FROM eclipse-temurin:17-jdk AS build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY api-service/pom.xml api-service/pom.xml
COPY worker-service/pom.xml worker-service/pom.xml

COPY shared/src shared/src
COPY worker-service/src worker-service/src

RUN mvn -pl shared,worker-service -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /workspace/worker-service/target/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
