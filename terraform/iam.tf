# Minimal IAM role required by the Lambda `create-function` API. LocalStack
# Community does not enforce IAM policy, so this is deliberately bare: an
# assume-role policy for the Lambda service, no additional policy
# attachments beyond what's needed for Lambda to assume the role.
data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda_exec" {
  name               = "infratomic-test-app-lambda-exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

# --- IAM reachability fixtures (issue #19) ----------------------------------
#
# LocalStack Community does not enforce IAM policy at all, so these fixtures
# only need to prove state-consistency: `terraform apply` succeeds and
# `terraform state list` shows every resource below with no errors. The
# actual `iam-reachable?` semantics (direct identity-based access,
# resource-based access, multi-hop role-assumption chains, deny-overrides-
# allow) are exercised by `state-backend/test/infratomic/state_backend
# /iam_test.clj` against separate, hand-built resource fixtures, mirroring
# `query_test.clj`'s own pattern - not against this applied state.

# A shared, reusable trust policy for every fixture role below whose trust
# relationship isn't itself under test (only `iam_assume_target`'s trust
# policy is - see below).
data "aws_iam_policy_document" "iam_fixture_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# Direct identity-based policy fixture: an inline `aws_iam_role_policy` on
# `iam_reader` granting it `s3:GetObject` on `iam_direct_target`'s objects.
resource "aws_s3_bucket" "iam_direct_target" {
  bucket        = "infratomic-test-app-iam-direct-target"
  force_destroy = true
}

resource "aws_iam_role" "iam_reader" {
  name               = "infratomic-test-app-iam-reader"
  assume_role_policy = data.aws_iam_policy_document.iam_fixture_assume_role.json
}

data "aws_iam_policy_document" "iam_reader_policy" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_direct_target.arn}/*"]
  }
}

resource "aws_iam_role_policy" "iam_reader_policy" {
  name   = "infratomic-test-app-iam-reader-policy"
  role   = aws_iam_role.iam_reader.id
  policy = data.aws_iam_policy_document.iam_reader_policy.json
}

# Resource-based policy fixture: a bucket policy on
# `iam_resource_policy_target` granting `iam_bucket_principal`
# `s3:GetObject`, with no identity-based policy on the role at all.
resource "aws_iam_role" "iam_bucket_principal" {
  name               = "infratomic-test-app-iam-bucket-principal"
  assume_role_policy = data.aws_iam_policy_document.iam_fixture_assume_role.json
}

resource "aws_s3_bucket" "iam_resource_policy_target" {
  bucket        = "infratomic-test-app-iam-resource-policy-target"
  force_destroy = true
}

data "aws_iam_policy_document" "iam_resource_policy_target" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_resource_policy_target.arn}/*"]

    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.iam_bucket_principal.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "iam_resource_policy_target" {
  bucket = aws_s3_bucket.iam_resource_policy_target.id
  policy = data.aws_iam_policy_document.iam_resource_policy_target.json
}

# Role-assumption-chain fixture: `iam_assume_source` can assume
# `iam_assume_target` (trust policy on the target grants the source
# `sts:AssumeRole`, and the source's own identity policy grants it
# permission to assume the target), and the target itself is granted
# `s3:GetObject` on `iam_assume_chain_target`, so the chain has something to
# reach.
resource "aws_s3_bucket" "iam_assume_chain_target" {
  bucket        = "infratomic-test-app-iam-assume-chain-target"
  force_destroy = true
}

resource "aws_iam_role" "iam_assume_source" {
  name               = "infratomic-test-app-iam-assume-source"
  assume_role_policy = data.aws_iam_policy_document.iam_fixture_assume_role.json
}

data "aws_iam_policy_document" "iam_assume_target_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.iam_assume_source.arn]
    }
  }
}

resource "aws_iam_role" "iam_assume_target" {
  name               = "infratomic-test-app-iam-assume-target"
  assume_role_policy = data.aws_iam_policy_document.iam_assume_target_trust.json
}

data "aws_iam_policy_document" "iam_assume_source_policy" {
  statement {
    effect    = "Allow"
    actions   = ["sts:AssumeRole"]
    resources = [aws_iam_role.iam_assume_target.arn]
  }
}

resource "aws_iam_role_policy" "iam_assume_source_policy" {
  name   = "infratomic-test-app-iam-assume-source-policy"
  role   = aws_iam_role.iam_assume_source.id
  policy = data.aws_iam_policy_document.iam_assume_source_policy.json
}

data "aws_iam_policy_document" "iam_assume_target_policy" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_assume_chain_target.arn}/*"]
  }
}

resource "aws_iam_role_policy" "iam_assume_target_policy" {
  name   = "infratomic-test-app-iam-assume-target-policy"
  role   = aws_iam_role.iam_assume_target.id
  policy = data.aws_iam_policy_document.iam_assume_target_policy.json
}

# Managed-policy fixture: `iam_managed_policy_principal` gets its
# `s3:GetObject` grant on `iam_managed_policy_target` via an
# `aws_iam_role_policy_attachment` to a standalone `aws_iam_policy`, not an
# inline policy.
resource "aws_s3_bucket" "iam_managed_policy_target" {
  bucket        = "infratomic-test-app-iam-managed-policy-target"
  force_destroy = true
}

data "aws_iam_policy_document" "iam_managed_policy" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_managed_policy_target.arn}/*"]
  }
}

resource "aws_iam_policy" "iam_managed_policy" {
  name   = "infratomic-test-app-iam-managed-policy"
  policy = data.aws_iam_policy_document.iam_managed_policy.json
}

resource "aws_iam_role" "iam_managed_policy_principal" {
  name               = "infratomic-test-app-iam-managed-policy-principal"
  assume_role_policy = data.aws_iam_policy_document.iam_fixture_assume_role.json
}

resource "aws_iam_role_policy_attachment" "iam_managed_policy" {
  role       = aws_iam_role.iam_managed_policy_principal.name
  policy_arn = aws_iam_policy.iam_managed_policy.arn
}

# Deny-overrides-allow fixture: an explicit `Deny` paired with an `Allow`
# for the same action/resource (blocks), alongside a second, unrelated
# `Allow`/`Deny` pair for a different action (doesn't block), all in one
# inline identity policy.
resource "aws_s3_bucket" "iam_deny_target" {
  bucket        = "infratomic-test-app-iam-deny-target"
  force_destroy = true
}

resource "aws_iam_role" "iam_deny_principal" {
  name               = "infratomic-test-app-iam-deny-principal"
  assume_role_policy = data.aws_iam_policy_document.iam_fixture_assume_role.json
}

data "aws_iam_policy_document" "iam_deny_policy" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_deny_target.arn}/*"]
  }

  statement {
    effect    = "Deny"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.iam_deny_target.arn}/*"]
  }

  statement {
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.iam_deny_target.arn}/*"]
  }

  statement {
    effect    = "Deny"
    actions   = ["s3:DeleteObject"]
    resources = ["${aws_s3_bucket.iam_deny_target.arn}/*"]
  }
}

resource "aws_iam_role_policy" "iam_deny_policy" {
  name   = "infratomic-test-app-iam-deny-policy"
  role   = aws_iam_role.iam_deny_principal.id
  policy = data.aws_iam_policy_document.iam_deny_policy.json
}
