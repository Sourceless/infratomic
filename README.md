# infratomic

The logic-powered infrastructure database

## Local AWS test app (LocalStack + Terraform)

`terraform/` provisions a small, real Lambda-backed upload app — an S3
bucket, a minimal IAM execution role, and a Lambda function exposed via a
Lambda Function URL — against a local AWS simulator, so infra changes can
be exercised without any real AWS account or credentials.

### Bring up the stack

```sh
docker compose up -d          # start LocalStack (gateway on localhost:4566)
cd terraform
terraform init
terraform apply
```

`terraform apply` provisions the S3 bucket, IAM role, Lambda function, and
Lambda Function URL against the LocalStack endpoint. On success it prints
`bucket_name` and `function_url` outputs.

### Verifying it worked

There are two verification paths, depending on whether you have the AWS
CLI installed.

**Primary path (requires `awscli`):**

```sh
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url=http://localhost:4566 s3 ls
```

This should list the provisioned bucket (`infratomic-test-app-uploads`).

**Fallback path (no `awscli` required):**

```sh
terraform state list       # shows the bucket, role, Lambda function, and function URL resources
terraform output           # shows bucket_name and function_url

FUNCTION_URL=$(terraform output -raw function_url)
curl -X POST "${FUNCTION_URL}?key=hello.txt" \
  -H "Content-Type: text/plain" \
  --data-binary "hello from curl"
```

A `200` response with a JSON body echoing the bucket and key means the
Lambda wrote the payload into S3. If `awscli` happens to be available you
can then confirm the object landed with
`aws --endpoint-url=http://localhost:4566 s3 cp s3://infratomic-test-app-uploads/hello.txt -`;
otherwise, `terraform state list` / `terraform output` plus the `200`
response from `curl` are sufficient confirmation that the end-to-end path
works.

Note: use `--data-binary` with an explicit `Content-Type` (as above) rather
than curl's `-d`/`--data`, which defaults to
`application/x-www-form-urlencoded` and gets re-encoded (spaces become
`+`) before it reaches the Lambda.

### Notes

- This targets LocalStack Community edition only — no real AWS credentials,
  accounts, or endpoints are involved (see `terraform/provider.tf`).
- The IAM role in `terraform/iam.tf` is a bare Lambda assume-role
  boilerplate needed to satisfy the Lambda `create-function` API; LocalStack
  Community doesn't enforce IAM policy, so no broader IAM design is in
  scope.
- Out of scope for this stack: real AWS deployment, CI integration, and AWS
  services beyond S3 and the minimal Lambda + IAM role (no DynamoDB,
  ECS/EKS/Batch, or API Gateway).
