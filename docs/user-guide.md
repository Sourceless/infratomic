# infratomic user guide

An operator-facing, end-to-end walkthrough of every user-facing capability
in this repository. Consolidates material scattered across `README.md`
and `docs/adr/` into one place; it does not replace either of those
(README stays the quick-start/contributor doc, the ADRs stay the
decision record).

Two ways to run the State Backend are covered:

- **From a checkout** (`clojure -M -m infratomic.state-backend.main`),
  `embedded` mode - an in-process, file-backed Datomic database. This is
  the fastest way to develop against the system and is what `README.md`'s
  quick-start uses.
- **As a container**, `gateway` mode - the State Backend runs in its own
  container, network-separated from its database, which is held by a
  separately-run Dev-Local Gateway container. This is the deployable,
  published-image path (see [Running the container](#running-the-container-gateway-mode)).

Every other capability below (Policy Check, the CLI, Sync, Drift
detection, network reachability, IAM reachability, the ad-hoc query and
Rule-registration endpoints) works identically against either mode - the
State Backend's HTTP surface and behavior do not depend on which mode
it's running in.

## Contents

- [The State Backend](#the-state-backend)
- [Running the container (`gateway` mode)](#running-the-container-gateway-mode)
- [Policy Check](#policy-check)
- [The CLI](#the-cli)
- [Sync](#sync)
- [Drift detection](#drift-detection)
- [Network reachability](#network-reachability)
- [IAM reachability](#iam-reachability)
- [Ad-hoc queries and runtime Rule registration](#ad-hoc-queries-and-runtime-rule-registration)

## The State Backend

The State Backend is a Clojure HTTP service implementing Terraform's
`http` state backend protocol (`GET`/`POST`/`DELETE` on `/state`), storing
Terraform state as real Datomic entities and datoms instead of an opaque
JSON blob - see `README.md`'s [State Backend](../README.md#state-backend-terraform-state-in-datomic)
section for the storage design, and
`docs/adr/0003-decompose-resource-attributes-into-datoms.md` for why.

From a checkout, in `embedded` mode (the default - `INFRATOMIC_DATOMIC_MODE`
unset):

```sh
nix develop
cd state-backend
clojure -M -m infratomic.state-backend.main
```

This starts the service on `http://localhost:8080`, creating the Datomic
database and schema on first run if they don't already exist (the
implicit "bootstrap" every normal startup performs - see
[`bootstrap`](#bootstrap) below). Point `terraform/provider.tf`'s `http`
backend at it and run `terraform apply` as normal (see `README.md`'s
[Switching `terraform/` onto the State Backend](../README.md#switching-terraform-onto-the-state-backend)).

The State Backend's full HTTP surface:

| Method | Path            | Purpose                                                     |
|--------|-----------------|--------------------------------------------------------------|
| GET    | `/state`        | Terraform state backend protocol - read current state        |
| POST   | `/state`        | Terraform state backend protocol - write state                |
| DELETE | `/state`        | Terraform state backend protocol - clear state                |
| POST   | `/policy-check` | [Policy Check](#policy-check) a Terraform plan                |
| POST   | `/sync`         | Run [Sync](#sync) against LocalStack                          |
| GET    | `/drift`        | [Drift detection](#drift-detection) report                    |
| POST   | `/query`        | [Ad-hoc Datalog query](#ad-hoc-queries-and-runtime-rule-registration) |
| POST   | `/rules`        | [Register a Policy Check Rule at runtime](#ad-hoc-queries-and-runtime-rule-registration) |

`/state` is JSON (Terraform's protocol); every other endpoint is EDN
(`Content-Type: application/edn`) - Datalog queries, tx-data, and Rules
are already plain Clojure data, and EDN round-trips them exactly.

### `bootstrap`

`clojure -M -m infratomic.state-backend.main bootstrap` (or, for the
container, `docker run <image> bootstrap`) creates the database and
installs schema, then exits immediately without starting the HTTP server
- the same idempotent logic (`db/ensure-db!`) that every normal startup
runs implicitly first. Useful for scripted/CI setup against a fresh
database (in particular, a fresh Dev-Local Gateway database in `gateway`
mode) before the State Backend itself needs to be up. Safe to run more
than once - a database that already has schema installed is left
unchanged.

## Running the container (`gateway` mode)

The published State Backend image (`ghcr.io/<org>/infratomic`, built by
this repository's `state-backend-image` CI workflow from
`state-backend/Dockerfile`) defaults to `gateway` mode
(`INFRATOMIC_DATOMIC_MODE=gateway`): rather than an in-process embedded
database, it connects over the network to a separately-run **Dev-Local
Gateway** process (`dev-local-gateway/`) - a small HTTP+EDN server
wrapping a real `com.datomic/local` database, mirroring the *shape* of a
Datomic Pro/Cloud client-api connection (opaque client/conn/db handles)
without requiring the licensed, credential-gated `com.datomic/client-pro`
or a my.datomic.com account. See
`openspec/changes/issue-35-ship-infratomic-deployable-container-real-datomic/design.md`
for the full design.

This genuinely network-separates the State Backend from its database:
the container holds no Datomic storage of its own in `gateway` mode, and
can be stopped/restarted freely as long as the Dev-Local Gateway (and its
storage) stays up.

### Bring up the whole stack

From the repo root:

```sh
docker compose up -d localstack gateway
```

This starts LocalStack (`http://localhost:4566`) and the Dev-Local
Gateway (`http://localhost:8081`, persisting to a named Docker volume so
data survives a `gateway` service restart).

Bootstrap a fresh database against the Dev-Local Gateway:

```sh
docker run --rm \
  --network host \
  -e INFRATOMIC_GATEWAY_HOST=localhost \
  -e INFRATOMIC_GATEWAY_PORT=8081 \
  ghcr.io/<org>/infratomic bootstrap
```

(`--network host` is the simplest way to reach a `docker compose`
service's published port from a one-off `docker run`; on a real Docker
network, put both containers on the same user-defined network and use the
Dev-Local Gateway's container/service name as `INFRATOMIC_GATEWAY_HOST`
instead - see `docker-compose.yml`'s `gateway` service.)

Then start the State Backend itself, pointed at the same Dev-Local
Gateway:

```sh
docker run -d --name infratomic-state-backend \
  --network host \
  -p 8080:8080 \
  -e INFRATOMIC_GATEWAY_HOST=localhost \
  -e INFRATOMIC_GATEWAY_PORT=8081 \
  ghcr.io/<org>/infratomic
```

### Verifying it worked

```sh
curl -i http://localhost:8080/state   # 204 (no state yet) confirms the server is up and
                                       # reachable through the Dev-Local Gateway
```

Run an [ad-hoc query](#ad-hoc-queries-and-runtime-rule-registration)
against the (empty, until you `terraform apply` something into it) live
db:

```sh
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/edn" \
  --data '{:find [?id] :where [[?e :resource/id ?id]]}'
# => []
```

Register a new Policy Check [Rule](#ad-hoc-queries-and-runtime-rule-registration):

```sh
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/edn" \
  --data '{:rule/id :s3-buckets-without-force-destroy
           :rule/find [?e]
           :rule/in [$]
           :rule/where [[?e :resource/type "aws_s3_bucket"]]}'
# => {:registered :s3-buckets-without-force-destroy}
```

A subsequent `POST /policy-check` against a plan containing an
`aws_s3_bucket` resource now flags it, with no restart required - Rule
registration takes effect on the very next Policy Check.

Point `terraform/provider.tf` at `http://localhost:8080/state` (as in the
`embedded`-mode walkthrough above) to run the sample app against the
containerized stack.

### The image builds with no Datomic Pro credential

Building `state-backend/Dockerfile` (and `dev-local-gateway/Dockerfile`)
requires no my.datomic.com account or download key - both depend only on
`com.datomic/local`, resolved from public Maven repositories like every
other dependency in this repository.

## Policy Check

Policy Check evaluates a Terraform plan against a registry of Rules
*before* `terraform apply` runs, catching violations (e.g. a security
group open to the internet on port 22) without ever touching real
infrastructure - see `docs/adr/0004-resolve-plan-time-references-to-address-stand-ins.md`
for how plan-time `null` identifying attributes are resolved so Rules can
match resources that don't exist yet.

A Rule is stored as data - a `:find`/`:in`/`:where` Datalog query (plus an
optional recursive rule-set) - not an arbitrary function; see
[Ad-hoc queries and runtime Rule registration](#ad-hoc-queries-and-runtime-rule-registration)
for the full shape and how to register one at runtime. One Rule is
registered by default: `security-groups-with-port-22-open`, flagging any
`aws_security_group` with an ingress rule open to `0.0.0.0/0` on port 22.

`POST /policy-check` with a `terraform show -json` plan document as the
body:

```sh
terraform plan -out=tfplan
terraform show -json tfplan > plan.json
curl -X POST http://localhost:8080/policy-check \
  -H "Content-Type: application/json" \
  --data @plan.json
# => {"violations":[{"rule":"security-groups-with-port-22-open","resource":{"id":"aws_security_group.example","type":"aws_security_group"}}]}
```

In practice you don't call this directly - the [CLI](#the-cli) does it
for you as part of `apply`.

## The CLI

`cli/` is a drop-in replacement for the `terraform` binary: every
subcommand passes straight through to the real `terraform` unchanged,
except `apply`, which runs a Policy Check first and refuses to proceed
(never calling real `terraform apply`) if it reports any violations. See
`README.md`'s [CLI](../README.md#cli-policy-gated-terraform-apply)
section for the full setup and a worked example (add an insecure security
group, watch the CLI block it; fix it, watch the CLI proceed).

```sh
clojure -Sdeps '{:deps {infratomic/cli {:local/root "../cli"}}}' -M -m infratomic.cli.main -- plan
clojure -Sdeps '{:deps {infratomic/cli {:local/root "../cli"}}}' -M -m infratomic.cli.main -- apply
```

## Sync

Sync reconciles the State Backend's db against what's actually deployed
in LocalStack: it discovers resources that exist in LocalStack but aren't
in Datomic yet (tagging them `:resource/managed? false` - a **Discovered
Resource**, never presented to Terraform as something it owns), and it
detects when an already Terraform-managed resource's live attributes have
diverged from what Terraform last wrote (tagging the write
`:resource/last-write-source :sync`, feeding [Drift detection](#drift-detection)).
See `docs/adr/0005-tag-managed-vs-discovered-with-resource-managed.md` and
`docs/adr/0009-flag-drift-via-write-source-tag-and-datomic-history.md`.

Sync runs two ways, producing identical results either way:

- **On demand**, via `POST /sync` (no request body - Sync's input is
  "whatever LocalStack currently has"):

  ```sh
  curl -X POST http://localhost:8080/sync
  # => {"discovered":[...],"updated":[...],"drifted":[...],"skipped_already_managed":N}
  ```

- **Automatically**, on a fixed interval, in-process, with no CLI or
  external cron involved - started as part of the State Backend's normal
  startup path (not when started with the `bootstrap` argument). The
  interval is configured via `INFRATOMIC_SYNC_INTERVAL_SECONDS` (seconds,
  default `300`); the first automatic run happens immediately at process
  start, then repeats every configured interval, measured from the end of
  one run to the start of the next so two runs never overlap. A failed
  automatic run is logged to stderr and does not stop future automatic
  runs. Every running State Backend instance runs its own scheduler
  independently - fine for today's single-instance deployment, but see
  `CONTEXT.md`'s "Sync" glossary entry for the known limitation this
  implies for a multi-instance deployment (no leader election / overlap
  prevention across instances).

## Drift detection

Independent of Policy Check (which only ever evaluates a *plan*, never
already-deployed state), Drift detection reports every Terraform-managed
resource whose most recent write came from Sync (i.e. its live value in
LocalStack no longer matches what Terraform last asserted).

`GET /drift`:

```sh
curl http://localhost:8080/drift
# => {"drifted":[{"type":"aws_security_group","id":"aws_security_group.example"}]}
```

## Network reachability

`state-backend/src/infratomic/state_backend/query.clj`'s `reachable?` and
`reachable-within-hops?` answer "can workload A reach workload B (or the
public internet)?" by traversing the deployed VPC/subnet/route-table/
security-group graph as a real recursive Datalog rule set - same-subnet,
local-route-within-VPC, single-hop VPC peering (`reachable?`), an
arbitrary-length chain of VPC peering hops bounded by a hop count
(`reachable-within-hops?`), and internet-gateway routes to
`"0.0.0.0/0"` - gated by forward-direction security group rules (AWS
security groups are stateful, so return-path rules are never checked).
See `README.md`'s [Querying deployed infrastructure](../README.md#querying-deployed-infrastructure)
section and `state-backend/test/infratomic/state_backend/query_test.clj`
for worked examples.

These are library functions, not (yet) their own dedicated HTTP endpoint
- call them from a REPL/script against a live db, or via the
[ad-hoc query endpoint](#ad-hoc-queries-and-runtime-rule-registration)
using the same `reaches`/`chain-reaches` rule sets directly:

```clojure
(require '[infratomic.state-backend.datomic :as d]
         '[infratomic.state-backend.db :as db]
         '[infratomic.state-backend.query :as query])

(def conn (db/ensure-db! (db/client)))
(query/reachable? (d/db conn) "aws_instance.a" "aws_instance.b")
(query/reachable-within-hops? (d/db conn) "aws_instance.a" "aws_instance.d" 3)
```

## IAM reachability

`state-backend/src/infratomic/state_backend/iam.clj`'s `iam-reachable?`
answers "can IAM principal P perform action A on resource R?" by parsing
every deployed IAM policy document (identity, trust, and resource-based)
into statement facts at query time (into a scratch, never-persisted
speculative db - see
`docs/adr/0005-derive-iam-policy-facts-at-query-time-via-speculative-db.md`)
and traversing them as a recursive `grants` Datalog rule set, including
role-assumption chains of arbitrary length (`sts:AssumeRole`) and scoped
deny-override semantics.

```clojure
(require '[infratomic.state-backend.datomic :as d]
         '[infratomic.state-backend.db :as db]
         '[infratomic.state-backend.iam :as iam])

(def conn (db/ensure-db! (db/client)))
(iam/iam-reachable? (d/db conn) "aws_iam_role.source" "aws_s3_bucket.data" "s3:GetObject")
```

## Ad-hoc queries and runtime Rule registration

Two HTTP endpoints share one safety boundary - the shared query/rule
validator (`infratomic.state-backend.validator`) - so there is exactly
one place deciding what Datalog is safe to run on untrusted input.

The validator walks every `:where` clause (recursing into
`not`/`not-join`/`or`/`or-join` sub-clauses and into every rule body of an
accompanying rule-set) and rejects any function-invocation clause (`[(sym
args...) binding?]`) whose `sym` isn't in a fixed allowlist: `< > <= >= =
not= ==`. A rule-invocation clause like `(reaches ?src ?dst)` is never
rejected by this check - only vector-wrapped function-invocation clauses
are.

### `POST /query` - ad-hoc Datalog query

Body: `{:find ... :in ... :where ...}`, optionally with a `:rule-defs`
rule-set alongside `%` in `:in`. Runs against the live db and responds
with the raw `d/q` result set, EDN-encoded.

```sh
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/edn" \
  --data '{:find [?id ?type] :where [[?e :resource/id ?id] [?e :resource/type ?type]]}'
```

A query using a disallowed function (e.g. `(str ?x)`) is rejected with
`400` and a reason, and never run.

### `POST /rules` - register a Policy Check Rule at runtime

Body: a stored Rule map - `:rule/id` (a unique keyword), `:rule/find`
(binding exactly the resource entity/id the Rule flags), `:rule/in`,
`:rule/where`, and an optional `:rule/rule-defs` for recursive Rules.
Validated the same way as `/query`; on success, upserted into the Rule
registry by `:rule/id` (registering under an id already present replaces
the previous Rule) and visible to the very next `POST /policy-check` -
no restart required.

```sh
curl -X POST http://localhost:8080/rules \
  -H "Content-Type: application/edn" \
  --data '{:rule/id :example-rule
           :rule/find [?e]
           :rule/in [$]
           :rule/where [[?e :resource/type "aws_s3_bucket"]]}'
```
