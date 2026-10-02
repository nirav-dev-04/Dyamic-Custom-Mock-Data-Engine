# Stage 1: Build stage with Maven and JDK 21
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/mock-data-engine-0.0.1-SNAPSHOT.jar app.jar
COPY frontend /app/frontend

EXPOSE 5001
ENTRYPOINT ["java", "-jar", "app.jar"]
