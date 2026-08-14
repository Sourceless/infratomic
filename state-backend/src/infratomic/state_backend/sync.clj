(ns infratomic.state-backend.sync
  "Sync: discovers resources present in LocalStack's EC2/IAM APIs that
  aren't already known to the State Backend, and ingests them as
  Discovered (unmanaged) Resource entities (`:resource/managed?` `false`),
  matched against existing Resource entities by AWS resource id rather
  than Terraform `(type, name)` - see ADR-0005/0006/0007/0008/0010,
  design.md, and CONTEXT.md's \"Sync\"/\"Discovered Resource\" entries.

  Three layers, in order:

  1. An EC2 client (`ec2-client`) and an IAM client (`iam-client`), both
     pointed at LocalStack, using a JDK-native
     `java.net.http.HttpClient`-based transport rather than
     `com.cognitect.aws/api`'s own bundled Jetty-based one (see the
     `http-client` var's docstring for why: this project's own
     `ring-jetty-adapter` server pulls in a Jetty major version binary-
     incompatible with the one `com.cognitect.aws/api` bundles).
  2. Pure AWS-response -> Terraform-attribute-map translation functions,
     one per modeled resource type (`resource-schema` in `db.clj`), per
     design.md's translation table - each producing the same
     Terraform-attribute-key-shaped map `db/resource-attr-tx` already
     knows how to decompose for `POST /state`.
  3. Matching/ingestion (`sync!`): for each translated resource, look up
     whether a Resource entity already exists for its AWS id (by that
     type's modeled id ident, e.g. `:aws-security-group/id` - never by
     `:resource/id` directly, since a Terraform-managed match has a
     different id shape than a Discovered Resource's synthesized one; or,
     for `\"aws_iam_role_policy_attachment\"` - the one type with no
     AWS-assigned id of its own - a composite `(role, policy_arn)` match,
     see `composite-match`), and transact accordingly - a fresh discovery,
     an update to a previously-discovered entity, or (issue #27) a
     diff-gated update to a Terraform-managed entity whose observed live
     value has drifted out-of-band, tagging the write
     `:resource/last-write-source :sync`. A Terraform-managed match whose
     live value hasn't changed gets no write at all, except (issue #32) to
     reassert `:resource/sync-present? true` if a prior Sync pass had
     flagged it removed (`resource-tx`'s reappearance fix); a
     Terraform-managed, `sync-present-types`-covered resource stored but
     not observed this pass instead gets `:resource/sync-present? false`
     asserted (`missing-child-tx`), the removed-child drift signal
     `query.clj`'s removed-child Rule reads."
  (:require [cheshire.core :as json]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.http :as aws-http]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio ByteBuffer]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; EC2 client (ADR-0008)
;; ---------------------------------------------------------------------------

(def ^:private restricted-headers
  "HTTP headers `java.net.http.HttpRequest.Builder` refuses to set
  explicitly (it manages them itself) - dropped rather than passed
  through from `com.cognitect.aws/api`'s signed request map, which sets
  some of these itself (e.g. `host`)."
  #{"connection" "content-length" "expect" "host" "upgrade"})

(defn- request-uri
  [{:keys [scheme server-name server-port uri query-string]}]
  (str (name scheme) "://" server-name (when server-port (str ":" server-port)) uri
       (when (seq query-string) (str "?" query-string))))

(defn- body-bytes
  ^bytes [^ByteBuffer body]
  (let [arr (byte-array (.remaining body))]
    (.get body arr)
    arr))

(defn- body-publisher
  [body]
  (if body
    (HttpRequest$BodyPublishers/ofByteArray (body-bytes body))
    (HttpRequest$BodyPublishers/noBody)))

(defn- response-headers
  [^java.net.http.HttpResponse response]
  (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])) (.map (.headers response))))

