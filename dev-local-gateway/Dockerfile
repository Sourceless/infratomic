# Build context is this directory (dev-local-gateway/). Not published to
# GHCR (only the State Backend image is, per the container-image spec) -
# this image exists for local `docker compose` gateway-mode dev/testing
# (see ../docker-compose.yml's `gateway` service) and for the "run the
# published State Backend image alongside a Dev-Local Gateway container"
# verification flow in docs/user-guide.md. Depends only on
# com.datomic/local - no com.datomic/client-pro, no my.datomic.com
# credential, anywhere in this build or runtime.
FROM clojure:temurin-17-tools-deps-jammy AS build
WORKDIR /build

COPY deps.edn build.clj ./
RUN clojure -P -X:build

COPY src src
RUN clojure -T:build uber

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/dev-local-gateway.jar /app/dev-local-gateway.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/dev-local-gateway.jar"]
