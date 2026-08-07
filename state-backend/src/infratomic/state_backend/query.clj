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
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]))

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

(def ^:private reaches-rules
  "The Datomic rules (`:in $ %`) backing `reachable?`, expressed as a single
  recursive rule, `reaches`, with one disjunctive clause per network path
  the traversal understands (self, same-subnet, local-route-within-VPC,
  peering-route, internet-gateway-route). Each network-path clause is
  gated by `forward-permits` (or, for the internet-bound clause,
  `egress-permits-cidr`), the shared forward-direction security-group
  check: source egress + (for a resource target) target ingress, matching
  by CIDR or `source_security_group_id`. Return-path security group rules
  are never checked - AWS security groups are stateful.

  `reaches`'s target (`?dst`) is polymorphic: either an `aws_instance`
  resource identifier (matched by the same-subnet/local-route/peering
  clauses) or a CIDR string such as `\"0.0.0.0/0\"` (matched only by the
  internet-gateway clause) - so \"the public internet\" needs no special
  sentinel, just the default route."
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
     [?pcx-e :aws-vpc-peering-connection/peer-vpc-id ?vpc]]

    ;; Shared forward-direction SG check: source's egress rules permit
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
