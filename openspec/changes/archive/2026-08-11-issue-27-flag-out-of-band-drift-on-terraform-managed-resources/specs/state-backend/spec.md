## ADDED Requirements

### Requirement: Every write to a Resource entity records its write source
Every write the State Backend makes to a Resource entity — via `POST /state` or via Sync — SHALL record `:resource/last-write-source` on that entity, identifying whether the write came from Terraform (`:terraform`) or from Sync (`:sync`). A `POST /state` write SHALL always record `:terraform`.

#### Scenario: Posting state tags the resource as Terraform-sourced
- **WHEN** a client sends `POST /state` with a managed resource entry, whether creating a new Resource entity or updating an existing one
- **THEN** the resulting Resource entity's `:resource/last-write-source` is `:terraform`
