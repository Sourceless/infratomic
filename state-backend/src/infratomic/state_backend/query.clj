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
