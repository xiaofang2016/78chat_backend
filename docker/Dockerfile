FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 部署时把 im-platform.jar 挂到 /app/app.jar，构建镜像时不复制 jar。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
