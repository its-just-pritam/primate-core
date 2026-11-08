FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home platito
COPY --from=build /workspace/build/libs/platito-backend-*.jar app.jar
USER platito
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
