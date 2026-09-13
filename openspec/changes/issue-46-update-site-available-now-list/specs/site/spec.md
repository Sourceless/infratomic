## MODIFIED Requirements

### Requirement: The features list separates shipped capabilities from roadmap capabilities
The features section SHALL present two distinct groups — "Available now" and "On the roadmap" — and SHALL NOT present any roadmap-only capability as though it were currently shipped.

#### Scenario: Available-now features are only ones with real implementation
- **WHEN** the features section's "Available now" group is read
- **THEN** it lists Policy Checks, Reachability (graph search), Scheduled Sync, Drift detection, Unattended Terraform execution, and Auto-reconciliation, each described using `CONTEXT.md`'s canonical glossary terms for that capability

#### Scenario: Roadmap features are listed separately and distinctly
- **WHEN** the features section's "On the roadmap" group is read
- **THEN** it lists blast-radius simulation, a safe-deploy-ordering solver, and Reachability auto-fix as three separate line items, none of which appears in the "Available now" group
