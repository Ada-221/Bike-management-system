FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home telemetry
COPY --from=build /build/target/*.jar app.jar
USER telemetry
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