(defn http-client
  "A `cognitect.aws.http/HttpClient` implementation backed by the JDK's
  own `java.net.http.HttpClient` (the same transport `cli/main.clj`
  already uses for the Policy Check request, see that namespace) rather
  than `com.cognitect.aws/api`'s own default (`cognitect.aws.http.cognitect`,
  backed by `com.cognitect/http-client`, which itself depends on
  `org.eclipse.jetty/jetty-client` 9.4.x). That default is unusable in
  this project as-is: `state-backend/deps.edn` also depends on
  `ring/ring-jetty-adapter`, which pulls in Jetty 12.x - a single Jetty
  major version wins the classpath for every `org.eclipse.jetty/*`
  artifact (they're the same Maven coordinates at different versions,
  not independently loadable), and Jetty 12's `jetty-util` no longer has
  the classes (e.g. `org.eclipse.jetty.util.log.Log`) the bundled Jetty
  9.4 `jetty-client` needs, so constructing the default http-client
  throws `ClassNotFoundException` at runtime. Passed as `ec2-client`'s
  `:http-client` option, entirely bypassing the bundled implementation -
  the `com.cognitect/http-client`/Jetty-9 dependency is never loaded."
  []
  (let [client (HttpClient/newHttpClient)]
    (reify aws-http/HttpClient
      (-submit [_ request channel]
        (a/thread
          (try
            (let [{:keys [request-method headers body timeout-msec]} request
                  builder (HttpRequest/newBuilder (URI/create (request-uri request)))]
              (doseq [[k v] headers]
                (when-not (contains? restricted-headers (str/lower-case k))
                  (.header builder k v)))
              (when timeout-msec
                (.timeout builder (Duration/ofMillis timeout-msec)))
              (.method builder (str/upper-case (name request-method)) (body-publisher body))
              (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofByteArray))]
                (a/put! channel
                        {:status  (.statusCode response)
                         :body    (ByteBuffer/wrap (.body response))
                         :headers (response-headers response)})))
            (catch Exception e
              (a/put! channel {:cognitect.anomalies/category :cognitect.anomalies/fault
                                :cognitect.anomalies/message  (.getMessage e)}))))
        channel)
      (-stop [_] nil))))

