# Stage 1: build the jar with Maven (no Maven/JDK needed on the host).
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Stage 2: small runtime image with only a JRE.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home fleet
COPY --from=build /build/target/*.jar app.jar
USER fleet
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
