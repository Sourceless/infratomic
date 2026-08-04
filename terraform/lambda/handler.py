"""Minimal upload handler for the LocalStack test app.

Invoked via a Lambda Function URL. Accepts an HTTP request (any method) with
the file content as the request body and writes that content as a new
object into the S3 bucket named by the BUCKET_NAME environment variable.

The object key can be supplied via the `key` query-string parameter; if
omitted, a key is generated from the request ID so repeated uploads don't
collide.
"""

import base64
import os

import boto3

s3 = boto3.client("s3")

BUCKET_NAME = os.environ["BUCKET_NAME"]


def handler(event, context):
    query_params = event.get("queryStringParameters") or {}
    key = query_params.get("key") or f"upload-{context.aws_request_id}"

    body = event.get("body") or ""
    if event.get("isBase64Encoded"):
        content = base64.b64decode(body)
    else:
        content = body.encode("utf-8")

    s3.put_object(Bucket=BUCKET_NAME, Key=key, Body=content)

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": f'{{"bucket": "{BUCKET_NAME}", "key": "{key}"}}',
    }
