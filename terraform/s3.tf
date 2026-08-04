# S3 bucket that the test app's Lambda handler uploads files into.
resource "aws_s3_bucket" "uploads" {
  bucket = "infratomic-test-app-uploads"
}
