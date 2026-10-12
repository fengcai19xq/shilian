FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY deploy/test/maven-settings.xml /root/.m2/settings.xml
COPY services-java/ingest/pom.xml ./
RUN mvn -q -B dependency:go-offline || true
COPY services-java/ingest/src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8030
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
