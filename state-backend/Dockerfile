# Build context is this directory (state-backend/) - see
# openspec/changes/issue-35-ship-infratomic-deployable-container-real-datomic/
# design.md's "Dockerfile and GHCR publish" decision. Depends only on
# com.datomic/local (via deps.edn) - no com.datomic/client-pro, no
# my.datomic.com credential, anywhere in this build.
FROM clojure:temurin-17-tools-deps-jammy AS build
WORKDIR /build

# Resolve deps.edn (including the :build alias's tools.build) in its own
# layer, cached separately from application source changes.
COPY deps.edn build.clj ./
RUN clojure -P -X:build

COPY src src
RUN clojure -T:build uber

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/state-backend.jar /app/state-backend.jar

# The published image's default Datomic connection mode is `gateway` (see
# the container-image spec's "The container's default Datomic mode is
# gateway mode" requirement) - connecting to a separately-run Dev-Local
# Gateway process over the network, not an in-process embedded db.
# INFRATOMIC_GATEWAY_HOST/INFRATOMIC_GATEWAY_PORT must be set at `docker
# run` time to point at that Dev-Local Gateway (see docs/user-guide.md).
ENV INFRATOMIC_DATOMIC_MODE=gateway

EXPOSE 8080

# Default command starts the HTTP server; `docker run <image> bootstrap`
# overrides CMD to run the bootstrap entrypoint instead (installs schema
# into a fresh database and exits, without starting the server) - see
# main.clj's `-main` arg-dispatch.
ENTRYPOINT ["java", "-jar", "/app/state-backend.jar"]
CMD []
