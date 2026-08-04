output "bucket_name" {
  description = "Name of the S3 bucket the test app uploads files into."
  value       = aws_s3_bucket.uploads.bucket
}

output "function_url" {
  description = "HTTP endpoint for the upload Lambda (Lambda Function URL)."
  value       = aws_lambda_function_url.upload_handler.function_url
}
