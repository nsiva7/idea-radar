FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle buildFatJar --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/idea-radar.jar app.jar
EXPOSE 8080
CMD ["java", "-Xmx384m", "-jar", "app.jar"]
