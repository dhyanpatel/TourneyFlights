# Build stage
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.7_3.3.7 AS build
WORKDIR /app

# Copy build files first for better caching
COPY project/ project/
COPY build.sbt .

# Fetch dependencies (cached layer)
RUN sbt update

# Copy source and build fat JAR
COPY src/ src/
RUN sbt assembly

# Runtime stage - slim JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the fat JAR from build stage
COPY --from=build /app/target/scala-3.3.7/tourneyFlights.jar ./app.jar

# Expose port (http4s default)
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]
