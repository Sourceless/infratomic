## Why

`site/index.html`'s "Available now" list predates scheduled Sync + drift detection (#31, #32), unattended Terraform execution (#33), and auto-reconciliation (#34) — it only names Policy Checks and Reachability, so visitors underestimate what's actually shipped.

## What Changes

- `site/index.html`'s "Available now" `<ul>` gains four new `<li>` entries (six total, alongside the existing Policy Checks and Reachability entries):
  - **Scheduled Sync** — automatic ingestion of unmanaged resources on a fixed interval, in addition to the existing on-demand CLI trigger.
  - **Drift detection** — attribute-level, New-Child, and Removed-Child drift, surfaced via `GET /drift` and the CLI's `drift-check` subcommand. Described as detection only (does not itself remediate).
  - **Unattended Terraform execution** — `apply`/`import`/`destroy` running unattended, each recorded as an Invocation, with per-resource-address locking.
  - **Auto-reconciliation** — automatic policy-Rule evaluation against live state on every Sync pass, remediating violations via `terraform apply` or synthesized import+destroy, with every decision recorded.
- `site/index.html`'s `<meta name="description">` tag is updated to the same effect, replacing the stale "Policy-gated terraform apply and reachability graph search today" copy.
- "On the roadmap" section is left byte-for-byte unchanged.
- `openspec/specs/site/spec.md`'s "Available-now features are only ones with real implementation" scenario is updated to name all six shipped capabilities instead of just two.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `site`: the "Available-now features are only ones with real implementation" scenario's expected feature list changes from two named capabilities (Policy Checks, Reachability) to six (adding Scheduled Sync, Drift detection, Unattended Terraform execution, Auto-reconciliation).

## Impact

- `site/index.html` — the "Available now" `<ul>` and the `<meta name="description">` tag.
- `openspec/specs/site/spec.md` — one scenario's expected content.
- No application code, other capabilities, or the "On the roadmap" section are touched.
