# S3 bucket that the test app's Lambda handler uploads files into.
resource "aws_s3_bucket" "uploads" {
  bucket = "infratomic-test-app-uploads"

  # Safe here: this is a local, ephemeral, no-real-data LocalStack test
  # bucket that gets created/destroyed repeatedly by local developers.
  # Without this, `terraform destroy` fails with BucketNotEmpty after
  # following the README's own documented curl upload verification step.
  force_destroy = true
}
