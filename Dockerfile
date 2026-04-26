# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn -B package -DskipTests

# ---- Runtime stage ----
# AC#3: Slim JRE runtime — not JDK
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Install curl for healthcheck (used in docker-compose.yml)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# SEC-001: Create a non-root user for the runtime stage
RUN addgroup --system app && adduser --system --ingroup app app

# Copy the repackaged Spring Boot JAR (explicit name — do NOT use glob;
# spring-boot-maven-plugin produces both .jar and .jar.original)
COPY --from=build --chown=app:app /app/target/Proxy-server-1.0-SNAPSHOT.jar app.jar

# SSH config and keys must be mounted at runtime: -v ~/.ssh:/home/app/.ssh:ro
# Never bake SSH credentials into the image.
EXPOSE 8080

USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
