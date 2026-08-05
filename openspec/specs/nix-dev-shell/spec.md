# nix-dev-shell Specification

## Purpose

Provides a reproducible `nix develop` shell, defined by a repo-root Nix flake, so contributors get a baseline toolchain (`git`, `nix`) without manual setup.

## Requirements

### Requirement: Flake exists and is valid
The repo SHALL contain a `flake.nix` at its root that is a valid Nix flake.

#### Scenario: Flake show succeeds
- **WHEN** a contributor runs `nix flake show` from the repo root
- **THEN** the command succeeds with no errors and lists the flake's outputs

#### Scenario: Flake check succeeds
- **WHEN** a contributor runs `nix flake check` from the repo root
- **THEN** the command succeeds with no errors

### Requirement: Dev shell provides baseline toolchain
The flake SHALL expose a `devShells.x86_64-linux.default` output that puts `git`, `nix`, a JDK 17+ runtime, and the Clojure CLI on `PATH`.

#### Scenario: Entering the dev shell
- **WHEN** a contributor runs `nix develop` from the repo root on an `x86_64-linux` system
- **THEN** they enter a shell where `git --version`, `nix --version`, `java -version` (reporting major version 17 or higher), and `clj --version` all succeed

### Requirement: Flake targets x86_64-linux only
The flake's outputs SHALL be defined only for the `x86_64-linux` system; no other system SHALL be targeted.

#### Scenario: Only x86_64-linux is present in outputs
- **WHEN** a contributor inspects the flake's `devShells` output
- **THEN** the only system key present is `x86_64-linux`

### Requirement: Dev shell package list is limited to baseline plus Clojure toolchain
The dev shell SHALL contain only `git`, `nix`, a JDK 17+ package, and the Clojure CLI, with no other language-specific toolchains, and the repo SHALL NOT gain a CI workflow as part of this capability.

#### Scenario: Dev shell package list is limited
- **WHEN** a contributor inspects the packages passed to the dev shell derivation
- **THEN** only `git`, `nix`, a JDK 17+ package, and the Clojure CLI package are present, with no other language-specific packages

#### Scenario: No CI workflow is added
- **WHEN** a contributor inspects the repository for CI configuration
- **THEN** no `.github/workflows` entry running `nix flake check` (or similar) exists as part of this change
