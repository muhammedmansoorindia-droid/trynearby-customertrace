# Backend image. Multi-stage so the runtime layer carries no build tooling.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so source edits do not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root.
RUN useradd --system --create-home --shell /usr/sbin/nologin ct
USER ct

COPY --from=build /build/target/backend-*.jar /app/app.jar

EXPOSE 8080

# Bound to 0.0.0.0 so the published port is reachable from the host.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]