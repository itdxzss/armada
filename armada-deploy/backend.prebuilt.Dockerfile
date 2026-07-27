FROM eclipse-temurin:17-jre

WORKDIR /app

ENV TZ=UTC

RUN apt-get update \
    && apt-get install -y --no-install-recommends less vim-tiny \
    && rm -rf /var/lib/apt/lists/*

COPY armada-api/target/armada-api-deploy.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
