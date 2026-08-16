## 1. Datomic facade namespace

- [x] 1.1 Create `infratomic.state-backend.datomic`, re-exporting `q`, `pull`, `transact`, `with`, `with-db`, `db`, `history`, `as-of`, `create-database`, `connect`, `client` as multimethods/protocol functions dispatching on the concrete type of the `client`/`conn`/`db` value
- [x] 1.2 Implement the `EmbeddedClient` type/dispatch path delegating straight to `datomic.client.api` (zero behavior change from today)
- [x] 1.3 Update `db.clj`, `query.clj`, `policy.clj`, `sync.clj` to `:require` the facade namespace instead of `datomic.client.api`, aliased `d` (call sites unchanged beyond the require line)

## 2. Dev-Local Gateway process

- [x] 2.1 Scaffold a new Dev-Local Gateway process (own `-main`, own deps.edn or alias) depending only on `com.datomic/local`
- [x] 2.2 Implement the atom-backed, opaque-handle session registry keyed by random UUID handles
- [x] 2.3 Implement one HTTP endpoint per client-api operation needed (`create-database`, `connect`, `db`, `q`, `pull`, `transact`, `with`, `with-db`, `history`, `as-of`), request/response bodies as EDN (`Content-Type: application/edn`)
- [x] 2.4 Substitute handles for client/conn/db objects in responses, and resolve handles back to real objects from request arguments before delegating to the real `datomic.client.api` call
- [x] 2.5 Wire the gateway to a real dev-local storage directory (`:server-type :datomic-local`, configured `:storage-dir`), independent of the State Backend's own storage
- [x] 2.6 Add the Dev-Local Gateway as a service in `docker-compose.yml` for local `gateway`-mode dev/testing

## 3. Gateway-mode client in the State Backend

- [x] 3.1 Implement `GatewayClient`/`GatewayConn`/`GatewayDb` facade types wrapping an opaque handle string plus the configured gateway base URL
- [x] 3.2 Implement the facade's `q`/`pull`/`transact`/`with`/`with-db`/`db`/`history`/`as-of`/`create-database`/`connect`/`client` dispatch for `Gateway*` types: POST EDN-encoded arguments (handles substituted) to the corresponding Dev-Local Gateway endpoint, EDN-decode the response, re-wrap any returned handle into the matching `Gateway*` type
- [x] 3.3 Add `INFRATOMIC_DATOMIC_MODE` env var reading to `db/client` (`embedded` default, `gateway` selecting the `GatewayClient` path)
- [x] 3.4 Add env vars for the Dev-Local Gateway host/port, read only in `gateway` mode
- [x] 3.5 Confirm the existing hermetic test suite (`db/client :mem`, in-process dev-local) passes unchanged under the default `embedded` mode

## 4. Unified stored Rule format and registry

- [x] 4.1 Define the stored Rule map shape (`:rule/id`, `:rule/find`, `:rule/in`, `:rule/where`, optional `:rule/rule-defs`)
- [x] 4.2 Change `policy.clj`'s Rule registry from a compile-time `def` vector to an `(atom {})` keyed by `:rule/id`
- [x] 4.3 Rewrite the existing `security-groups-with-port-22-open` Rule into the stored data shape, seeded into the registry atom at namespace load
- [x] 4.4 Update `policy/evaluate` to run each registered Rule via `d/q` using its stored `:rule/find`/`:rule/in`/`:rule/where`/`:rule/rule-defs`, then pull each bound entity into a `:resource/id`/`:resource/type` pair in the shared evaluation code (not the Rule's own query)
- [x] 4.5 Confirm `policy/evaluate` reads the registry atom fresh on every call (no closed-over snapshot), so runtime-registered Rules are visible on the next Policy Check

## 5. Shared query/rule validator

- [x] 5.1 Implement `(validate-query {:find ... :in ... :where ...} rule-defs)`, recursing into `not`/`not-join`/`or`/`or-join` sub-clauses and every rule body in `rule-defs`
- [x] 5.2 Reject any function-invocation clause (`[(sym args...) binding?]`) whose `sym` is outside the explicit allowlist (`< > <= >= = not= ==`), while leaving bare-list rule-invocation clauses (e.g. `(reaches ?src ?dst)`) unaffected by this check
- [x] 5.3 Return `{:valid? true}` or `{:valid? false :reason "..."}`
- [x] 5.4 Add dedicated tests against the validator itself covering the allowlist boundary and the recursion/rule-invocation distinction

## 6. Ad-hoc query HTTP endpoint

- [x] 6.1 Implement `POST /query` (`Content-Type: application/edn`), accepting a `{:find ... :in ... :where ...}` map optionally with a `%` rule-set argument
- [x] 6.2 Validate the request body via the shared validator; respond `400` with the reason on rejection
- [x] 6.3 On success, run the query against `(d/db conn)` and respond with the raw `d/q` result set, EDN-encoded
- [x] 6.4 Wire the route into the app handler alongside `/state`, `/policy-check`, `/sync`, `/drift`

## 7. Runtime Rule registration HTTP endpoint

- [x] 7.1 Implement `POST /rules` (`Content-Type: application/edn`), accepting a Rule map (`:rule/id`, `:rule/find`, `:rule/in`, `:rule/where`, optional `:rule/rule-defs`)
- [x] 7.2 Validate the Rule's `:where`/`:rule/rule-defs` via the shared validator; respond `400` with the reason on rejection
- [x] 7.3 On success, `swap!` the Rule into the atom-backed registry (upsert by `:rule/id`) and respond `200`
- [x] 7.4 Wire the route into the app handler

## 8. `bootstrap` entrypoint

- [x] 8.1 Add a `bootstrap` branch to `-main`'s arg-dispatch that calls `(db/ensure-db! (db/client))` and exits `0` with a short confirmation, without starting Jetty
- [x] 8.2 Confirm the implicit bootstrap inside `-main`'s normal (no-arg) startup path is unchanged and both paths call the same `ensure-db!`

## 9. Dockerfile and CI/GHCR publish

- [x] 9.1 Write a Dockerfile that builds an uberjar via the existing Clojure CLI tooling and runs it under a JRE base image
- [x] 9.2 Set `ENTRYPOINT`/`CMD` to invoke `-main` (default: server-start; `bootstrap` selectable as a documented override arg)
- [x] 9.3 Add a GitHub Actions workflow that builds the image and publishes it to `ghcr.io/<org>/infratomic` using the workflow's built-in `GITHUB_TOKEN`
- [x] 9.4 Confirm no my.datomic.com or other external credential is required anywhere in the build or publish pipeline

## 10. User guide

- [x] 10.1 Write `docs/user-guide.md` covering: running the container against a Dev-Local Gateway + LocalStack, the State Backend's HTTP surface (`/state`, `/policy-check`, `/sync`, `/drift`, `/query`, `/rules`), the CLI, network reachability, and IAM reachability
- [x] 10.2 Include the `bootstrap` command and how to POST a new Rule and confirm a Policy Check picks it up

## 11. End-to-end verification

- [x] 11.1 Run the published image alongside a Dev-Local Gateway container and LocalStack via `docker run`/`docker-compose`, with `INFRATOMIC_DATOMIC_MODE=gateway`
- [x] 11.2 Run `bootstrap` against a fresh Dev-Local Gateway db and confirm schema installs
- [x] 11.3 Curl `/query` for a known resource and confirm results
- [x] 11.4 POST a new Rule via `/rules` and confirm a subsequent Policy Check picks it up
- [x] 11.5 Walk the user guide from a clean checkout for each covered capability
