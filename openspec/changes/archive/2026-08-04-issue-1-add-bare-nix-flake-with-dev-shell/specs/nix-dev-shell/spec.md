## Purpose

Provides a reproducible `nix develop` shell, defined by a repo-root Nix flake, so contributors get a baseline toolchain (`git`, `nix`) without manual setup.

## ADDED Requirements

### Requirement: Flake exists and is valid
The repo SHALL contain a `flake.nix` at its root that is a valid Nix flake.

#### Scenario: Flake show succeeds
- **WHEN** a contributor runs `nix flake show` from the repo root
- **THEN** the command succeeds with no errors and lists the flake's outputs

#### Scenario: Flake check succeeds
- **WHEN** a contributor runs `nix flake check` from the repo root
- **THEN** the command succeeds with no errors

### Requirement: Dev shell provides baseline toolchain
The flake SHALL expose a `devShells.x86_64-linux.default` output that puts `git` and `nix` on `PATH`.

#### Scenario: Entering the dev shell
- **WHEN** a contributor runs `nix develop` from the repo root on an `x86_64-linux` system
- **THEN** they enter a shell where `git --version` and `nix --version` both succeed

### Requirement: Flake targets x86_64-linux only
The flake's outputs SHALL be defined only for the `x86_64-linux` system; no other system SHALL be targeted.

#### Scenario: Only x86_64-linux is present in outputs
- **WHEN** a contributor inspects the flake's `devShells` output
- **THEN** the only system key present is `x86_64-linux`

### Requirement: No language-specific packages or CI in scope
The dev shell SHALL contain only `git` and `nix`, with no language-specific toolchains, and the repo SHALL NOT gain a CI workflow as part of this capability.

#### Scenario: Dev shell package list is minimal
- **WHEN** a contributor inspects the packages passed to the dev shell derivation
- **THEN** only `git` and `nix` are present, with no language-specific packages

#### Scenario: No CI workflow is added
- **WHEN** a contributor inspects the repository for CI configuration
- **THEN** no `.github/workflows` entry running `nix flake check` (or similar) exists as part of this change
