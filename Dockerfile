# Build is performed by the release.yml / ci.yml workflows (which have
# GH Packages auth via the GH_PACKAGES_TOKEN repo secret). The Docker
# image just packages the pre-built fat JAR — no Maven inside the
# container, no settings.xml plumbing, no transitive resolution at
# image-build time.
#
# release.yml's docker job downloads the saiku-jar artifact into
# ./build-context/saiku.jar before invoking docker buildx, so the JAR
# is already in the build context when this Dockerfile runs.
FROM gcr.io/distroless/java21-debian12:nonroot
ARG JAR_PATH=build-context/saiku.jar
WORKDIR /app
COPY ${JAR_PATH} /app/saiku.jar
ENV SAIKU_HOME=/app/saiku-home
VOLUME ["/app/saiku-home"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/saiku.jar"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080", "--home", "/app/saiku-home"]
