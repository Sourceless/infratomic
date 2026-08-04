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
