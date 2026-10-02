# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers) and run in CI / locally via `./mvnw test`, not inside the image build.
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER app
# Sized to fit a 512 MB container with headroom: heap ~45%, with metaspace, code cache, direct buffers and thread
# stacks explicitly capped (left alone, the JVM reserves well past the container limit and gets OOM-killed).
# C1-only JIT keeps compiler threads from starving request work on fractional-CPU instances.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
