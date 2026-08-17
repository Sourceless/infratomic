## Why

Sync only runs when a human invokes the CLI's `sync` subcommand, so newly created or drifted resources go unnoticed until someone remembers to run it. Discovery should happen on its own so operators aren't the trigger mechanism (#31, part of epic #30).

## What Changes

- Add an in-process scheduler, started from `-main`'s normal-startup path (not the `bootstrap` path), that calls `sync/sync!` directly on a fixed delay.
- Extract a plain `schedule!` function wrapping `java.util.concurrent.ScheduledExecutorService/scheduleWithFixedDelay`, with initial delay `0` (first run at startup) and the configured delay between the end of one run and the start of the next — never overlapping runs.
- Each scheduled invocation of `sync!` is wrapped in its own `try/catch Throwable`; on failure it `println`s a failure message to `*err*` (stderr) and swallows the exception so the executor keeps scheduling future runs.
- Add a new `INFRATOMIC_SYNC_INTERVAL_SECONDS` environment variable (seconds, default `300`) read once at startup, following the existing `INFRATOMIC_<NAME>` convention.
- No changes to `sync!`, `resource-tx`, drift detection, matching, or remediation logic — this only adds a second, automatic trigger path for the exact same `sync!` call the CLI/`POST /sync` already use.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `resource-sync`: replaces the "Sync runs only when explicitly triggered" requirement (Sync SHALL NOT run on a timer) with a requirement that Sync also runs automatically on a configurable interval, in addition to remaining triggerable on demand via `POST /sync`.

## Impact

- `state-backend/src/infratomic/state_backend/main.clj` — `-main` starts the scheduler after `conn`/`ec2-client` are built, in the normal-startup path only.
- `state-backend/src/infratomic/state_backend/sync.clj` (or a new small namespace) — the `schedule!` function and the failure-isolated task wrapper around `sync!`.
- New env var `INFRATOMIC_SYNC_INTERVAL_SECONDS`, default `300`.
- No new dependencies (`java.util.concurrent.ScheduledExecutorService`, `println`/stderr only).
- Known limitation: no leader election / overlap prevention across multiple State Backend instances; out of scope for this change since deployment is currently single-instance and `sync!` is upsert-based.
