# Zips the handler source on demand, keyed on its content hash so
# `terraform apply` automatically rebuilds and redeploys the Lambda when
# handler.py changes, with no separate build step to remember.
data "archive_file" "upload_handler" {
  type        = "zip"
  source_file = "${path.module}/lambda/handler.py"
  output_path = "${path.module}/lambda/handler.zip"
}

resource "aws_lambda_function" "upload_handler" {
  function_name = "infratomic-test-app-upload-handler"

  filename         = data.archive_file.upload_handler.output_path
  source_code_hash = data.archive_file.upload_handler.output_base64sha256

  role    = aws_iam_role.lambda_exec.arn
  handler = "handler.handler"
  runtime = "python3.12"
  timeout = 10

  environment {
    variables = {
      BUCKET_NAME = aws_s3_bucket.uploads.bucket
    }
  }
}

# Exposes the function directly over HTTP with no API Gateway in front of
# it, per the change's IAM/API-Gateway scoping decision.
resource "aws_lambda_function_url" "upload_handler" {
  function_name      = aws_lambda_function.upload_handler.function_name
  authorization_type = "NONE"
}
