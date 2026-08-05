## MODIFIED Requirements

### Requirement: Dev shell provides baseline toolchain
The flake SHALL expose a `devShells.x86_64-linux.default` output that puts `git`, `nix`, a JDK 17+ runtime, and the Clojure CLI on `PATH`.

#### Scenario: Entering the dev shell
- **WHEN** a contributor runs `nix develop` from the repo root on an `x86_64-linux` system
- **THEN** they enter a shell where `git --version`, `nix --version`, `java -version` (reporting major version 17 or higher), and `clj --version` all succeed

## REMOVED Requirements

### Requirement: No language-specific packages or CI in scope
**Reason**: Superseded by issue #4, which requires a JVM + Clojure CLI on `PATH` in the default dev shell so the local Datomic database's `clj -M:test` verification works with no manual setup beyond clone + run. A blanket "no language-specific toolchains" rule is no longer accurate.
**Migration**: See the new "Dev shell package list is limited to baseline plus Clojure toolchain" requirement below, which replaces this one with a narrower, explicit allow-list instead of a blanket prohibition.

## ADDED Requirements

### Requirement: Dev shell package list is limited to baseline plus Clojure toolchain
The dev shell SHALL contain only `git`, `nix`, a JDK 17+ package, and the Clojure CLI, with no other language-specific toolchains, and the repo SHALL NOT gain a CI workflow as part of this capability.

#### Scenario: Dev shell package list is limited
- **WHEN** a contributor inspects the packages passed to the dev shell derivation
- **THEN** only `git`, `nix`, a JDK 17+ package, and the Clojure CLI package are present, with no other language-specific packages

#### Scenario: No CI workflow is added
- **WHEN** a contributor inspects the repository for CI configuration
- **THEN** no `.github/workflows` entry running `nix flake check` (or similar) exists as part of this change
