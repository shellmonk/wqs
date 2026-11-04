# Built from the jar of `./gradlew bootJar` (see README). Layers: dependencies change rarely,
# the application on every build.
FROM eclipse-temurin:21-jre AS layers
WORKDIR /layers
COPY build/libs/wqs.jar wqs.jar
RUN java -Djarmode=tools -jar wqs.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 wqs
USER wqs
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
