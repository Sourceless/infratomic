## 1. Scheduler primitive

- [ ] 1.1 Implement a `schedule!` function wrapping `java.util.concurrent.ScheduledExecutorService/scheduleWithFixedDelay`, taking an interval (seconds) and a task (`fn`) to run, with initial delay `0`.
- [ ] 1.2 Wrap each scheduled invocation of `sync/sync!` in `try/catch Throwable`: on failure, `println` a failure message (including the exception) to `*err*`, and swallow it so the task returns normally.
- [ ] 1.3 Unit test: `schedule!` with a short real interval (e.g. 20ms) fires the given task multiple times within a bounded wait, using a real `ScheduledExecutorService`.
- [ ] 1.4 Unit test: a task that throws on its first invocation still fires again on the next scheduled tick (failure isolation) — asserts the catch lives in the task body, not the executor.

## 2. Configuration

- [ ] 2.1 Read `INFRATOMIC_SYNC_INTERVAL_SECONDS` once at startup via `System/getenv`, `or`-chained to default `300`, following the existing `INFRATOMIC_<NAME>` convention (`db.clj`'s `gateway-mode?`/`gateway-url`).

## 3. Wiring into startup

- [ ] 3.1 In `-main`'s normal-startup path (after `conn`/`ec2-client` are built, before/alongside `jetty/run-jetty`), start the scheduler via `schedule!`, passing a task that calls `sync/sync!` with the existing `conn`/`ec2-client`.
- [ ] 3.2 Confirm the `bootstrap` path is unaffected — the scheduler is not started when `-main` is invoked with the `bootstrap` argument.

## 4. Verification

- [ ] 4.1 Integration test or manual check: with a short `INFRATOMIC_SYNC_INTERVAL_SECONDS`, a resource hand-created in LocalStack (not via Terraform or a manual `sync` trigger) appears as a Discovered Resource after the interval elapses, with no `POST /sync`/CLI invocation.
- [ ] 4.2 Confirm `POST /sync` (manual trigger) continues to work unchanged alongside the automatic scheduler.
- [ ] 4.3 Update `docs/user-guide.md`'s Sync section to document `INFRATOMIC_SYNC_INTERVAL_SECONDS`, its default, and that Sync now also runs automatically.

## 5. Documentation

- [ ] 5.1 Note the known limitation (no leader election / overlap prevention across multiple State Backend instances) in `CONTEXT.md` or the user guide, wherever Sync's "runs inside the State Backend process" precedent is documented.
