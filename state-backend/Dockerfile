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

# Pinned Terraform CLI release (issue #33), downloaded in its own stage so
# it's cached independently of the application build above - matches or
# exceeds ../terraform/provider.tf's `required_version = ">= 1.5"`. The
# published image (and this repo's CI, ubuntu-latest/amd64) only ever runs
# linux/amd64, so a single fixed platform binary is fetched rather than
# building a multi-arch image.
FROM debian:bookworm-slim AS terraform
ARG TERRAFORM_VERSION=1.9.8
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && curl -fsSL -o /tmp/terraform.zip \
       "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip" \
    && unzip /tmp/terraform.zip -d /usr/local/bin \
    && chmod +x /usr/local/bin/terraform \
    && rm -rf /tmp/terraform.zip /var/lib/apt/lists/*

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/state-backend.jar /app/state-backend.jar
COPY --from=terraform /usr/local/bin/terraform /usr/local/bin/terraform

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
