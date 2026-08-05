## REMOVED Requirements

### Requirement: No language-specific packages or CI in scope
**Reason**: Issue #7 introduces the State Backend, a Clojure service, which needs a Clojure toolchain to build and run inside the dev shell. The blanket "no language-specific packages" scoping no longer holds; it is superseded by a narrower requirement that scopes the dev shell to exactly the toolchain the State Backend needs, while still excluding CI.
**Migration**: See the new "Dev shell provides Clojure toolchain, no other language-specific packages" requirement below.

## ADDED Requirements

### Requirement: Dev shell provides Clojure toolchain, no other language-specific packages
The dev shell SHALL put `clojure` (the Clojure CLI) and a `jdk` on `PATH`, in addition to `git` and `nix`, so the State Backend service can be built and run without further setup. No other language-specific toolchains SHALL be added, and the repo SHALL NOT gain a CI workflow as part of this capability.

#### Scenario: Dev shell package list is scoped to git, nix, clojure, and jdk
- **WHEN** a contributor inspects the packages passed to the dev shell derivation
- **THEN** only `git`, `nix`, `clojure`, and `jdk` are present, with no other language-specific packages

#### Scenario: Entering the dev shell provides a working Clojure toolchain
- **WHEN** a contributor runs `nix develop` from the repo root on an `x86_64-linux` system
- **THEN** they enter a shell where `clojure --version` (or equivalent Clojure CLI invocation) succeeds, backed by the provided `jdk`

#### Scenario: No CI workflow is added
- **WHEN** a contributor inspects the repository for CI configuration
- **THEN** no `.github/workflows` entry running `nix flake check` (or similar) exists as part of this change
