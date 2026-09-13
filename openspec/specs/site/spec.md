# site Specification

## Purpose

A single public-facing landing page for infratomic, deployed via GitHub Pages, giving a conference-talk audience (and anyone else who lands on `infratomic.org`) a quickstart example and an honest features list — without overclaiming capabilities the codebase doesn't yet implement.

## Requirements

### Requirement: The site is a single page with exactly two content sections
The site SHALL consist of exactly one page containing a quickstart section and a features section, and SHALL NOT contain a multi-page docs structure, a blog, or an architecture/"how it works" section.

#### Scenario: Loading the page
- **WHEN** the published page is loaded
- **THEN** it renders a quickstart section and a features section, and no additional content section beyond a minimal header (tagline) and footer (license/repo links)

### Requirement: The quickstart section shows a real, working CLI example
The quickstart section SHALL present the policy-gated `terraform apply` CLI example, using the same command(s) documented in `README.md`'s "CLI (policy-gated `terraform apply`)" section, such that copying and running the shown command(s) against a checkout with the State Backend and real `terraform` running behaves as described.

#### Scenario: Copying the quickstart command
- **WHEN** a reader copies the quickstart section's example command and runs it in a checkout set up per `README.md`
- **THEN** it behaves exactly as `README.md`'s own documented CLI usage describes (passthrough for other subcommands, Policy Check gating on `apply`)

### Requirement: The features list separates shipped capabilities from roadmap capabilities
The features section SHALL present two distinct groups — "Available now" and "On the roadmap" — and SHALL NOT present any roadmap-only capability as though it were currently shipped.

#### Scenario: Available-now features are only ones with real implementation
- **WHEN** the features section's "Available now" group is read
- **THEN** it lists Policy Checks, Reachability (graph search), Scheduled Sync, Drift detection, Unattended Terraform execution, and Auto-reconciliation, each described using `CONTEXT.md`'s canonical glossary terms for that capability

#### Scenario: Roadmap features are listed separately and distinctly
- **WHEN** the features section's "On the roadmap" group is read
- **THEN** it lists blast-radius simulation, a safe-deploy-ordering solver, and Reachability auto-fix as three separate line items, none of which appears in the "Available now" group

### Requirement: The site is deployed via a GitHub Actions workflow to GitHub Pages
The repository SHALL contain a GitHub Actions workflow that publishes the site's content as a GitHub Pages deployment using `actions/upload-pages-artifact` and `actions/deploy-pages`, requesting only the `GITHUB_TOKEN`-scoped permissions Pages deployment requires (no externally-provisioned secret).

#### Scenario: A push to main triggers a deployment
- **WHEN** a change under the site's source directory is pushed to `main`
- **THEN** the workflow runs, uploads the site's content as a Pages artifact, and deploys it, using only the workflow's built-in `GITHUB_TOKEN`

### Requirement: A CNAME file configures the custom domain
The site's published content SHALL include a `CNAME` file containing `infratomic.org`, so that once GitHub Pages is enabled and DNS is repointed by a repo administrator, the custom domain resolves to this Pages deployment.

#### Scenario: CNAME is present in the published artifact
- **WHEN** the Pages deployment workflow uploads the site's content
- **THEN** the uploaded artifact includes a `CNAME` file at its root containing exactly `infratomic.org`

### Requirement: DNS repoint and Pages enablement are explicitly out of this capability's automated scope
This capability SHALL NOT attempt to modify DNS records or enable GitHub Pages in repository settings — those actions require registrar/repo-admin access outside what a workflow or code change can perform, and are documented as manual follow-up.

#### Scenario: Workflow does not attempt out-of-band configuration
- **WHEN** the deployment workflow runs
- **THEN** it only uploads and deploys the Pages artifact — it makes no API call to change DNS records or to enable/configure the repository's Pages settings
