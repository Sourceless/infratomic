(ns infratomic.state-backend.query
  "Query functions over the State Backend's Datomic database, proving that
  decomposing resource attributes into real datoms (see
  `infratomic.state-backend.db`) makes infrastructure questions answerable
  as structural Datalog queries rather than JSON-blob scans. Each function
  takes a `db` value (a `datomic.client.api` db, as returned by `(d/db
  conn)`), so it composes with both a live connection and an isolated
  in-memory db in tests - no HTTP layer involved, functions only, called
  from tests, per the issue's scope."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [infratomic.state-backend.datomic :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.validator :as validator]))

(def ^:private resource-summary-pattern
  [:resource/id :resource/type])

(defn all-deployed-resources
  "Every currently deployed resource, each identified by at least its
  `:resource/id` and `:resource/type` - matching what `terraform state
  list` would show."
  [db]
  (map #(d/pull db resource-summary-pattern %) (db/all-resource-eids db)))

(defn resources-by-type
  "Currently deployed resources of Terraform resource type `type` (e.g.
  \"aws_security_group\"). Empty if no deployed resource has that type."
  [db type]
  (map #(d/pull db resource-summary-pattern %)
       (map first
            (d/q '[:find ?e
                   :in $ ?type
                   :where [?e :resource/type ?type]]
                 db type))))

(defn- candidate-values
  "Alternate representations of a by-attribute-value query's `value`, so a
  modeled numeric attribute stored generically elsewhere as a stringified
  number doesn't subtly mismatch the query's input type: a numeric-looking
  string also tries its parsed long, and a number also tries its string
  form."
  [value]
  (cond
    (string? value)
    (into #{value} (when-some [n (parse-long value)] [n]))

    (number? value)
    #{value (str value)}

    :else
    #{value}))

(defn- modeled-matches
  [db key candidates]
  (let [idents (db/idents-for-key key)]
    (if (empty? idents)
      #{}
      (into #{}
            (mapcat (fn [v]
                      (map first
                           (d/q '[:find ?e
                                  :in $ [?ident ...] ?v
                                  :where [?e ?ident ?v]]
                                db (vec idents) v))))
            candidates))))

(defn- generic-matches
  [db key candidates]
  (into #{}
        (mapcat (fn [v]
                  (map first
                       (d/q '[:find ?e
                              :in $ ?key ?value
                              :where
                              [?a :resource.attribute/key ?key]
                              [?a :resource.attribute/value ?value]
                              [?e :resource/attribute ?a]]
                            db key (json/generate-string v)))))
        candidates))

(defn resources-by-attribute-value
  "Every currently deployed resource with attribute `key` set to `value`,
  searching both generic key/value attributes and modeled/typed attributes
  so the search is unified regardless of how a given attribute happens to
  be stored. `value` is matched against both its given representation and
  an equivalent alternate (e.g. a numeric string also matches the parsed
  number, and vice versa)."
  [db key value]
  (let [candidates (candidate-values value)
        eids       (into (modeled-matches db key candidates)
                          (generic-matches db key candidates))]
    (map #(d/pull db resource-summary-pattern %) eids)))

;; ---------------------------------------------------------------------------
;; Drift detection (issue #27)
;; ---------------------------------------------------------------------------

(defn- sync-sourced-managed-resources
  "`[eid type]` pairs for every managed (`:resource/managed? true`)
  resource whose current `:resource/last-write-source` is `:sync` - the
  only resources that can possibly be drifted, since a resource whose
  most recent write came from Terraform is by definition not drifted from
  Terraform's own last-asserted value."
  [db]
  (d/q '[:find ?e ?type
         :where
         [?e :resource/managed? true]
         [?e :resource/last-write-source :sync]
         [?e :resource/type ?type]]
       db))

(defn- last-terraform-write-tx
  "The most recent transaction id in which `eid`'s
  `:resource/last-write-source` was *asserted* `:terraform`, or `nil` if
  it never was (a resource Sync discovered directly has no such
  transaction, and is never a candidate here anyway - see
  `sync-sourced-managed-resources`, which only considers already-managed
  resources). The 5-tuple pattern's trailing `true` is required - without
  it, a history-db query matches both the assertion *and* the later
  retraction of the same `[e a v]` (a cardinality-one attribute's history
  retains both when a new value supersedes it), so an unfiltered `(max
  ?tx)` would pick up the retraction's (later) transaction instead of the
  assertion's."
  [db eid]
  (ffirst (d/q '[:find (max ?tx)
                 :in $ ?e
                 :where
                 [?e :resource/last-write-source :terraform ?tx true]]
               (d/history db) eid)))

;; ---------------------------------------------------------------------------
;; New-child / removed-child drift (issue #32): a single data-driven
;; child/parent join table, generalizing security-groups-with-port-22-open's
;; single hard-coded join across every FK-bearing child type this system
;; models, driving both directions of detection.
;; ---------------------------------------------------------------------------

(def child-parent-joins
  "Every FK-bearing child/parent relationship the new-child and
  removed-child drift Rules traverse (design.md's \"a single data-driven
  child/parent join table drives both new- and removed-child detection\"
  decision): `:child-type`/`:parent-type` are Terraform resource type
  strings, `:fk-ident` is the child's modeled foreign-key attribute,
  `:parent-join-ident` is the parent attribute it's compared against by
  typed value equality. `aws_route_table_association` appears twice
  (once per FK) - deliberately: an out-of-band association is drift on
  *both* the route table and the subnet it names. Its `:aws-iam-role-
  policy-attachment/role` entry is the one whose `:parent-join-ident`
  isn't that parent type's `\"id\"` attribute (`:aws-iam-role/name`, not
  an id) - a data difference here, not a code branch."
  [{:child-type "aws_security_group_rule"        :fk-ident :aws-security-group-rule/security-group-id
    :parent-type "aws_security_group"             :parent-join-ident :aws-security-group/id}
   {:child-type "aws_route"                       :fk-ident :aws-route/route-table-id
    :parent-type "aws_route_table"                :parent-join-ident :aws-route-table/id}
   {:child-type "aws_route_table_association"     :fk-ident :aws-route-table-association/route-table-id
    :parent-type "aws_route_table"                :parent-join-ident :aws-route-table/id}
   {:child-type "aws_route_table_association"     :fk-ident :aws-route-table-association/subnet-id
    :parent-type "aws_subnet"                      :parent-join-ident :aws-subnet/id}
   {:child-type "aws_iam_role_policy_attachment"  :fk-ident :aws-iam-role-policy-attachment/role
    :parent-type "aws_iam_role"                    :parent-join-ident :aws-iam-role/name}])

(defn- joined-children
  "`[?parent-e ?child-e]` pairs for one `child-parent-joins` entry: every
  child entity of `:child-type` whose `:fk-ident` value equals a managed
  (`:resource/managed? true`) parent entity of `:parent-type`'s
  `:parent-join-ident` value, further restricted by `child-where` (extra
  where clauses, as data - `:resource/managed? false` for the new-child
  Rule, `:resource/managed? true` + `:resource/sync-present? false` for
  the removed-child Rule). The shared low-level traversal both
  `new-children-by-parent` and `removed-children-by-parent` parameterize
  over `child-parent-joins`, rather than one bespoke Rule per
  relationship."
  [db {:keys [child-type fk-ident parent-type parent-join-ident]} child-where]
  (d/q {:find  '[?parent-e ?child-e]
        :in    '[$ ?child-type ?fk-ident ?parent-type ?parent-join-ident]
        :where (into '[[?parent-e :resource/type ?parent-type]
                        [?parent-e :resource/managed? true]
                        [?parent-e ?parent-join-ident ?join-v]
                        [?child-e ?fk-ident ?join-v]
                        [?child-e :resource/type ?child-type]]
                      child-where)}
       db child-type fk-ident parent-type parent-join-ident))

(defn- children-by-parent
  "Map of parent entity id -> list of pulled child summaries
  (`resource-summary-pattern`), across every entry of `joins` (defaults to
  `child-parent-joins`), for children matching `child-where` (see
  `joined-children`). A child under two different parents
  (`aws_route_table_association`'s two FK entries) appears independently
  in each parent's own list."
  ([db child-where] (children-by-parent db child-where child-parent-joins))
  ([db child-where joins]
   (->> joins
        (mapcat #(joined-children db % child-where))
        (group-by first)
        (into {} (map (fn [[parent-e pairs]]
                         [parent-e (mapv (comp #(d/pull db resource-summary-pattern %) second) pairs)]))))))

(defn new-children-by-parent
  "Map of managed-parent entity id -> list of its new out-of-band
  children (`children-by-parent`, `resource-summary-pattern`-shaped): for
  each `child-parent-joins` entry, every child entity of `:child-type`
  joined to a managed parent whose own `:resource/managed?` is `false` -
  i.e. a Discovered Resource `resource-tx`'s already-shipped `nil? match`
  branch produced, purely read back here at query time (no Sync
  write-path change is needed for this half of the feature - see
  design.md). A Terraform-managed child with the same FK shape is exactly
  what Terraform expects to exist, not new-child drift - reliably true for
  every covered type, `aws_security_group_rule`/`aws_route` included:
  `sync.clj`'s `existing-match` matches a Terraform-managed instance of
  either type by composite key (`db/resource-composite-key`), not by
  Terraform's own `\"id\"` (a different id space from what Sync observes -
  see ADR-0006), so `resource-tx`'s `nil? match` branch is only ever
  reached for a genuinely new, out-of-band instance.

  A Discovered child whose `:resource/sync-present?` is currently `false`
  is excluded (issue #32 PR #36 round-4 review fix): every child type
  `child-parent-joins` covers is one of `sync.clj`'s `sync-present-types`,
  and `resource-tx`'s `nil?`/`false? managed?` branches now assert
  `:resource/sync-present? true` on a fresh or re-observed Discovered
  child of a covered type on every pass that finds it, exactly like an
  already-managed match already did - so once a later full Sync pass
  (`missing-child-tx`, now also covering Discovered resources via
  `db/resource-ids-by-type`) no longer observes a previously-flagged
  out-of-band child, its marker flips to `false` and it drops out of this
  map, self-clearing the new-child drift signal instead of reporting it
  forever. A child whose marker is absent (no covering Sync pass has run
  since it was discovered) is still included - it was, by definition, just
  observed by `resource-tx`'s own `nil? match` branch this pass, which
  itself now sets the marker `true`, so an absent marker in practice only
  ever describes a brand-new-this-pass discovery reached through some
  other path than a live `sync!` run (e.g. a hermetic test transacting a
  Discovered entity directly)."
  [db]
  (children-by-parent db '[[?child-e :resource/managed? false]
                            (not [?child-e :resource/sync-present? false])]))

(defn removed-children-by-parent
  "Map of managed-parent entity id -> list of its managed children that
  have gone missing out-of-band (`children-by-parent`,
  `resource-summary-pattern`-shaped): for each `child-parent-joins` entry,
  every managed (`:resource/managed? true`) child entity of `:child-type`
  whose current `:resource/sync-present?` is `false` - Sync's most recent
  full pass covering that type ran and didn't find it (see `sync.clj`'s
  `missing-child-tx`). The child entity itself, and all of its
  modeled/generic attribute datoms, are never touched by this mechanism -
  `GET /state` continues to report it exactly as before for as long as it
  stays Terraform-managed (design.md's \"removed-child detection never
  changes what GET /state reports\" constraint)."
  [db]
  (children-by-parent db '[[?child-e :resource/managed? true]
                            [?child-e :resource/sync-present? false]]))

(defn- attribute-drifted-eids
  "Entity ids of every managed resource whose most recent write source is
  `:sync` and whose current live attribute values differ from the values
  Terraform last asserted for it - i.e. its stored attributes as of the
  most recent transaction where `:resource/last-write-source` held
  `:terraform` (found via `d/history`/`d/as-of`, `db/stored-attributes`
  reconstructing both sides so the diff compares like-for-like). Both
  sides are narrowed to `type`'s modeled keys via `db/comparable-attributes`
  before comparing - Sync never observes (and so never legitimately
  drifts) a resource's other, unmodeled attributes, and a
  Terraform-managed resource's own unrelated `:sync` update already
  retracts its stored generic sub-entities (`resource-upsert-retractions`),
  which would otherwise make an unscoped full-attribute-map comparison see
  permanent phantom drift on those unrelated keys forever after. A
  resource whose most recent write is `:terraform` (never drifted), or one
  with no prior `:terraform`-sourced write at all (Sync-discovered, never
  Terraform-managed), is never included - matching the resource-sync/
  drift-detection specs' scenarios. The attribute-level half of the drift
  Rule (design.md's \"Drift Rule: d/history-based comparison\") - see
  `drifted-resources`, which merges this with the new-child/removed-child
  halves (issue #32)."
  [db]
  (into []
        (keep (fn [[eid type]]
                (when-let [tx (last-terraform-write-tx db eid)]
                  (let [terraform-attrs (db/comparable-attributes type (db/stored-attributes (d/as-of db tx) eid type))
                        live-attrs      (db/comparable-attributes type (db/stored-attributes db eid type))]
                    (when (not= terraform-attrs live-attrs)
                      eid)))))
        (sync-sourced-managed-resources db)))

(defn drifted-resources
  "The drift Rule (design.md's \"Drift Rule: d/history-based comparison\"),
  merging three independent detection mechanisms into one flat list, each
  resource appearing at most once: plain attribute-level drift
  (`attribute-drifted-eids`); new-child drift, a managed parent with at
  least one out-of-band child (`new-children-by-parent`); and
  removed-child drift, a managed parent with at least one previously-known
  managed child Sync's most recent pass didn't find
  (`removed-children-by-parent`) - see those fns and design.md's
  `child-parent-joins`. A parent flagged by more than one mechanism gets
  one entry carrying all applicable signals, not several.

  This function is query-time-only: it is deliberately **not** registered
  in `policy.clj`'s `rules` vector, so it never runs as part of the
  pre-apply Policy Check (see that namespace and the drift-detection
  spec's \"excluded from the pre-apply Policy Check\" requirement)."
  [db]
  (let [attr-eids        (attribute-drifted-eids db)
        new-children     (new-children-by-parent db)
        removed-children (removed-children-by-parent db)
        all-eids         (into (into #{} attr-eids)
                                (into (set (keys new-children)) (keys removed-children)))]
    (into []
          (map (fn [eid]
                 (cond-> (d/pull db resource-summary-pattern eid)
                   (contains? new-children eid)     (assoc :new-children (get new-children eid))
                   (contains? removed-children eid)  (assoc :removed-children (get removed-children eid)))))
          all-eids)))

(defn- resource->json
  [{:resource/keys [id type] :keys [new-children removed-children]}]
  (cond-> {"type" type "id" id}
    (seq new-children)     (assoc "new_children" (mapv resource->json new-children))
    (seq removed-children) (assoc "removed_children" (mapv resource->json removed-children))))

(defn drift-endpoint
  "Handle a `GET /drift` request: evaluate the drift Rule
  (`drifted-resources`) against `(d/db conn)` and respond `200` with
  `{\"drifted\": [{\"type\" ... \"id\" ... [\"new_children\" [...]]
  [\"removed_children\" [...]]} ...]}` (`[]` when no drift is present) -
  `new_children`/`removed_children` are each a list of `{\"type\" ...
  \"id\" ...}` objects, present only when that parent has at least one
  (issue #32). Read-only - never creates, modifies, or retracts any
  resource entity or state version. Takes no request body and no query
  params."
  [conn]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {"drifted" (mapv resource->json (drifted-resources (d/db conn)))})})

(defn security-groups-with-port-22-open
  "Every `aws_security_group` resource with at least one associated
  `aws_security_group_rule` permitting ingress on port 22 from
  \"0.0.0.0/0\" - resolved via the rule's `security_group_id` back to its
  owning security group by typed value equality (`security-group-id` =
  `id`), a real Datalog join over `aws_security_group_rule`'s structure
  (port range, protocol, CIDR blocks), not an application-level scan of a
  JSON blob. Security groups with no such rule are not included."
  [db]
  (map #(d/pull db resource-summary-pattern %)
       (map first
            (d/q '[:find ?sg
                   :where
                   [?sg :aws-security-group/id ?sg-id]
                   [?rule :aws-security-group-rule/security-group-id ?sg-id]
                   [?rule :aws-security-group-rule/from-port ?from]
                   [?rule :aws-security-group-rule/to-port ?to]
                   [?rule :aws-security-group-rule/cidr-block "0.0.0.0/0"]
                   [(<= ?from 22)]
                   [(>= ?to 22)]]
                 db))))

;; ---------------------------------------------------------------------------
;; Network reachability
;; ---------------------------------------------------------------------------

(def ^:private permission-rules
  "The shared forward-direction security-group sub-rules used by both
  `reaches-rules` (backing `reachable?`) and `chain-rules` (backing
  `reachable-within-hops?`): `forward-permits`, `sg-rule-permits`,
  `rule-matches-peer`, and `egress-permits-cidr`. Extracted into their own
  var so both rule sets reference the same SG-matching semantics by
  construction, rather than duplicating clauses that could silently drift
  apart. `reaches-rules`'s resulting rule content stays byte-for-byte
  identical to before this extraction - only where these clauses live
  changed."
  '[;; Shared forward-direction SG check: source's egress rules permit
    ;; reaching `?dst`, and `?dst`'s ingress rules permit traffic from
    ;; `?src`. Only used for resource (not CIDR) targets.
    [(forward-permits ?src ?dst)
     (sg-rule-permits ?src "egress" ?dst)
     (sg-rule-permits ?dst "ingress" ?src)]

    ;; `?instance`'s security groups have a rule of `?type` permitting
    ;; `?peer` - either by CIDR (the rule's `cidr_blocks` matches `?peer`'s
    ;; subnet CIDR) or by `source_security_group_id` (the rule references
    ;; one of `?peer`'s security groups directly).
    [(sg-rule-permits ?instance ?type ?peer)
     [?instance-e :aws-instance/id ?instance]
     [?instance-e :aws-instance/vpc-security-group-id ?sg]
     [?rule-e :aws-security-group-rule/security-group-id ?sg]
     [?rule-e :aws-security-group-rule/type ?type]
     (rule-matches-peer ?rule-e ?peer)]

    [(rule-matches-peer ?rule-e ?peer)
     [?peer-e :aws-instance/id ?peer]
     [?peer-e :aws-instance/subnet-id ?peer-subnet]
     [?peer-subnet-e :aws-subnet/id ?peer-subnet]
     [?peer-subnet-e :aws-subnet/cidr-block ?peer-cidr]
     [?rule-e :aws-security-group-rule/cidr-block ?peer-cidr]]

    [(rule-matches-peer ?rule-e ?peer)
     [?peer-e :aws-instance/id ?peer]
     [?peer-e :aws-instance/vpc-security-group-id ?peer-sg]
     [?rule-e :aws-security-group-rule/source-security-group-id ?peer-sg]]

    ;; A rule open to the whole internet (`0.0.0.0/0`) trivially permits
    ;; any specific peer too, since that CIDR is a superset of every
    ;; address - without needing full CIDR-containment arithmetic for the
    ;; general case.
    [(rule-matches-peer ?rule-e ?peer)
     [?peer-e :aws-instance/id ?peer]
     [?rule-e :aws-security-group-rule/cidr-block "0.0.0.0/0"]]

    ;; `?instance`'s security groups have an egress rule whose
    ;; `cidr_blocks` includes the literal CIDR string `?cidr`. Used only by
    ;; the internet-gateway clause, whose target isn't an `aws_instance`.
    [(egress-permits-cidr ?instance ?cidr)
     [?instance-e :aws-instance/id ?instance]
     [?instance-e :aws-instance/vpc-security-group-id ?sg]
     [?rule-e :aws-security-group-rule/security-group-id ?sg]
     [?rule-e :aws-security-group-rule/type "egress"]
     [?rule-e :aws-security-group-rule/cidr-block ?cidr]]])

(def ^:private reaches-rules
  "The Datomic rules (`:in $ %`) backing `reachable?`, expressed as a single
  recursive rule, `reaches`, with one disjunctive clause per network path
  the traversal understands (self, same-subnet, local-route-within-VPC,
  peering-route, internet-gateway-route), composed with the shared
  `permission-rules`. Each network-path clause is gated by
  `forward-permits` (or, for the internet-bound clause,
  `egress-permits-cidr`), the shared forward-direction security-group
  check: source egress + (for a resource target) target ingress, matching
  by CIDR or `source_security_group_id`. Return-path security group rules
  are never checked - AWS security groups are stateful.

  `reaches`'s target (`?dst`) is polymorphic: either an `aws_instance`
  resource identifier (matched by the same-subnet/local-route/peering
  clauses) or a CIDR string such as `\"0.0.0.0/0\"` (matched only by the
  internet-gateway clause) - so \"the public internet\" needs no special
  sentinel, just the default route."
  (into
   '[;; Self: a resource always reaches itself, regardless of SG/route
     ;; configuration.
     [(reaches ?src ?dst)
      [(= ?src ?dst)]]

     ;; Same subnet: both instances share `subnet_id`.
     [(reaches ?src ?dst)
      [?src-e :aws-instance/id ?src]
      [?dst-e :aws-instance/id ?dst]
      [?src-e :aws-instance/subnet-id ?subnet]
      [?dst-e :aws-instance/subnet-id ?subnet]
      (forward-permits ?src ?dst)]

     ;; Local route within VPC: both instances' subnets belong to the same
     ;; VPC - the "local" route AWS adds implicitly, never an explicit
     ;; `aws_route`.
     [(reaches ?src ?dst)
      [?src-e :aws-instance/id ?src]
      [?dst-e :aws-instance/id ?dst]
      [?src-e :aws-instance/subnet-id ?src-subnet]
      [?dst-e :aws-instance/subnet-id ?dst-subnet]
      [?src-subnet-e :aws-subnet/id ?src-subnet]
      [?dst-subnet-e :aws-subnet/id ?dst-subnet]
      [?src-subnet-e :aws-subnet/vpc-id ?vpc]
      [?dst-subnet-e :aws-subnet/vpc-id ?vpc]
      (forward-permits ?src ?dst)]

     ;; Peering route: source's subnet's route table has a route to a
     ;; peering connection whose two sides include the target's VPC.
     [(reaches ?src ?dst)
      [?src-e :aws-instance/id ?src]
      [?dst-e :aws-instance/id ?dst]
      [?src-e :aws-instance/subnet-id ?src-subnet]
      [?dst-e :aws-instance/subnet-id ?dst-subnet]
      [?dst-subnet-e :aws-subnet/id ?dst-subnet]
      [?dst-subnet-e :aws-subnet/vpc-id ?dst-vpc]
      [?assoc-e :aws-route-table-association/subnet-id ?src-subnet]
      [?assoc-e :aws-route-table-association/route-table-id ?rt]
      [?route-e :aws-route/route-table-id ?rt]
      [?route-e :aws-route/vpc-peering-connection-id ?pcx]
      [?pcx-e :aws-vpc-peering-connection/id ?pcx]
      (peering-connects ?pcx-e ?dst-vpc)
      (forward-permits ?src ?dst)]

     ;; Internet-gateway route: source's subnet's route table has a route
     ;; to an `aws_internet_gateway` for the default route, and the target
     ;; is the literal internet CIDR (not another instance).
     [(reaches ?src ?dst)
      [(= ?dst "0.0.0.0/0")]
      [?src-e :aws-instance/id ?src]
      [?src-e :aws-instance/subnet-id ?src-subnet]
      [?assoc-e :aws-route-table-association/subnet-id ?src-subnet]
      [?assoc-e :aws-route-table-association/route-table-id ?rt]
      [?route-e :aws-route/route-table-id ?rt]
      [?route-e :aws-route/destination-cidr-block "0.0.0.0/0"]
      [?route-e :aws-route/gateway-id ?gw]
      [?igw-e :aws-internet-gateway/id ?gw]
      (egress-permits-cidr ?src "0.0.0.0/0")]

     ;; A peering connection's two sides - either its `vpc_id` or its
     ;; `peer_vpc_id` - can be the "other" VPC from either instance's point
     ;; of view.
     [(peering-connects ?pcx-e ?vpc)
      [?pcx-e :aws-vpc-peering-connection/vpc-id ?vpc]]
     [(peering-connects ?pcx-e ?vpc)
      [?pcx-e :aws-vpc-peering-connection/peer-vpc-id ?vpc]]]
   permission-rules))

(defn reachable?
  "Whether network traffic from resource `src` (an `aws_instance` resource
  identifier) can reach `dst` - either another `aws_instance` resource
  identifier or a CIDR/IP string such as `\"0.0.0.0/0\"` for the public
  internet - by traversing the deployed VPC/subnet/route-table/security-group
  graph via the recursive `reaches` Datomic rule set (see `reaches-rules`).
  `src` always reaches itself, regardless of security group or route
  configuration."
  [db src dst]
  (boolean
   (seq (d/q '[:find ?dst
               :in $ % ?src ?dst
               :where (reaches ?src ?dst)]
             db reaches-rules src dst))))

;; ---------------------------------------------------------------------------
;; Multi-hop VPC peering chain reachability
;; ---------------------------------------------------------------------------

(def ^:private chain-rules
  "The Datomic rules (`:in $ %`) backing `reachable-within-hops?`, a wholly
  separate recursive rule set from `reaches-rules` (see that var and
  `reachable?`) that walks a *chain* of `aws_vpc_peering_connection` hops
  between VPCs, rather than the single fixed hop `reaches` understands.
  Composed with the shared `permission-rules` (see that var) so the
  endpoint security-group semantics stay identical to `reachable?`'s by
  construction, without calling into or modifying `reaches`/
  `peering-connects` themselves.

  - `chain-connects ?vpc-a ?vpc-b`: true when one hop - today, one
    `aws_vpc_peering_connection` - directly connects `?vpc-a` to `?vpc-b`.
    A route table belonging to `?vpc-a` (`aws-route-table/vpc-id`, joined
    at VPC granularity, not anchored to any particular subnet - see
    design.md's rationale) has an `aws_route` naming a peering connection
    whose `vpc_id`/`peer_vpc_id` pair includes `?vpc-b`. One disjunctive
    clause per hop type; a future hop type (VPN, transit gateway,
    cross-account peering) is an additional clause here, not a
    restructuring of the recursion below.

  - `vpc-chain-reaches ?src-vpc ?dst-vpc ?hops`: the genuinely
    self-referential, hard-bounded recursive walk. Base case: a VPC always
    chain-reaches itself, at any `?hops` (including `0` or negative).
    Recursive case: `[(> ?hops 0)]` guards against recursing past budget,
    `chain-connects` finds one hop to `?mid-vpc`, `?hops` is decremented,
    and the rule recurses on `?mid-vpc`. The guard-then-decrement pair on
    every call is what makes the bound provable, unlike an unbounded
    transitive closure checked against a limit afterward.

  - `chain-reaches ?src ?dst ?hops`: the public entry point, polymorphic
    like `reaches` - self clause; a resource-target clause that resolves
    `?src`/`?dst` (`aws_instance`s) to their VPCs via `subnet_id` and
    delegates to `vpc-chain-reaches`, gated by the shared `forward-permits`
    (source egress + target ingress - only the true endpoints are
    SG-checked, intermediate transit VPCs are pure topology); and a
    CIDR-target clause (`?dst` = `\"0.0.0.0/0\"`) that delegates to
    `vpc-chain-reaches` to find some VPC within the hop budget that itself
    routes to an `aws_internet_gateway`, gated by `egress-permits-cidr` -
    the final internet-gateway step is free, it is not a `chain-connects`
    hop and consumes no `?hops` budget."
  (into
   '[;; One hop: a route table belonging to `?vpc-a` has a route to a
     ;; peering connection whose two sides include `?vpc-b`. Symmetric
     ;; clauses mirror `peering-connects`'s own two clauses, but are
     ;; defined fresh here (not reused) - see design.md.
     [(chain-connects ?vpc-a ?vpc-b)
      [?rt-e :aws-route-table/vpc-id ?vpc-a]
      [?route-e :aws-route/route-table-id ?rt]
      [?rt-e :aws-route-table/id ?rt]
      [?route-e :aws-route/vpc-peering-connection-id ?pcx]
      [?pcx-e :aws-vpc-peering-connection/id ?pcx]
      [?pcx-e :aws-vpc-peering-connection/vpc-id ?vpc-a]
      [?pcx-e :aws-vpc-peering-connection/peer-vpc-id ?vpc-b]]
     [(chain-connects ?vpc-a ?vpc-b)
      [?rt-e :aws-route-table/vpc-id ?vpc-a]
      [?route-e :aws-route/route-table-id ?rt]
      [?rt-e :aws-route-table/id ?rt]
      [?route-e :aws-route/vpc-peering-connection-id ?pcx]
      [?pcx-e :aws-vpc-peering-connection/id ?pcx]
      [?pcx-e :aws-vpc-peering-connection/peer-vpc-id ?vpc-a]
      [?pcx-e :aws-vpc-peering-connection/vpc-id ?vpc-b]]

     ;; Base case: a VPC always chain-reaches itself, regardless of
     ;; `?hops` - zero-hop termination for same-VPC source/target pairs.
     [(vpc-chain-reaches ?src-vpc ?dst-vpc ?hops)
      [(= ?src-vpc ?dst-vpc)]]

     ;; Recursive case: guard, one hop, decrement, recurse. Genuinely
     ;; self-referential - the hard bound is provable from the
     ;; guard-then-decrement pair on every call, not checked afterward.
     [(vpc-chain-reaches ?src-vpc ?dst-vpc ?hops)
      [(> ?hops 0)]
      (chain-connects ?src-vpc ?mid-vpc)
      [(- ?hops 1) ?hops-1]
      (vpc-chain-reaches ?mid-vpc ?dst-vpc ?hops-1)]

     ;; Self: a resource always reaches itself, regardless of hop budget.
     [(chain-reaches ?src ?dst ?hops)
      [(= ?src ?dst)]]

     ;; Resource target: resolve both endpoints to their VPCs, delegate
     ;; the chain walk to `vpc-chain-reaches`, then gate on the shared
     ;; `forward-permits` - true endpoints only, exactly as `reachable?`
     ;; does for its single hop.
     [(chain-reaches ?src ?dst ?hops)
      [?src-e :aws-instance/id ?src]
      [?dst-e :aws-instance/id ?dst]
      [?src-e :aws-instance/subnet-id ?src-subnet]
      [?dst-e :aws-instance/subnet-id ?dst-subnet]
      [?src-subnet-e :aws-subnet/id ?src-subnet]
      [?dst-subnet-e :aws-subnet/id ?dst-subnet]
      [?src-subnet-e :aws-subnet/vpc-id ?src-vpc]
      [?dst-subnet-e :aws-subnet/vpc-id ?dst-vpc]
      (vpc-chain-reaches ?src-vpc ?dst-vpc ?hops)
      (forward-permits ?src ?dst)]

     ;; CIDR target: resolve `?src`'s VPC, delegate to `vpc-chain-reaches`
     ;; to find some VPC within budget that itself has a route to an
     ;; `aws_internet_gateway`, then gate on `egress-permits-cidr`. The
     ;; IGW step is free of hop budget - `vpc-chain-reaches` only counts
     ;; peering hops to arrive at the IGW-having VPC.
     [(chain-reaches ?src ?dst ?hops)
      [(= ?dst "0.0.0.0/0")]
      [?src-e :aws-instance/id ?src]
      [?src-e :aws-instance/subnet-id ?src-subnet]
      [?src-subnet-e :aws-subnet/id ?src-subnet]
      [?src-subnet-e :aws-subnet/vpc-id ?src-vpc]
      [?igw-rt-e :aws-route-table/vpc-id ?igw-vpc]
      [?igw-route-e :aws-route/route-table-id ?igw-rt]
      [?igw-rt-e :aws-route-table/id ?igw-rt]
      [?igw-route-e :aws-route/destination-cidr-block "0.0.0.0/0"]
      [?igw-route-e :aws-route/gateway-id ?gw]
      [?igw-e :aws-internet-gateway/id ?gw]
      (vpc-chain-reaches ?src-vpc ?igw-vpc ?hops)
      (egress-permits-cidr ?src "0.0.0.0/0")]]
   permission-rules))

(defn reachable-within-hops?
  "Whether network traffic from resource `src` (an `aws_instance` resource
  identifier) can reach `dst` - either another `aws_instance` resource
  identifier or a CIDR/IP string such as `\"0.0.0.0/0\"` for the public
  internet - by traversing a *chain* of `aws_vpc_peering_connection` hops,
  each hop being one peering connection traversed (e.g. A-B-C-D is 3
  hops), bounded by `max-hops`. Unlike `reachable?`'s single fixed peering
  hop, this walks an arbitrary-length chain via the genuinely
  self-referential recursive `chain-reaches`/`vpc-chain-reaches` Datomic
  rules (see `chain-rules`), each recursive step guarded and decrementing
  a hard hop counter so the bound is provable, not merely checked
  afterward against an unbounded traversal.

  Only the true endpoints (`src`/`dst`) are security-group-checked -
  source egress + (for a resource target) target ingress, reusing the
  same `forward-permits`/`egress-permits-cidr` sub-rules `reachable?`
  uses. Intermediate transit VPCs in the chain are pure routing/topology
  and are never SG-checked (AWS security groups attach to instances, and
  there is no anchor instance at a transit-only VPC).

  When `dst` is a CIDR, the final internet-gateway egress step is free -
  it does not consume `max-hops` budget, which counts peering hops only.

  `src` always reaches itself, regardless of `max-hops` (including `0`).

  This function does not call into or modify `reaches`/`peering-connects`,
  and has no effect on `reachable?`'s behavior, signature, or cost
  profile."
  [db src dst max-hops]
  (boolean
   (seq (d/q '[:find ?dst
               :in $ % ?src ?dst ?max-hops
               :where (chain-reaches ?src ?dst ?max-hops)]
             db chain-rules src dst max-hops))))

;; ---------------------------------------------------------------------------
;; Ad-hoc query HTTP endpoint
;; ---------------------------------------------------------------------------

(defn- parse-edn
  "Parse `s` as EDN, returning ::invalid instead of throwing on failure -
  mirrors `handler.clj`'s own JSON-parsing error handling, for the
  EDN-bodied `/query` endpoint."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _
      ::invalid)))

(defn ad-hoc-query
  "Handle a `POST /query` request body: parse `raw-body` as EDN (a
  `{:find ... :in ... :where ...}` query map, optionally with a
  `:rule-defs` rule-set alongside `%` in `:in`), validate it via the
  shared validator, and on success run it against `(d/db conn)`,
  responding `200` with the raw `d/q` result set, EDN-encoded (no
  result-shape assumption, unlike a Rule - any `:find` shape that passes
  validation is run as-is). Responds `400` with the reason on invalid EDN
  or a validator rejection, without running the query."
  [conn raw-body]
  (let [parsed (parse-edn raw-body)]
    (if (or (= parsed ::invalid) (not (map? parsed)))
      {:status  400
       :headers {"Content-Type" "application/edn"}
       :body    (pr-str {:reason "invalid EDN: expected a query map"})}
      (let [rule-defs  (:rule-defs parsed)
            validation (validator/validate-query parsed rule-defs)]
        (if-not (:valid? validation)
          {:status  400
           :headers {"Content-Type" "application/edn"}
           :body    (pr-str validation)}
          (let [query  (select-keys parsed [:find :in :where])
                args   (cond-> [(d/db conn)] (seq rule-defs) (conj rule-defs))
                result (apply d/q query args)]
            {:status  200
             :headers {"Content-Type" "application/edn"}
             :body    (pr-str result)}))))))
