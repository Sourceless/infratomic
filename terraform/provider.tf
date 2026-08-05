terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }

  # State is stored in the State Backend (state-backend/), a Clojure service
  # implementing Terraform's `http` backend protocol and persisting state in
  # Datomic. LOCK/UNLOCK are out of scope, so `lock_address`/`unlock_address`
  # are intentionally omitted. Start the service (`clojure -M -m
  # infratomic.state-backend.main` from `state-backend/` inside `nix
  # develop`) before running `terraform init`/`terraform apply`.
  backend "http" {
    address = "http://localhost:8080/state"
  }
}

# Configured to target the LocalStack gateway instead of real AWS: dummy
# credentials, validation/metadata checks skipped, and every service
# endpoint pointed at http://localhost:4566 (LocalStack's single-port
# gateway) using S3 path-style addressing so requests don't depend on
# wildcard DNS resolution.
provider "aws" {
  region = "us-east-1"

  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  s3_use_path_style = true

  endpoints {
    s3     = "http://localhost:4566"
    iam    = "http://localhost:4566"
    lambda = "http://localhost:4566"
    sts    = "http://localhost:4566"
  }
}
