## 1. Site content

- [ ] 1.1 Create `site/index.html` with a minimal header (project name + the talk's tagline: "If you treat infrastructure as data, you can test things that don't exist yet."), a quickstart section, a features section, and a footer linking to the GitHub repo and `LICENSE` (Apache 2.0). No third content section.
- [ ] 1.2 Quickstart section: reuse `README.md`'s "CLI (policy-gated `terraform apply`)" example nearly verbatim — the `clojure -Sdeps '{:deps {infratomic/cli {:local/root "../cli"}}}' -M -m infratomic.cli.main -- apply` invocation and a short description of the Policy Check gate — trimmed to just this one example, not the full README section.
- [ ] 1.3 Features section, "Available now" group: Policy Checks and Reachability (graph search), each described using `CONTEXT.md`'s canonical terms (Policy Check, Rule, Violation; Reachable, Workload, IAM-reachable) — do not introduce new terminology for these two.
- [ ] 1.4 Features section, "On the roadmap" group: three separate line items — blast-radius simulation, a safe-deploy-ordering solver, and Reachability auto-fix — described in the talk's own language (no invented canonical terms, no edits to `CONTEXT.md`).
- [ ] 1.5 Create `site/style.css` with plain CSS (no framework, no build step) styling the page; link it from `index.html` via a relative `<link>`.
- [ ] 1.6 Create `site/CNAME` containing exactly `infratomic.org`.

## 2. Deployment workflow

- [ ] 2.1 Create `.github/workflows/pages.yml`: triggers on `push` to `main` (paths-filtered to `site/**` and the workflow file itself) and `workflow_dispatch`; permissions `contents: read`, `pages: write`, `id-token: write`; steps `actions/checkout`, `actions/upload-pages-artifact` with `path: site`, `actions/deploy-pages`.
- [ ] 2.2 Set the deploy job's `environment: github-pages` (with the `page_url` output) per `actions/deploy-pages`' documented usage, matching the convention other Pages-deploying repos in this org already use.

## 3. Verification

- [ ] 3.1 Open `site/index.html` locally in a browser (`file://` or a local static server) and confirm the quickstart command and features list render as expected, with no broken relative links (`style.css`, footer links to the repo and `LICENSE`).
- [ ] 3.2 Copy the quickstart section's command and run it against a checkout with the State Backend and real `terraform` running, per `README.md`'s own verification steps, to confirm it behaves as documented (Policy Check gating on `apply`).
- [ ] 3.3 Confirm `site/CNAME`'s content is exactly `infratomic.org` (no trailing content, no `www` prefix, no protocol).
- [ ] 3.4 After merge, confirm the `pages.yml` workflow runs on the merge commit and either succeeds (if Pages is already enabled) or fails specifically at the `deploy-pages` step with a "Pages not enabled" style error (expected until the manual follow-up below happens) — a failure anywhere else (e.g. `upload-pages-artifact`) is a real defect to fix.

## 4. Manual follow-up (outside this change — do not treat as blocking merge)

- [ ] 4.1 Repo admin enables GitHub Pages (Settings → Pages → Build and deployment: GitHub Actions) and sets the custom domain to `infratomic.org`.
- [ ] 4.2 Registrar-access holder repoints `infratomic.org`'s DNS from Squarespace to GitHub Pages (apex `A` records to GitHub Pages' published IPs, optional `www` `CNAME`), then enables "Enforce HTTPS" once DNS propagates and GitHub issues a certificate.
- [ ] 4.3 Once both of the above are done, verify `https://infratomic.org` loads over HTTPS showing the quickstart and features sections with no broken links, and close out issue #40's "site is live" acceptance criterion.
