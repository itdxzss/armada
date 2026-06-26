FROM eclipse-temurin:17-jre

WORKDIR /app

ENV TZ=UTC

COPY armada-api/target/armada-api-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
