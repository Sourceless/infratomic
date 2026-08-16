## Context

See proposal.md - Why. The State Backend's only Sync trigger today is `sync-endpoint` (`POST /sync`), which calls `sync/sync!` and returns its summary. `main.clj`'s `-main` branches on a `bootstrap` argument (installs schema, exits) vs. normal startup (builds `conn`/`ec2-client`, calls `jetty/run-jetty :join? true`, blocking forever). There is no logging library in this repo (`deps.edn` has no slf4j/timbre binding) and no scheduling library (no `chime`/`quartz`/`at-at`) — the codebase consistently prefers JDK-native primitives (e.g. `java.net.http.HttpClient` over an SDK-bundled client) where reasonable.

## Goals / Non-Goals

**Goals:**
- Trigger `sync/sync!` automatically on a configurable interval, in-process, with no CLI or external cron involved.
- Guarantee scheduled runs never overlap and a failure in one run never silences future runs.
- Keep the change strictly to the trigger path — zero changes to `sync!`, `resource-tx`, matching, or drift-detection logic.

**Non-Goals:**
- Leader election / overlap prevention across multiple State Backend instances (single-instance deployment only; `sync!` is upsert-based so a hypothetical double-run wastes work but doesn't corrupt data).
- A structured/queryable log of past sync failures — stderr `println` only.
- Any change to `POST /sync`'s behavior or response shape.

## Decisions

- **JDK-native scheduler, no new dependency**: use `java.util.concurrent.ScheduledExecutorService`. Alternative considered: a scheduling library (`chime`, `at-at`) — rejected, matches the repo's existing preference for JDK-native primitives over new deps for a single call site.
- **`scheduleWithFixedDelay`, not `scheduleAtFixedRate`**: guarantees the interval is measured from the end of one `sync!` run to the start of the next, so two runs can never overlap given `sync!`'s duration is unbounded/unmeasured. `scheduleAtFixedRate` was rejected because it can queue/overlap runs if a single `sync!` takes longer than the interval.
- **Initial delay `0`**: first sync runs immediately at process startup so a fresh or restarted process isn't stale for a full interval, then repeats every `INFRATOMIC_SYNC_INTERVAL_SECONDS` thereafter.
- **`try/catch Throwable` inside the scheduled task body, not relying on the executor**: `ScheduledExecutorService` silently suppresses all future executions of a periodic task after it throws once, with no logging of its own. The task body must catch broadly (`Throwable`, not just `Exception`, to also catch `Error`s) and swallow so the task always returns normally and the executor keeps scheduling it.
- **Logging via `println` to `*err*`**: consistent with the repo's existing `println`-to-stdout convention (`main.clj`'s startup message) rather than introducing a first logging dependency for one call site.
- **Extracted plain `schedule!` function**: wraps the `ScheduledExecutorService` setup so it's callable and testable independently of `-main`/Jetty, taking the interval and the task (already wrapped in `try/catch`) as arguments.
- **Scheduler wired into `-main`'s normal-startup path only, after `conn`/`ec2-client` exist**: not the `bootstrap` path (which exits immediately after installing schema and must not start a scheduler). Calls `sync/sync!` directly against the same `conn`/`ec2-client` the HTTP handlers use — this is the same reference `sync-endpoint` calls, so "same results as a manual sync run" holds by construction rather than needing separate verification.
- **Config via `INFRATOMIC_SYNC_INTERVAL_SECONDS`, default `300`**: follows the existing `INFRATOMIC_<NAME>` convention (`db.clj`'s `gateway-mode?`/`gateway-url`), read once via `System/getenv` with an `or`-chained default. Unit is explicit in the name to avoid ambiguity between seconds/minutes.
- **Test strategy: real executor, short real interval (~20ms), no injectable clock**: avoids introducing test-only clock abstraction machinery. Two tests: (1) `schedule!` with a short interval fires the task multiple times within a bounded wait; (2) a task that throws on its first invocation still fires again on the next scheduled tick, proving failure isolation.

## Risks / Trade-offs

- [A long-running or hung `sync!` call delays all subsequent scheduled runs indefinitely, since `scheduleWithFixedDelay` never runs concurrently] → Accepted: `sync!`'s duration is already bounded by LocalStack's API responsiveness in practice; no timeout is introduced by this change, matching `sync!`'s current unbounded synchronous behavior on the manual/`POST /sync` path.
- [Stderr `println` failures are easy to miss in production log aggregation without a structured logger] → Accepted per alignment decision; revisit if/when a logging library is introduced repo-wide.
- [Multiple State Backend instances would each independently schedule and run Sync, wasting work] → Out of scope; documented as a known limitation, no locking introduced. Current deployment is single-instance.
