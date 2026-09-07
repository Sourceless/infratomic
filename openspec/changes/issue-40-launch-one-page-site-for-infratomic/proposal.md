## Why

Issue #40 asks for a live one-page site at `https://infratomic.org` ahead of the 2026-09-17 "Infrastructure as Data" conference talk, so attendees have somewhere to learn the pitch, see a real quickstart, and see what the project can (and can't yet) do. Nothing in the repo today builds or deploys any such page — there's a README aimed at contributors, but no public-facing site and no Pages workflow.

## What Changes

- Add a single static HTML/CSS page (no build step, no JS framework, no new tooling dependency) with two sections only, per the issue's explicit single-landing-page scope:
  - **Quickstart** — the real, working policy-gated `terraform apply` CLI example trimmed from `README.md`'s "CLI (policy-gated `terraform apply`)" section, copy-pasteable as-is.
  - **Features** — split into two groups: **Available now** (Policy Checks, Reachability graph search — described using `CONTEXT.md`'s canonical glossary terms) and **On the roadmap** (blast-radius simulation, a safe-deploy-ordering solver, and Reachability auto-fix, each as its own line item, described in the talk's own looser language since none of these three have canonical `CONTEXT.md` terms).
  - No "how it works" / architecture section, and no link to `sourceless.org` — both explicitly deferred per the alignment decision.
- Add a GitHub Actions workflow that builds no artifact (there's nothing to build) and deploys the page via `actions/upload-pages-artifact` + `actions/deploy-pages`, modeled on this repo's one existing workflow (`.github/workflows/state-backend-image.yml`) for permissions/secrets conventions, adding the `pages: write` / `id-token: write` permissions Pages deployment requires (new to this repo).
- Add a `CNAME` file (containing `infratomic.org`) alongside the page so GitHub Pages is configured for the custom domain once Pages is enabled and DNS is repointed.

**Explicitly out of scope for this PR** (per the alignment decision — a human with registrar/repo-admin access handles these manually, decoupled from code review):
- Repointing `infratomic.org`'s DNS from Squarespace to GitHub Pages.
- Enabling GitHub Pages in the repo's Settings (build type, custom domain, "Enforce HTTPS").

The issue itself stays open after this change merges, until `https://infratomic.org` is verified live — that verification depends on the manual steps above, not on anything this change can complete unilaterally.

## Capabilities

### New Capabilities
- **site** — the public one-page site's content requirements (quickstart section, features list with "available now"/"roadmap" split) and its GitHub Pages deployment (workflow, CNAME).

### Modified Capabilities
(none)

## Impact

- New files: a static site page and its assets (exact path decided in `design.md`), `CNAME` (at `site/CNAME`, per GitHub Pages' requirement that it live at the root of the *published* content, which is `site/`, not the repo root — see `design.md`), `.github/workflows/<pages-workflow>.yml`.
- No application code (`cli/`, `state-backend/`, `dev-local-gateway/`) is touched.
- `README.md`, `CONTEXT.md`, and `docs/` are not modified — the site reuses their content by reference/copy, not by editing them.
- New repo-level capability: this is the first GitHub Pages deployment and the first workflow requesting `pages`/`id-token` permissions.
- Follow-up (tracked on issue #40, not in this change's tasks): DNS repoint + Pages enablement by whoever holds registrar/repo-admin access.
