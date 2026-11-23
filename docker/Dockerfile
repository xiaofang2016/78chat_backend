FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# The deploy mounts im-platform.jar at /app/app.jar. The image build does not copy the jar.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
