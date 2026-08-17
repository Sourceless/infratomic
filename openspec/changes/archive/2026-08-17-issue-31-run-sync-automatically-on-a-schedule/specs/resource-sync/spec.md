## REMOVED Requirements

### Requirement: Sync runs only when explicitly triggered
**Reason**: Superseded by automatic scheduled Sync (issue #31) — Sync now also runs on a configurable interval without an explicit trigger, in addition to remaining triggerable on demand.
**Migration**: No action needed for existing callers of `POST /sync` or the CLI `sync` command — explicit triggering still works exactly as before. See the new "Sync runs on demand or automatically on a configurable interval" requirement.

Sync SHALL only run in response to an explicit trigger — it SHALL NOT run automatically, on a timer, or as a side effect of any other operation (e.g. `POST`/`GET /state`, a Policy Check).

#### Scenario: No sync happens without being triggered
- **WHEN** the State Backend is running, resources exist in LocalStack that are not yet known to it, and Sync is never explicitly triggered
- **THEN** no Discovered Resource entities are created for those resources

## ADDED Requirements

### Requirement: Sync runs on demand or automatically on a configurable interval
Sync SHALL run in response to an explicit trigger (e.g. `POST /sync`) and SHALL also run automatically, without any explicit trigger, on a fixed interval configured via the `INFRATOMIC_SYNC_INTERVAL_SECONDS` environment variable (seconds, default `300`). An automatic run SHALL invoke the same `sync!` operation as an explicit trigger and SHALL produce the same results (discovered, updated, and drifted resources) as a manually triggered run given the same LocalStack state. Sync SHALL NOT run as a side effect of any other operation (e.g. `POST`/`GET /state`, a Policy Check) — only via an explicit trigger or the automatic interval.

#### Scenario: A resource created outside Terraform is discovered without a manual trigger
- **WHEN** the State Backend is running with automatic Sync enabled, a resource is created directly via LocalStack (not through Terraform or a manual `sync` trigger), and the configured interval elapses
- **THEN** a new Discovered Resource entity exists for that resource, without any explicit `POST /sync` or CLI `sync` invocation having occurred

#### Scenario: Automatic and manual sync produce equivalent results
- **WHEN** the same LocalStack resource state is present, and Sync is run once via an automatic scheduled trigger and once via an explicit `POST /sync` trigger
- **THEN** both runs produce equivalent results (the same resources discovered, updated, and flagged as drifted)

#### Scenario: The automatic interval is configurable
- **WHEN** the State Backend starts with `INFRATOMIC_SYNC_INTERVAL_SECONDS` set to a value other than the default
- **THEN** automatic Sync runs recur at that configured interval rather than the default 300 seconds

#### Scenario: No automatic sync happens before the interval elapses
- **WHEN** the State Backend is running, resources exist in LocalStack that are not yet known to it, no explicit trigger occurs, and the configured automatic interval has not yet elapsed since the last run
- **THEN** no additional Discovered Resource entities are created for those resources until the interval elapses or an explicit trigger occurs

### Requirement: A failed automatic sync run is logged and does not stop future runs
An automatic Sync run that raises any exception or error SHALL have that failure logged (printed to stderr) rather than silently dropped, and SHALL NOT prevent subsequent automatic runs from occurring at the next configured interval.

#### Scenario: A failed automatic run is logged
- **WHEN** an automatic Sync run raises an exception (e.g. LocalStack is unreachable)
- **THEN** a failure message is printed to stderr describing the failure

#### Scenario: A failure in one automatic run does not suppress the next
- **WHEN** an automatic Sync run raises an exception
- **THEN** the next automatic Sync run still occurs at the next configured interval
