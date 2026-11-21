FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

ARG JAR
COPY ${JAR} /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
