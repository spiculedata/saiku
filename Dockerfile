ARG JAVA_VERSION=21

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -pl saiku-launcher -am package \
 && cp saiku-launcher/target/saiku-*.jar /src/saiku.jar

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/saiku.jar /app/saiku.jar
ENV SAIKU_HOME=/app/saiku-home
VOLUME ["/app/saiku-home"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/saiku.jar"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080", "--home", "/app/saiku-home"]
