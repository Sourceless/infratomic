# container-image Specification

## Purpose

A runnable, published container image of the State Backend, so it can be tried out end-to-end outside a dev checkout instead of only exercised via the test suite.

## Requirements

### Requirement: A Dockerfile builds an image running the State Backend
The repository SHALL contain a Dockerfile that builds an image which, when run, starts the State Backend service.

#### Scenario: Building the image
- **WHEN** the Dockerfile is built
- **THEN** the build succeeds and produces an image whose default entrypoint starts the State Backend's HTTP server

#### Scenario: The image supports the bootstrap entrypoint
- **WHEN** the image is run with the `bootstrap` argument instead of the default
- **THEN** it runs the State Backend's `bootstrap` command (installing schema into a fresh database and exiting) instead of starting the HTTP server

### Requirement: The image requires no licensed Datomic dependency or credentials to build
Building the image SHALL NOT require `com.datomic/client-pro` or any my.datomic.com credential.

#### Scenario: Building the image with no Datomic Pro credentials present
- **WHEN** the Dockerfile is built in an environment with no my.datomic.com account or download key configured anywhere
- **THEN** the build succeeds

### Requirement: The built image is published to GHCR
A CI workflow SHALL build the image and publish it to GHCR, using only credentials available to the workflow by default (no external, manually-provisioned secret).

#### Scenario: A push triggers a published image
- **WHEN** the CI workflow runs against a build of the image
- **THEN** the resulting image is pushed to a GHCR repository under this project, addressable by a tag

### Requirement: The container's default Datomic mode is gateway mode
The image's default runtime configuration SHALL run the State Backend in `gateway` mode (`INFRATOMIC_DATOMIC_MODE=gateway`), connecting to a Dev-Local Gateway over the network, rather than embedded mode.

#### Scenario: Running the image with a Dev-Local Gateway configured
- **WHEN** the published image is run with `INFRATOMIC_DATOMIC_MODE=gateway` and a Dev-Local Gateway host/port configured, alongside a running Dev-Local Gateway and LocalStack
- **THEN** the State Backend starts and serves requests against the Dev-Local Gateway's db
