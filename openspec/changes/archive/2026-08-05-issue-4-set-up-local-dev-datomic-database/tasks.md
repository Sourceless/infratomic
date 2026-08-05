## 1. Dev shell (JDK + Clojure CLI)

- [x] 1.1 Add a JDK 17+ package (e.g. `pkgs.jdk17`) and `pkgs.clojure` to the `packages` list of `devShells.x86_64-linux.default` in `flake.nix`
- [x] 1.2 Verify `nix develop` puts `java -version` (17+) and `clj --version` on `PATH` alongside `git`/`nix`
- [x] 1.3 Verify `nix flake check` still succeeds

## 2. Project scaffold

- [x] 2.1 Create root `deps.edn` with `com.datomic/local` `{:mvn/version "1.0.301"}` as a dependency
- [x] 2.2 Add a `:test` alias to `deps.edn` using `com.cognitect/test-runner` (git dep) as `extra-deps`, with `:main-opts ["-m" "cognitect.test-runner"]`
- [x] 2.3 Add `.datomic/` (or the chosen storage-dir path) and Clojure build artifacts (`.cpcache/`, `target/`) to `.gitignore`

## 3. Connection helper

- [x] 3.1 Create a namespace (e.g. `src/infratomic/datomic.clj`) exposing a function that builds the `d/client` config with a repo-local `:storage-dir` (e.g. `.datomic/storage`) and a fixed system name (e.g. `"dev"`)
- [x] 3.2 In that namespace, add a function that creates the database (`d/create-database`, idempotent) and returns a connection (`d/connect`) for a given db-name
- [x] 3.3 Document the storage-dir path and system name in a code comment or docstring at the point they're defined

## 4. Verification test

- [x] 4.1 Create a `clojure.test` namespace (e.g. `test/infratomic/datomic_test.clj`) that uses the connection helper from task 3 to connect to a test database
- [x] 4.2 In the test, transact an inline fixture schema with a single `:sample/name` string attribute
- [x] 4.3 Transact a sample entity using `:sample/name`, then query it back via `d/q`
- [x] 4.4 Assert the queried result matches the transacted value
- [x] 4.5 Run `clj -M:test` from a clean checkout (or a cleared storage dir) and confirm it passes with no manual setup beyond that command

## 5. Documentation

- [x] 5.1 Add a "Local Datomic database" section to `README.md`, following the structure of the existing "Local AWS test app" section (bring-up + verify)
- [x] 5.2 Document the storage-dir path, system name, and the `clj -M:test` verification command in that section
- [x] 5.3 Note in the README (or as a code comment) that the fixture schema is minimal/throwaway test plumbing, not app schema design
