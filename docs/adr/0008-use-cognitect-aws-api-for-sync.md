# Use `com.cognitect.aws/api` to talk to LocalStack's EC2 API, not `awscli`/`awslocal`

Sync (issue #26) needs to call LocalStack's EC2 API (`DescribeSecurityGroups`,
`DescribeSecurityGroupRules`, and the equivalent `Describe*` calls for every
type ADR-0007 lists) from inside the State Backend process. The two real
options were shelling out to `awscli`/`awslocal` as a subprocess, or adding a
native Clojure AWS SDK dependency.

We chose `com.cognitect.aws/api` + `com.cognitect.aws/endpoints` +
`com.cognitect.aws/ec2`, pointed at LocalStack via `:endpoint-override
{:protocol :http :hostname "localhost" :port 4566}` with a static credentials
provider. This keeps Sync a JVM-native, in-process call — no dependency on an
external binary being installed and on `PATH`, no subprocess-output parsing,
and results come back as Clojure data ready for `db/resource-attr-tx`
translation, consistent with how the rest of the State Backend already talks
to Datomic in-process rather than shelling out.

Considered and rejected: shelling out to `awscli`/`awslocal`. The repo's own
`README.md` already treats the AWS CLI as optional/best-effort for
verification, not a hard runtime dependency, and Sync running as a State
Backend endpoint (not a one-off script) makes an unconditional binary
dependency a worse fit than it would be for occasional manual verification.

Trade-off accepted: adds a first real AWS SDK dependency to
`state-backend/deps.edn` (previously none) — a JVM dependency, not
lock-in to a vendor beyond what talking to AWS/LocalStack already implies.
