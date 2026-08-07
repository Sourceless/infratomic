## 1. Plan-decomposition glue (state-backend)

- [ ] 1.1 Add a new `infratomic.state-backend.policy` namespace (or similarly named module) alongside `handler.clj`/`query.clj`.
- [ ] 1.2 Implement a function that, given a parsed `terraform show -json` document, extracts `planned_values.root_module.resources[]`.
- [ ] 1.3 Implement Address Stand-in resolution: for each resource, for each modeled identifying attribute (per `db/resource-schema`) whose value is `null`, substitute the resource's own address (`type + "." + name`).
- [ ] 1.4 Extend Address Stand-in resolution to cross-resource references: when an attribute is `null` but `configuration.root_module.resources[].expressions.<key>.references` names exactly one other resource, substitute that resource's address instead.
- [ ] 1.5 Implement a function building one resource's speculative tx-map: `:resource/id` (`type + "." + name`), `:resource/type`, `:resource/name`, plus `(db/resource-attr-tx type values)` using the Address-Stand-in-resolved `values`.
- [ ] 1.6 Implement a function building the full speculative tx-data for a plan document (all resources' tx-maps, no state-version entity).

## 2. Rule registry and Policy Check evaluation

- [ ] 2.1 Define the Rule contract and a static vector of registered Rules containing `query/security-groups-with-port-22-open` (require `infratomic.state-backend.query`, do not modify it).
- [ ] 2.2 Implement a function that, given a `conn` and a plan document, runs `(d/with (d/with-db conn) {:tx-data (speculative tx-data)})`, evaluates every registered Rule against `:db-after`, and returns a seq of Violations (rule identity + resource id/type) for every non-empty Rule result.
- [ ] 2.3 Verify `d/with` is never followed by `d/transact` anywhere in this path — the speculative db must never be persisted.

## 3. Policy Check HTTP endpoint

- [ ] 3.1 Add a new route (e.g. `POST /policy-check`) to the Ring handler wired up in `main.clj`, backed by the function from 2.2.
- [ ] 3.2 Parse the request body as the plan JSON document; respond `400` on invalid JSON, consistent with the existing `/state` endpoint's error handling style.
- [ ] 3.3 Respond `200` with `{"violations": [...]}` (empty array when no Violations), JSON-encoding each Violation's rule identity and resource id/type.
- [ ] 3.4 Add tests (fixture-based, per `handler_test.clj`'s pattern) covering: a plan with no violations, a plan violating the port-22 rule via an already-known id (existing SG being edited), and a plan violating the port-22 rule via Address Stand-ins (brand-new SG + rule, both `null` ids at plan time).
- [ ] 3.5 Add a test confirming a Policy Check call leaves `GET /state`'s result unchanged (no persistence side effect).

## 4. CLI project scaffolding

- [ ] 4.1 Create top-level `cli/` directory with its own `deps.edn` (Clojure CLI dependencies: JSON parsing, HTTP client).
- [ ] 4.2 Create `cli/src/infratomic/cli/main.clj` with a `-main` entry point parsing the first non-flag argument as the Terraform subcommand.
- [ ] 4.3 Implement passthrough: for any subcommand other than `apply`, invoke the real `terraform` binary with the given args, inheriting stdio, and exit with its exit code.
- [ ] 4.4 Add a config point (env var and/or CLI flag) for the Policy Check endpoint's base URL, defaulting to the sample app's local State Backend address.

## 5. CLI apply-gating flow

- [ ] 5.1 On `apply`, shell out to `terraform plan -out=tfplan`, propagating a plan failure (non-zero exit) straight through without calling the Policy Check.
- [ ] 5.2 On a successful plan, shell out to `terraform show -json tfplan` and parse its stdout as JSON.
- [ ] 5.3 POST the parsed plan JSON to the Policy Check endpoint and parse the `{"violations": [...]}` response.
- [ ] 5.4 If violations are present: print each Violation naming its rule and violating resource, exit non-zero, and do not invoke real `terraform apply`.
- [ ] 5.5 If no violations: invoke `terraform apply tfplan`, inheriting stdio, and exit with its exit code.

## 6. End-to-end verification

- [ ] 6.1 Manually verify against the sample app: add a new insecure SG (port 22 from `0.0.0.0/0`), run the CLI's `apply` — expect non-zero exit, a printed violation naming the SG, and nothing created in LocalStack or posted to the State Backend.
- [ ] 6.2 Fix the SG rule and re-run the CLI's `apply` — expect real `terraform apply` to proceed and succeed normally.
- [ ] 6.3 Confirm every non-`apply` subcommand (e.g. `plan`, `init`, `state list`) behaves identically through the CLI as running `terraform` directly.
