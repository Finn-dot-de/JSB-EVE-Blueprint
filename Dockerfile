# --- Stage 1: Build ---
# Wir nutzen direkt ein Maven-Image, da der lokale Wrapper fehlt
FROM maven:3.9.16-eclipse-temurin-26-alpine AS builder

WORKDIR /build

# Kopiere die pom.xml für das Caching der Dependencies
COPY pom.xml .

# Lade Abhängigkeiten herunter (wird gecached, solange sich die pom.xml nicht ändert)
RUN mvn dependency:go-offline

# Kopiere den restlichen Code und baue das Projekt
COPY src src
RUN mvn clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:26-jdk-alpine
WORKDIR /app

# Kopiere nur das fertige JAR aus der Build-Stage
COPY --from=builder /build/target/*.jar app.jar

# Standardport für Spring Boot
EXPOSE 8080

# Starte die Anwendung
ENTRYPOINT ["java", "-jar", "app.jar"]