(defn ec2-client
  "Build an EC2 client pointed at LocalStack (ADR-0008): `:endpoint-override`
  targets `localhost:4566`, static test credentials (LocalStack accepts
  any non-empty ones), an explicit `:region` (so building the client never
  touches `com.cognitect.aws/api`'s shared/default region-provider or
  http-client, only the ones given here - see `http-client`'s docstring)."
  []
  (aws/client {:api                  :ec2
               :region               "us-east-1"
               :http-client          (http-client)
               :endpoint-override    {:protocol :http :hostname "localhost" :port 4566}
               :credentials-provider (credentials/basic-credentials-provider
                                      {:access-key-id "test" :secret-access-key "test"})}))

(defn iam-client
  "Build an IAM client pointed at LocalStack, identical in shape to
  `ec2-client` (`:api :iam` in place of `:api :ec2`, same `http-client`/
  `:endpoint-override`/credentials) - built fresh inside `describe-all`'s
  IAM fetch step rather than threaded through as an extra parameter (see
  that step's docstring)."
  []
  (aws/client {:api                  :iam
               :region               "us-east-1"
               :http-client          (http-client)
               :endpoint-override    {:protocol :http :hostname "localhost" :port 4566}
               :credentials-provider (credentials/basic-credentials-provider
                                      {:access-key-id "test" :secret-access-key "test"})}))

;; ---------------------------------------------------------------------------
;; AWS response -> Terraform attribute-map translation (design.md's table)
;; ---------------------------------------------------------------------------

(defn security-group->attrs
  [sg]
  {"id"     (:GroupId sg)
   "vpc_id" (:VpcId sg)})

(defn security-group-rule->attrs
  "Translates one `DescribeSecurityGroupRules` entry. `cidr_blocks` is
  present only when the rule has a CIDR (`CidrIpv4`); `source_security_group_id`
  only when it references another security group (`ReferencedGroupInfo`) -
  a rule has exactly one or the other, matching Terraform's own
  `aws_security_group_rule` shape (decompose-attributes skips absent/nil
  keys, so omitting rather than nil-ing the other is equivalent)."
  [rule]
  (cond-> {"id"                 (:SecurityGroupRuleId rule)
           "from_port"          (:FromPort rule)
           "to_port"            (:ToPort rule)
           "protocol"           (:IpProtocol rule)
           "security_group_id"  (:GroupId rule)
           "type"               (if (:IsEgress rule) "egress" "ingress")}
    (:CidrIpv4 rule)
    (assoc "cidr_blocks" [(:CidrIpv4 rule)])

    (get-in rule [:ReferencedGroupInfo :GroupId])
    (assoc "source_security_group_id" (get-in rule [:ReferencedGroupInfo :GroupId]))))

(defn vpc->attrs
  [vpc]
  {"id"         (:VpcId vpc)
   "cidr_block" (:CidrBlock vpc)})

(defn subnet->attrs
  [subnet]
  {"id"         (:SubnetId subnet)
   "vpc_id"     (:VpcId subnet)
   "cidr_block" (:CidrBlock subnet)})

(defn route-table->attrs
  [rt]
  {"id"     (:RouteTableId rt)
   "vpc_id" (:VpcId rt)})

(defn- explicit-route?
  "Only `\"CreateRoute\"`-origin routes are explicit, user-created routes -
  the implicit local route every route table gets (`\"CreateRouteTable\"`
  origin, `GatewayId` `\"local\"`) has no Terraform `aws_route` resource
  counterpart (Terraform never creates or imports it), so it's excluded
  rather than ingested as a phantom Discovered Resource."
  [route]
  (= "CreateRoute" (:Origin route)))

(defn route->attrs
  "`route-table-id` is the owning route table's `RouteTableId`, passed in
  separately since a route entry itself carries no reference back to its
  table. `id` is synthesized as `\"<route_table_id>-<destination_cidr_block>\"`
  (design.md) - a route has no AWS-assigned id of its own."
  [route-table-id route]
  {"id"                        (str route-table-id "-" (:DestinationCidrBlock route))
   "route_table_id"            route-table-id
   "destination_cidr_block"    (:DestinationCidrBlock route)
   "gateway_id"                (:GatewayId route)
   "vpc_peering_connection_id" (:VpcPeeringConnectionId route)})

(defn- subnet-association?
  "Only associations naming a `SubnetId` are Terraform's
  `aws_route_table_association` shape - the implicit main-route-table
  association (`:Main true`, no `SubnetId`) has no Terraform resource
  counterpart, matching `explicit-route?`'s reasoning for the implicit
  local route."
  [assoc]
  (some? (:SubnetId assoc)))

(defn route-table-association->attrs
  [assoc]
  {"id"             (:RouteTableAssociationId assoc)
   "subnet_id"      (:SubnetId assoc)
   "route_table_id" (:RouteTableId assoc)})

(defn internet-gateway->attrs
  [igw]
  {"id"     (:InternetGatewayId igw)
   "vpc_id" (get-in igw [:Attachments 0 :VpcId])})

(defn vpc-peering-connection->attrs
  [pcx]
  {"id"          (:VpcPeeringConnectionId pcx)
   "vpc_id"      (get-in pcx [:RequesterVpcInfo :VpcId])
   "peer_vpc_id" (get-in pcx [:AccepterVpcInfo :VpcId])})

(defn- live-instance?
  "Excludes `\"terminated\"` instances - LocalStack (like real AWS) keeps
  terminated instances visible in `DescribeInstances` indefinitely, but a
  terminated instance isn't deployed infrastructure (the resource-sync
  capability's Purpose is discovering what's actually running), so
  ingesting one would create a permanent phantom Discovered Resource."
  [instance]
  (not= "terminated" (get-in instance [:State :Name])))

(defn instance->attrs
  [instance]
  {"id"                     (:InstanceId instance)
   "subnet_id"              (:SubnetId instance)
   "vpc_security_group_ids" (mapv :GroupId (:SecurityGroups instance))})

(defn iam-role-policy-attachment->attrs
  "Translates one `ListAttachedRolePolicies` `AttachedPolicies[]` entry for
  `role-name`. Deliberately has no `\"id\"` key - unlike every other
  modeled type, `aws_iam_role_policy_attachment` has no AWS-assigned id of
  its own (Terraform's own provider models none either); Sync matches and
  identifies it by the `(role, policy_arn)` pair instead (see
  `existing-match`/`discovered-resource-id`)."
  [role-name entry]
  {"role" role-name "policy_arn" (:PolicyArn entry)})

;; ---------------------------------------------------------------------------
;; Fetch: one Describe* call per modeled type, translated
;; ---------------------------------------------------------------------------

(defn- invoke!
  "`aws/invoke`, throwing on an anomaly response (a failed AWS/LocalStack
  call) rather than silently treating it as an empty result."
  ([client op] (invoke! client op {}))
  ([client op request]
   (let [response (aws/invoke client (cond-> {:op op} (seq request) (assoc :request request)))]
     (if (:cognitect.anomalies/category response)
       (throw (ex-info (str "EC2 " (name op) " failed") {:op op :response response}))
       response))))

(defn- invoke-paginated!
  "Like `invoke!`, but follows EC2's `NextToken` pagination cursor until
  exhausted, concatenating `result-key`'s value across every page, rather
  than silently returning only the first page's worth. Every EC2 Describe*
  call this namespace makes pages (confirmed empirically against a
  LocalStack instance whose accumulated resource count, from repeated
  Sync/test runs, exceeded its default page size of 100) - an unpaginated
  single call can silently omit a resource that happens to fall beyond the
  first page, with no error, no anomaly, nothing to indicate data is
  missing."
  ([client op result-key] (invoke-paginated! client op result-key {}))
  ([client op result-key request]
   (loop [token nil acc []]
     (let [response (invoke! client op (cond-> request token (assoc :NextToken token)))
           acc'     (into acc (get response result-key))]
       (if-let [next-token (:NextToken response)]
         (recur next-token acc')
         acc')))))

(defn- security-groups
  [client]
  (invoke-paginated! client :DescribeSecurityGroups :SecurityGroups))

(defn- security-group-rules
  "`DescribeSecurityGroupRules` called once per known security group id,
  filtered by `group-id` - called with no filter at all, LocalStack
  Community returns an empty list regardless of what rules actually exist
  (confirmed empirically against a running LocalStack instance), even
  though the real AWS API (and LocalStack's own per-group-filtered
  response) returns them correctly when a `group-id` filter is given."
  [client sg-ids]
  (mapcat (fn [sg-id]
             (invoke-paginated! client :DescribeSecurityGroupRules :SecurityGroupRules
                                 {:Filters [{:Name "group-id" :Values [sg-id]}]}))
          sg-ids))

(defn- vpcs [client] (invoke-paginated! client :DescribeVpcs :Vpcs))
(defn- subnets [client] (invoke-paginated! client :DescribeSubnets :Subnets))
(defn- route-tables [client] (invoke-paginated! client :DescribeRouteTables :RouteTables))
(defn- internet-gateways [client] (invoke-paginated! client :DescribeInternetGateways :InternetGateways))
(defn- vpc-peering-connections [client] (invoke-paginated! client :DescribeVpcPeeringConnections :VpcPeeringConnections))

(defn- instances
  [client]
  (mapcat :Instances (invoke-paginated! client :DescribeInstances :Reservations)))

(defn- discovered
  "A `{:type <terraform-type-string> :attributes <attrs-map>}` record for
  one translated AWS resource - the unit `resource-tx`/`sync!` operate on."
  [type attrs]
  {:type type :attributes attrs})

(defn- iam-role-names
  "Every `aws_iam_role` Resource entity's `:aws-iam-role/name` currently in
  `db` - the roles Sync is allowed to fetch policy attachments for (see
  `iam-role-policy-attachments`'s docstring)."
  [db]
  (map first (d/q '[:find ?name :where [_ :aws-iam-role/name ?name]] db)))

(defn- attached-role-policies
  "`ListAttachedRolePolicies`, following IAM's own pagination cursor shape
  (`Marker`/`IsTruncated` - distinct from EC2's `NextToken`, see
  `invoke-paginated!`) until exhausted, for the same reason: an
  unpaginated single call can silently omit an attachment beyond the
  first page."
  [client role-name]
  (loop [marker nil acc []]
    (let [response (invoke! client :ListAttachedRolePolicies
                             (cond-> {:RoleName role-name} marker (assoc :Marker marker)))
          acc'     (into acc (:AttachedPolicies response))]
      (if (:IsTruncated response)
        (recur (:Marker response) acc')
        acc'))))

(defn- iam-role-policy-attachments
  "`ListAttachedRolePolicies`, called once per already-known Terraform-
  managed `aws_iam_role` (`iam-role-names`, read from `db`) - never a
  blanket `ListRoles`/unfiltered scan, matching `security-group-rules`'s
  per-known-id-filtered pattern (this codebase's `resource-sync` spec
  scopes IAM role policy attachment discovery to roles Sync already knows
  about via prior Terraform applies)."
  [client db]
  (mapcat (fn [role-name]
             (map #(discovered "aws_iam_role_policy_attachment"
                                (iam-role-policy-attachment->attrs role-name %))
                  (attached-role-policies client role-name)))
          (iam-role-names db)))

(defn describe-all
  "Calls every `Describe*` API for every resource type modeled in
  `db/resource-schema` (ADR-0007) against `client` (an EC2 client), plus
  `ListAttachedRolePolicies` against a fresh IAM client (`iam-client`) for
  every `aws_iam_role` `db` already knows about, translating each result
  into `discovered` records. Impure (network calls); pure translation
  happens in the functions above. Takes `db` (in addition to `client`)
  since the IAM step needs to enumerate already-known managed roles - see
  `iam-role-policy-attachments`."
  [client db]
  (let [sgs (security-groups client)]
    (concat
     (map #(discovered "aws_security_group" (security-group->attrs %)) sgs)
     (map #(discovered "aws_security_group_rule" (security-group-rule->attrs %))
          (security-group-rules client (map :GroupId sgs)))
     (map #(discovered "aws_vpc" (vpc->attrs %)) (vpcs client))
     (map #(discovered "aws_subnet" (subnet->attrs %)) (subnets client))
     (let [rts (route-tables client)]
       (concat
        (map #(discovered "aws_route_table" (route-table->attrs %)) rts)
        (mapcat (fn [rt]
                   (map #(discovered "aws_route" (route->attrs (:RouteTableId rt) %))
                        (filter explicit-route? (:Routes rt))))
                rts)
        (mapcat (fn [rt]
                   (map #(discovered "aws_route_table_association" (route-table-association->attrs %))
                        (filter subnet-association? (:Associations rt))))
                rts)))
     (map #(discovered "aws_internet_gateway" (internet-gateway->attrs %)) (internet-gateways client))
     (map #(discovered "aws_vpc_peering_connection" (vpc-peering-connection->attrs %)) (vpc-peering-connections client))
     (map #(discovered "aws_instance" (instance->attrs %)) (filter live-instance? (instances client)))
     (iam-role-policy-attachments (iam-client) db))))

;; ---------------------------------------------------------------------------
;; Matching and ingestion (ADR-0005/0006, design.md's "Resource matching")
;; ---------------------------------------------------------------------------

(defn- id-ident
  "The modeled `:db/ident` for `type`'s `\"id\"` attribute (e.g.
  `:aws-security-group/id` for `\"aws_security_group\"`), or `nil` if
  `type` has no modeled `\"id\"` entry in `db/resource-schema`."
  [type]
  (get-in db/resource-schema [type "id" :ident]))

(def ^:private sync-present-types
  "The four FK-bearing child types the removed-child drift mechanism
  covers (design.md's `child-parent-joins`, minus the second
  `aws_route_table_association` FK entry, which is the same type) - the
  only types `:resource/sync-present?` is ever written for.

  Known pre-existing limitation (#26/#27, not introduced by this
  mechanism - confirmed against a real LocalStack instance while
  developing `sync_integration_test.clj`'s coverage of this feature):
  `existing-match`'s id-based lookup can never actually match a
  Terraform-managed `aws_security_group_rule` or `aws_route` instance to
  itself. For `aws_route`, this was already known (Sync synthesizes its
  own `\"<route_table_id>-<destination_cidr_block>\"` id, distinct from
  Terraform's own internal route id - see
  `sync_integration_test.clj`'s `drift-sample-app-instance!` docstring).
  For `aws_security_group_rule`, `resource-schema`'s `\"id\"` entry is
  written by both `POST /state` (Terraform's own synthetic rule id, e.g.
  `\"sgrule-<n>\"`) and Sync (AWS's real `SecurityGroupRuleId`, per
  ADR-0006) - two different id spaces sharing one modeled ident, so a
  Terraform-managed rule's stored id is never the one Sync observes
  either. In both cases, `missing-child-tx` ends up asserting
  `:resource/sync-present? false` on every Terraform-managed instance of
  these two types on every Sync pass, regardless of whether it was
  actually removed - a false-positive source this mechanism inherits
  rather than introduces. `aws_route_table_association` and
  `aws_iam_role_policy_attachment` (composite-matched) are unaffected."
  #{"aws_security_group_rule" "aws_route" "aws_route_table_association" "aws_iam_role_policy_attachment"})

(defn- resource-key
  "The value that identifies one observed AWS resource of `type` within
  `attributes`: its `\"id\"` for an id-based type, or the `[role
  policy_arn]` pair for `\"aws_iam_role_policy_attachment\"` (mirroring
  `db/managed-resource-ids-by-type`'s key shape for that type, so the two
  are directly comparable when diffing observed vs. stored ids)."
  [type attributes]
  (if (= type "aws_iam_role_policy_attachment")
    [(get attributes "role") (get attributes "policy_arn")]
    (get attributes "id")))

(defn- composite-match
  "The existing Resource entity (if any) whose
  `:aws-iam-role-policy-attachment/role` and
  `:aws-iam-role-policy-attachment/policy-arn` equal `role`/`policy-arn`
  together - neither alone uniquely identifies an attachment (a role can
  have many attachments; a policy can be attached to many roles), unlike
  every other FK-bearing child type's single AWS-assigned id."
  [db role policy-arn]
  (when-let [eid (ffirst (d/q '[:find ?e
                                 :in $ ?role ?arn
                                 :where
                                 [?e :aws-iam-role-policy-attachment/role ?role]
                                 [?e :aws-iam-role-policy-attachment/policy-arn ?arn]]
                               db role policy-arn))]
    (d/pull db [:db/id :resource/id :resource/managed? :resource/sync-present?] eid)))

(defn- existing-match
  "The existing Resource entity (if any), `{:db/id ... :resource/id ...
  :resource/managed? ... :resource/sync-present? ...}`, matching this
  observed AWS resource: for `\"aws_iam_role_policy_attachment\"`, a
  composite `(role, policy_arn)` lookup (`composite-match`, since this
  type has no modeled `\"id\"` of its own - see `id-ident`); for every
  other type, a Datalog query on the modeled id ident (e.g.
  `:aws-security-group/id ?aws-id`), not a `:resource/id` guess, since a
  Terraform-managed match's `:resource/id` is `\"<type>.<name>\"`, not the
  synthesized discovered-resource shape. `:db/id` is included so the
  Terraform-managed branch can reconstruct the match's currently stored
  attributes for the drift diff without a second lookup query."
  [db type aws-id attributes]
  (if (= type "aws_iam_role_policy_attachment")
    (composite-match db (get attributes "role") (get attributes "policy_arn"))
    (when-let [ident (id-ident type)]
      (when-let [eid (ffirst (d/q '[:find ?e :in $ ?ident ?v :where [?e ?ident ?v]] db ident aws-id))]
        (d/pull db [:db/id :resource/id :resource/managed? :resource/sync-present?] eid)))))

(defn- discovered-resource-id
  "Synthesizes a `:resource/id` for a fresh Discovered Resource of `type`:
  `\"aws_iam_role_policy_attachment.discovered-<role>-<policy_arn>\"` for
  that composite-keyed type (both values are already valid ARN/name
  strings with no embedded ambiguity), `\"<type>.discovered-<aws-id>\"`
  for every other type."
  [type aws-id attributes]
  (if (= type "aws_iam_role_policy_attachment")
    (str type ".discovered-" (get attributes "role") "-" (get attributes "policy_arn"))
    (str type ".discovered-" aws-id)))

(defn resource-tx
  "The ingestion decision (design.md's \"Resource matching\") for one
  translated AWS resource: no existing match -> a fresh Discovered
  Resource tx-map keyed by a synthesized `:resource/id`; an existing
  match that's already a Discovered Resource -> the same tx-map, but
  keyed by *that* entity's `:resource/id` (an in-place upsert, plus the
  same stale-attribute retractions `POST /state`'s `resource->tx` uses,
  so a shrunk cardinality-many attribute doesn't just accumulate stale
  datoms); an existing match that's Terraform-managed -> diff its
  currently stored attributes (reconstructed via `db/stored-attributes`)
  against the freshly observed live `attributes`, both narrowed to
  `type`'s modeled keys via `db/comparable-attributes` (Sync only ever
  observes modeled attributes - comparing the resource's *other*,
  unmodeled attributes, e.g. `tags` set only via `POST /state`, would
  falsely read as drift): identical (and, for a `sync-present-types`
  type, the match's `:resource/sync-present?` isn't currently `false`) ->
  no tx-data, `:skipped-already-managed`; identical but the match's
  `:resource/sync-present?` is `false` (issue #32's reappearance fix: a
  previously removed-child-flagged resource reappeared with unchanged
  attributes) -> a forced write reasserting `:resource/sync-present?
  true` (still `:skipped-already-managed` - no new outcome kind, the
  marker would otherwise never clear); different -> the same
  upsert-in-place tx-data an already-discovered match gets (plus
  `:resource/sync-present? true` for a `sync-present-types` type),
  tagging `:resource/last-write-source :sync`, outcome `:drifted`. Every
  tx-map this fn produces sets `:resource/last-write-source :sync` - the
  only write path (besides `POST /state`) a Resource entity ever goes
  through. Returns `{:tx-data [...] :outcome (:discovered :updated
  :drifted :skipped-already-managed)}`."
  [db type aws-id attributes]
  (let [match (existing-match db type aws-id attributes)]
    (cond
      (nil? match)
      {:tx-data [(merge {:resource/id                 (discovered-resource-id type aws-id attributes)
                          :resource/type               type
                          :resource/managed?           false
                          :resource/last-write-source  :sync}
                         (db/resource-attr-tx type attributes))]
       :outcome :discovered}

      (false? (:resource/managed? match))
      (let [id (:resource/id match)]
        {:tx-data (into [(merge {:resource/id                 id
                                  :resource/type               type
                                  :resource/managed?           false
                                  :resource/last-write-source  :sync}
                                 (db/resource-attr-tx type attributes))]
                         (db/resource-upsert-retractions db id type attributes))
         :outcome :updated})

      :else
      (let [id          (:resource/id match)
            eid         (:db/id match)
            stored      (db/stored-attributes db eid type)
            unchanged?  (= (db/comparable-attributes type stored) (db/comparable-attributes type attributes))
            covered?    (contains? sync-present-types type)
            marker      (when covered? {:resource/sync-present? true})
            reappeared? (and covered? (false? (:resource/sync-present? match)))]
        (cond
          (and unchanged? (not reappeared?))
          {:tx-data [] :outcome :skipped-already-managed}

          unchanged?
          {:tx-data [(merge {:resource/id id :resource/last-write-source :sync} marker)]
           :outcome :skipped-already-managed}

          :else
          {:tx-data (into [(merge {:resource/id                 id
                                    :resource/last-write-source  :sync}
                                   marker
                                   (db/resource-attr-tx type attributes))]
                           (db/resource-upsert-retractions db id type attributes))
           :outcome :drifted})))))

;; ---------------------------------------------------------------------------
;; Full Sync pass
;; ---------------------------------------------------------------------------

(defn- summary-entry
  [{:keys [type attributes]}]
  {:type type
   :id   (if (= type "aws_iam_role_policy_attachment")
           (str (get attributes "role") "-" (get attributes "policy_arn"))
           (get attributes "id"))})

(defn- observed-keys-by-type
  [type resources]
  (into #{}
        (comp (filter #(= type (:type %)))
              (map #(resource-key type (:attributes %))))
        resources))

(defn- missing-child-tx
  "Tx-data asserting `:resource/sync-present? false` on every
  Terraform-managed resource of covered child `type` that's currently
  stored (`db/managed-resource-ids-by-type`) but wasn't among this Sync
  pass's observed ids for that type (`observed-keys`) - the removed-child
  half of issue #32's presence marker (design.md's \"removed-child
  detection\" decision). The reciprocal `true` assertion for an observed,
  matched resource happens per-resource, inside `resource-tx` itself (see
  that fn's docstring) - this step only ever needs to mark absence, since
  a resource `resource-tx` never saw this run was, by definition, not
  observed."
  [db type observed-keys]
  (into []
        (keep (fn [[k eid]]
                (when-not (contains? observed-keys k)
                  [:db/add eid :resource/sync-present? false])))
        (db/managed-resource-ids-by-type db type)))

(defn sync!
  "The full Sync pass (design.md's \"POST /sync endpoint shape\"): fetches
  and translates every modeled type from LocalStack (`describe-all`),
  decides each one's ingestion outcome against a single db snapshot
  (`resource-tx`), transacts every resulting tx-data plus the
  removed-child presence-marker tx-data (`missing-child-tx`, one per
  `sync-present-types` type) in one transaction. Returns a summary map:
  `{:discovered [{:type :id} ...] :updated [{:type :id} ...] :drifted
  [{:type :id} ...] :skipped-already-managed <count>}`. `:drifted` lists
  each Terraform-managed resource whose observed live value differed from
  what was stored (and so was just updated, tagged `:sync`) - distinct
  from `:updated`, which is a previously-discovered, non-Terraform-managed
  resource that received new values."
  [conn client]
  (let [db         (d/db conn)
        resources  (describe-all client db)
        decisions  (map (fn [{:keys [type attributes] :as r}]
                           (assoc (resource-tx db type (get attributes "id") attributes)
                                  :entry (summary-entry r)))
                         resources)
        missing-tx (mapcat (fn [type] (missing-child-tx db type (observed-keys-by-type type resources)))
                            sync-present-types)
        tx-data    (into (into [] (mapcat :tx-data) decisions) missing-tx)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    {:discovered              (mapv :entry (filter #(= :discovered (:outcome %)) decisions))
     :updated                 (mapv :entry (filter #(= :updated (:outcome %)) decisions))
     :drifted                 (mapv :entry (filter #(= :drifted (:outcome %)) decisions))
     :skipped-already-managed (count (filter #(= :skipped-already-managed (:outcome %)) decisions))}))

;; ---------------------------------------------------------------------------
;; HTTP handler
;; ---------------------------------------------------------------------------

(defn- entry->json
  [{:keys [type id]}]
  {"type" type "id" id})

(defn- summary->json
  [{:keys [discovered updated drifted skipped-already-managed]}]
  {"discovered"               (mapv entry->json discovered)
   "updated"                  (mapv entry->json updated)
   "drifted"                  (mapv entry->json drifted)
   "skipped_already_managed"  skipped-already-managed})

(defn sync-endpoint
  "Handle a `POST /sync` request: run the full Sync pass (`sync!`) against
  `conn`/`client`, and respond `200` with the JSON-encoded summary
  (design.md's \"POST /sync endpoint shape\") - `{\"discovered\": [{\"type\"
  ... \"id\" ...} ...], \"updated\": [...], \"drifted\": [...],
  \"skipped_already_managed\": N}`. Takes no request body - Sync's inputs
  are \"whatever LocalStack currently has\", not a client-supplied
  document."
  [conn client]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string (summary->json (sync! conn client)))})
