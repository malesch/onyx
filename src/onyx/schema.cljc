(ns onyx.schema
  (:require [schema.core :as s]
            [onyx.information-model :as i]
            [onyx.types]
            [schema.spec.leaf :as leaf]
            [schema.spec.core :as spec]))

(s/defschema NamespacedKeyword
  (s/pred (fn [kw]
            (and (keyword? kw)
                 (namespace kw)))
          'keyword-namespaced?))

(s/defschema Function
  (s/cond-pre (s/pred var? 'var?)
              (s/pred ifn? 'ifn?)))

(s/defschema TaskName
  (s/pred (fn [v]
            (and (not= :all v)
                 (not= :none v)
                 (keyword? v)))
          'task-name?))

(defn ^{:private true} edge-two-nodes? [edge]
  (= (count edge) 2))

(def ^{:private true} edge-validator
  (s/constrained [TaskName] (fn [edge]
                              (and (= (count edge) 2)
                                   (vector? edge))) 'edge-two-nodes?))

(s/defschema Workflow
  (s/constrained [edge-validator]
                 #(and (vector? %) (pos? (count %)))
                 'edge-two-nodes?))

(s/defschema PartialWorkflow
  (s/constrained [edge-validator] vector? 'vector?))

(s/defschema Language
  (apply s/enum (get-in i/model [:catalog-entry :model :onyx/language :choices])))

(s/defschema PosInt
  (s/constrained s/Int pos? 'pos?))

(s/defschema SPosInt
  (s/constrained s/Int (fn [v] (>= v 0)) 'spos?))

(defrecord RestrictedKwNamespace [nspaces]
  s/Schema
  (spec [this]
    (let [prohibited-names (set (map name nspaces))]
      (leaf/leaf-spec
       (some-fn
        (spec/simple-precondition this keyword?)
        (spec/precondition this
                           (fn [datom]
                             (not (prohibited-names
                                   (namespace datom))))
                           (fn [datom]
                             (list '= (list 'name (interpose 'or nspaces))
                                   (list 'namespace datom))))))))
  (explain [this] [:restricted-ns nspaces]))

(defn ^:deprecated build-allowed-key-ns [& nspaces]
  (RestrictedKwNamespace. nspaces))

(defn restricted-ns [& nspaces]
  (RestrictedKwNamespace. nspaces))

(defn deprecated [key-seq]
  (s/pred
   (fn [_]
     (throw (ex-info (:deprecation-doc (get-in i/model key-seq)) {})))
   'deprecated-key?))

(def base-task-map
  {:onyx/name TaskName
   :onyx/type (apply s/enum (get-in i/model [:catalog-entry :model :onyx/type :choices]))
   :onyx/batch-size PosInt
   (s/optional-key :onyx/params) [s/Any]
   (s/optional-key :onyx/uniqueness-key) s/Any
   (s/optional-key :onyx/deduplicate?) s/Bool
   (s/optional-key :onyx/restart-pred-fn)
   (deprecated [:catalog-entry :model :onyx/restart-pred-fn])
   (s/optional-key :onyx/language) Language
   (s/optional-key :onyx/batch-timeout) SPosInt
   (s/optional-key :onyx/doc) s/Str
   (s/optional-key :onyx/bulk?) s/Bool
   (s/optional-key :onyx/max-peers) PosInt
   (s/optional-key :onyx/min-peers) PosInt
   (s/optional-key :onyx/n-peers) PosInt
   (s/optional-key :onyx/required-tags) [s/Keyword]
   (restricted-ns :onyx) s/Any})

(s/defschema FluxPolicy
  (apply s/enum (get-in i/model [:catalog-entry :model :onyx/flux-policy :choices])))

(s/defschema FnPath
  (s/cond-pre NamespacedKeyword s/Keyword))

(def partial-grouping-task
  {(s/optional-key :onyx/group-by-key) s/Any
   (s/optional-key :onyx/group-by-fn) FnPath
   :onyx/flux-policy FluxPolicy})

(defn grouping-task? [task-map]
  (and (#{:function :output} (:onyx/type task-map))
       (or (not (nil? (:onyx/group-by-key task-map)))
           (not (nil? (:onyx/group-by-fn task-map))))))

(def partial-input-task
  {:onyx/plugin (s/cond-pre NamespacedKeyword s/Keyword)
   :onyx/medium s/Keyword
   :onyx/type (s/enum :input)
   (s/optional-key :onyx/fn) FnPath
   (s/optional-key :onyx/input-retry-timeout) PosInt
   (s/optional-key :onyx/pending-timeout) PosInt
   (s/optional-key :onyx/max-pending) PosInt})

(def partial-output-task
  {:onyx/plugin (s/cond-pre NamespacedKeyword s/Keyword)
   :onyx/medium s/Keyword
   :onyx/type (s/enum :output)
   (s/optional-key :onyx/fn) FnPath})

(s/defschema NonNamespacedKeyword
  (s/pred (fn [v]
            (and (keyword? v)
                 (not (namespace v))))
          'keyword-non-namespaced))

(def partial-java-plugin
  {:onyx/plugin NonNamespacedKeyword
   (s/optional-key :onyx/fn) FnPath})

(def partial-clojure-plugin
  {:onyx/plugin NamespacedKeyword
   (s/optional-key :onyx/fn) FnPath})

(def partial-fn-task
  {:onyx/fn (s/cond-pre NamespacedKeyword s/Keyword)
   (s/optional-key :onyx/plugin) (s/cond-pre NamespacedKeyword s/Keyword)})

(def partial-clojure-fn-task
  {:onyx/fn NamespacedKeyword})

(def partial-java-fn-task
  {:onyx/fn s/Keyword})

(defn java? [task-map]
  (= :java (:onyx/language task-map)))

(defn valid-min-peers-max-peers-n-peers? [entry]
  (case (:onyx/flux-policy entry)
    :continue
    true
    :kill
    (or (:onyx/n-peers entry)
        (:onyx/min-peers entry)
        (= (:onyx/max-peers entry) 2))
    :recover
    (or (:onyx/n-peers entry)
        (and (:onyx/max-peers entry)
             (= (:onyx/max-peers entry)
                (:onyx/min-peers entry)))
        (= (:onyx/max-peers entry) 1))))

(def input-task-map
  {:clojure (merge base-task-map
                   partial-input-task)
   :java (merge base-task-map
                partial-input-task
                partial-java-plugin)})

(def output-task-map
  {:clojure-grouping (merge
                      base-task-map
                      partial-output-task
                      partial-grouping-task
                      partial-clojure-plugin)
   :java-grouping (merge
                   base-task-map
                   partial-output-task
                   partial-grouping-task
                   partial-java-plugin)
   :clojure (merge base-task-map
                   partial-output-task
                   partial-clojure-plugin)
   :java (merge base-task-map
                partial-output-task
                partial-java-plugin)})

(def function-task-map
  {:clojure-grouping (merge base-task-map
                            partial-fn-task
                            partial-grouping-task
                            partial-clojure-fn-task)
   :java-grouping (merge base-task-map
                         partial-fn-task
                         partial-grouping-task
                         partial-java-fn-task)
   :clojure (merge base-task-map
                   partial-fn-task
                   partial-clojure-fn-task)
   :java (merge base-task-map
                partial-fn-task
                partial-java-fn-task)})

(defn combine-restricted-ns [m]
  (let [r-ns-keys (filter (partial instance? onyx.schema.RestrictedKwNamespace)
                          (keys m))
        r-ns (mapcat :nspaces r-ns-keys)]
    (if-not (empty? r-ns)
      (-> (apply (partial dissoc m) r-ns-keys)
          (assoc (apply restricted-ns r-ns) s/Any))
      m)))

(defn UniqueTaskMap
  ([] (UniqueTaskMap nil))
  ([schema & schemas]
   (let [customize (fn [s] (combine-restricted-ns (apply merge s (cons schema schemas))))
         clojure? (complement java?)]
     (s/conditional
     ;;;; Inputs
      #(and (= (:onyx/type %) :input)
            (java? %))
      (customize (:java input-task-map))
      #(and (= (:onyx/type %) :input)
            (clojure? %))
      (customize (:clojure input-task-map))

     ;;;; Outputs
      #(and (= (:onyx/type %) :output)
            (grouping-task? %)
            (java? %))
      (s/constrained (customize (:java-grouping output-task-map))
                     valid-min-peers-max-peers-n-peers?
                     'valid-flux-policy-min-max-n-peers)
      #(and (= (:onyx/type %) :output)
            (grouping-task? %)
            (clojure? %))
      (s/constrained (customize (:clojure-grouping output-task-map))
                     valid-min-peers-max-peers-n-peers?
                     'valid-flux-policy-min-max-n-peers)
      #(and (= (:onyx/type %) :output)
            (not (grouping-task? %))
            (java? %)) (customize (:java output-task-map))
      #(and (= (:onyx/type %) :output)
            (not (grouping-task? %))
            (clojure? %)) (customize (:clojure output-task-map))
     ;;;; Functions
      #(and (= (:onyx/type %) :function)
            (grouping-task? %)
            (java? %)) (s/constrained (customize (:java-grouping function-task-map))
                                      valid-min-peers-max-peers-n-peers?
                                      'valid-flux-policy-min-max-n-peers)
      #(and (= (:onyx/type %) :function)
            (grouping-task? %)
            (clojure? %)) (s/constrained (customize (:clojure-grouping function-task-map))
                                         valid-min-peers-max-peers-n-peers?
                                         'valid-flux-policy-min-max-n-peers)

      #(and (= (:onyx/type %) :function)
            (not (grouping-task? %))
            (java? %)) (customize (:java function-task-map))

      #(and (= (:onyx/type %) :function)
            (not (grouping-task? %))
            (clojure? %)) (customize (:clojure function-task-map))
      'onyx-type-conditional))))

(def TaskMap
  (UniqueTaskMap))

(s/defschema Catalog
  [TaskMap])

(s/defschema Lifecycle
  {:lifecycle/task s/Keyword
   :lifecycle/calls NamespacedKeyword
   (s/optional-key :lifecycle/doc) s/Str
   (restricted-ns :lifecycle) s/Any})

(s/defschema LifecycleCall
  {(s/optional-key :lifecycle/doc) s/Str
   (s/optional-key :lifecycle/start-task?) Function
   (s/optional-key :lifecycle/before-task-start) Function
   (s/optional-key :lifecycle/before-batch) Function
   (s/optional-key :lifecycle/after-read-batch) Function
   (s/optional-key :lifecycle/after-batch) Function
   (s/optional-key :lifecycle/after-task-stop) Function
   (s/optional-key :lifecycle/after-ack-segment) Function
   (s/optional-key :lifecycle/after-retry-segment) Function
   (s/optional-key :lifecycle/handle-exception) Function})

(s/defschema FlowAction
  (s/enum :retry))

(s/defschema ^:deprecated UnsupportedFlowKey
  (restricted-ns :flow))

(s/defschema SpecialFlowTasks (s/enum :all :none))

(s/defschema FlowCondition
  {:flow/from (s/cond-pre TaskName SpecialFlowTasks)
   :flow/to (s/cond-pre TaskName [TaskName] SpecialFlowTasks)
   :flow/predicate (s/cond-pre s/Keyword [s/Any])
   (s/optional-key :flow/post-transform) NamespacedKeyword
   (s/optional-key :flow/thrown-exception?) s/Bool
   (s/optional-key :flow/action) FlowAction
   (s/optional-key :flow/short-circuit?) s/Bool
   (s/optional-key :flow/exclude-keys) [s/Keyword]
   (s/optional-key :flow/doc) s/Str
   (restricted-ns :flow) s/Any})

(s/defschema Unit
  [(s/one s/Int "unit-count")
   (s/one s/Keyword "unit-type")])

(s/defschema WindowType
  (apply s/enum (get-in i/model [:window-entry :model :window/type :choices])))

(s/defschema ^:deprecated UnsupportedWindowKey
  (restricted-ns :window))

(s/defschema WindowBase
  {:window/id s/Keyword
   :window/task TaskName
   :window/type WindowType
   :window/aggregation (s/cond-pre s/Keyword [s/Keyword])
   (s/optional-key :window/init) s/Any
   (s/optional-key :window/window-key) s/Any
   (s/optional-key :window/min-value) s/Int
   (s/optional-key :window/range) Unit
   (s/optional-key :window/slide) Unit
   (s/optional-key :window/timeout-gap) Unit
   (s/optional-key :window/session-key) s/Any
   (s/optional-key :window/doc) s/Str
   (restricted-ns :window) s/Any})

(s/defschema Window
  (s/constrained
   WindowBase
   (fn [v] (if (#{:fixed :sliding} (:window/type v))
             (:window/range v)
             true))
   'range-defined-for-fixed-and-sliding?))

(s/defschema StateAggregationCall
  {(s/optional-key :aggregation/init) Function
   :aggregation/create-state-update Function
   :aggregation/apply-state-update Function
   (s/optional-key :aggregation/super-aggregation-fn) Function})

(s/defschema WindowExtension
  (s/constrained
   {:window Window
    :id s/Keyword
    :task TaskName
    :type WindowType
    :aggregation (s/cond-pre s/Keyword [s/Keyword])
    (s/optional-key :init) (s/maybe s/Any)
    (s/optional-key :window-key) (s/maybe s/Any)
    (s/optional-key :min-value) (s/maybe SPosInt)
    (s/optional-key :range) (s/maybe Unit)
    (s/optional-key :slide) (s/maybe Unit)
    (s/optional-key :timeout-gap) (s/maybe Unit)
    (s/optional-key :session-key) (s/maybe s/Any)
    (s/optional-key :doc) (s/maybe s/Str)}
   record? 'record?))

(s/defschema TriggerRefinement
  NamespacedKeyword)

(s/defschema TriggerPeriod
  (apply s/enum (get-in i/model [:trigger-entry :model :trigger/period :choices])))

(s/defschema TriggerThreshold
  (s/enum :elements :element))

(s/defschema ^:deprecated UnsupportedTriggerKey
  (restricted-ns :trigger))

(s/defschema TriggerPeriod
  [(s/one PosInt "trigger period")
   (s/one TriggerPeriod "threshold type")])

(s/defschema TriggerThreshold
  [(s/one PosInt "number elements")
   (s/one TriggerThreshold "threshold type")])

(s/defschema Trigger
  {:trigger/window-id s/Keyword
   :trigger/refinement TriggerRefinement
   :trigger/on NamespacedKeyword
   :trigger/sync NamespacedKeyword
   (s/optional-key :trigger/fire-all-extents?) s/Bool
   (s/optional-key :trigger/pred) NamespacedKeyword
   (s/optional-key :trigger/watermark-percentage) double
   (s/optional-key :trigger/doc) s/Str
   (s/optional-key :trigger/period) TriggerPeriod
   (s/optional-key :trigger/threshold) TriggerThreshold
   (s/optional-key :trigger/id) s/Any
   (restricted-ns :trigger) s/Any})

(s/defschema RefinementCall
  {:refinement/create-state-update Function
   :refinement/apply-state-update Function})

(s/defschema TriggerCall
  {:trigger/init-state Function
   :trigger/next-state Function
   :trigger/trigger-fire? Function})

(s/defschema TriggerState
  (s/constrained
   {:window-id s/Keyword
    :refinement TriggerRefinement
    :on s/Keyword
    :sync s/Keyword
    :fire-all-extents? (s/maybe s/Bool)
    :pred (s/maybe s/Keyword)
    :watermark-percentage (s/maybe double)
    :doc (s/maybe s/Str)
    :period (s/maybe TriggerPeriod)
    :threshold (s/maybe TriggerThreshold)
    :sync-fn (s/maybe Function)
    :state s/Any
    :id s/Any
    :trigger Trigger
    :init-state Function
    :trigger-fire? Function
    :next-trigger-state Function
    :create-state-update Function
    :apply-state-update Function}
   record? 'record?))

(s/defschema PeerSchedulerEvent (apply s/enum i/peer-scheduler-event-types))

(s/defschema TriggerEventType (apply s/enum i/trigger-event-types))

(def PeerSchedulerEventTypes [:peer-reallocated :peer-left :job-killed :job-completed])

(s/defschema PeerSchedulerEvent (apply s/enum PeerSchedulerEventTypes))

(def TriggerEventTypes [:timer-tick :new-segment])

(s/defschema TriggerEvent (apply s/enum (into PeerSchedulerEventTypes TriggerEventTypes)))

(s/defschema PeerSchedulerEvent (apply s/enum i/peer-scheduler-event-types))

(s/defschema TriggerEventType (apply s/enum i/trigger-event-types))

(s/defschema JobScheduler
  NamespacedKeyword)

(s/defschema TaskScheduler
  NamespacedKeyword)

(s/defschema JobMetadata
  {s/Keyword s/Any})

(s/defschema Job
  {:catalog Catalog
   :workflow Workflow
   :task-scheduler TaskScheduler
   (s/optional-key :percentage) s/Int
   (s/optional-key :flow-conditions) [FlowCondition]
   (s/optional-key :windows) [Window]
   (s/optional-key :triggers) [Trigger]
   (s/optional-key :lifecycles) [Lifecycle]
   (s/optional-key :metadata) JobMetadata
   (s/optional-key :acker/percentage) s/Int
   (s/optional-key :acker/exempt-input-tasks?) s/Bool
   (s/optional-key :acker/exempt-output-tasks?) s/Bool
   (s/optional-key :acker/exempt-tasks) [s/Keyword]})

(s/defschema PartialJob
  (assoc Job :workflow PartialWorkflow))

(s/defschema TenancyId
  (s/cond-pre s/Uuid s/Str))

(s/defschema EnvConfig
  {:zookeeper/address s/Str
   (s/optional-key :onyx/id) (deprecated [:env-config :model :onyx/id])
   :onyx/tenancy-id TenancyId
   (s/optional-key :zookeeper/server?) s/Bool
   (s/optional-key :zookeeper.server/port) s/Int
   (s/optional-key :onyx.bookkeeper/server?) s/Bool
   (s/optional-key :onyx.bookkeeper/delete-server-data?) s/Bool
   (s/optional-key :onyx.bookkeeper/port) s/Int
   (s/optional-key :onyx.bookkeeper/local-quorum?) s/Bool
   (s/optional-key :onyx.bookkeeper/local-quorum-ports) [s/Int]
   (s/optional-key :onyx.bookkeeper/base-journal-dir) s/Str
   (s/optional-key :onyx.bookkeeper/base-ledger-dir) s/Str
   (s/optional-key :onyx.bookkeeper/disk-usage-threshold) (s/pred float?)
   (s/optional-key :onyx.bookkeeper/disk-usage-warn-threshold) (s/pred float?)
   (s/optional-key :onyx.bookkeeper/zk-ledgers-root-path) s/Str
   s/Keyword s/Any})

(s/defschema AeronIdleStrategy
  (s/enum :busy-spin :low-restart-latency :high-restart-latency))

(s/defschema Messaging
  (s/enum :aeron :dummy-messenger))

(s/defschema StateLogImpl
  (s/enum :bookkeeper :none))

(s/defschema StateFilterImpl
  (s/enum :set :rocksdb))

(s/defschema PeerClientConfig
  {:zookeeper/address s/Str
   (s/optional-key :onyx/id) (deprecated [:env-config :model :onyx/id])
   :onyx/tenancy-id TenancyId
   s/Keyword s/Any})

(s/defschema PeerConfig
  {:zookeeper/address s/Str
   (s/optional-key :onyx/id) (deprecated [:env-config :model :onyx/id])
   :onyx/tenancy-id TenancyId
   :onyx.peer/job-scheduler JobScheduler
   :onyx.messaging/impl Messaging
   :onyx.messaging/bind-addr s/Str
   (s/optional-key :onyx.log/config) (s/maybe {s/Any s/Any})
   (s/optional-key :onyx.messaging/peer-port) s/Int
   (s/optional-key :onyx.messaging/external-addr) s/Str
   (s/optional-key :onyx.peer/stop-task-timeout-ms) s/Int
   (s/optional-key :onyx.peer/inbox-capacity) s/Int
   (s/optional-key :onyx.peer/outbox-capacity) s/Int
   (s/optional-key :onyx.peer/retry-start-interval) s/Int
   (s/optional-key :onyx.peer/join-failure-back-off) s/Int
   (s/optional-key :onyx.peer/drained-back-off) s/Int
   (s/optional-key :onyx.peer/job-not-ready-back-off) s/Int
   (s/optional-key :onyx.peer/peer-not-ready-back-off) s/Int
   (s/optional-key :onyx.peer/fn-params) s/Any
   (s/optional-key :onyx.peer/backpressure-check-interval) s/Int
   (s/optional-key :onyx.peer/backpressure-low-water-pct) s/Int
   (s/optional-key :onyx.peer/backpressure-high-water-pct) s/Int
   (s/optional-key :onyx.peer/state-log-impl) StateLogImpl
   (s/optional-key :onyx.peer/state-filter-impl) StateFilterImpl
   (s/optional-key :onyx.peer/tags) [s/Keyword]
   (s/optional-key :onyx.peer/trigger-timer-resolution) PosInt
   (s/optional-key :onyx.bookkeeper/client-timeout) PosInt
   (s/optional-key :onyx.bookkeeper/client-throttle) PosInt
   (s/optional-key :onyx.bookkeeper/ledger-password) s/Str
   (s/optional-key :onyx.bookkeeper/ledger-id-written-back-off) PosInt
   (s/optional-key :onyx.bookkeeper/ledger-ensemble-size) PosInt
   (s/optional-key :onyx.bookkeeper/ledger-quorum-size) PosInt
   (s/optional-key :onyx.bookkeeper/write-batch-size) PosInt
   (s/optional-key :onyx.bookkeeper/write-buffer-size) PosInt
   (s/optional-key :onyx.bookkeeper/write-batch-backoff) PosInt
   (s/optional-key :onyx.bookkeeper/read-batch-size) PosInt
   (s/optional-key :onyx.rocksdb.filter/base-dir) s/Str
   (s/optional-key :onyx.rocksdb.filter/bloom-filter-bits) PosInt
   (s/optional-key :onyx.rocksdb.filter/compression) (s/enum :bzip2 :lz4 :lz4hc :none :snappy :zlib)
   (s/optional-key :onyx.rocksdb.filter/block-size) PosInt
   (s/optional-key :onyx.rocksdb.filter/peer-block-cache-size) PosInt
   (s/optional-key :onyx.rocksdb.filter/num-buckets) PosInt
   (s/optional-key :onyx.rocksdb.filter/num-ids-per-bucket) PosInt
   (s/optional-key :onyx.rocksdb.filter/rotation-check-interval-ms) PosInt
   (s/optional-key :onyx.zookeeper/backoff-base-sleep-time-ms) s/Int
   (s/optional-key :onyx.zookeeper/backoff-max-sleep-time-ms) s/Int
   (s/optional-key :onyx.zookeeper/backoff-max-retries) s/Int
   (s/optional-key :onyx.zookeeper/prepare-failure-detection-interval) s/Int
   (s/optional-key :onyx.messaging/inbound-buffer-size) s/Int
   (s/optional-key :onyx.messaging/completion-buffer-size) s/Int
   (s/optional-key :onyx.messaging/release-ch-buffer-size) s/Int
   (s/optional-key :onyx.messaging/retry-ch-buffer-size) s/Int
   (s/optional-key :onyx.messaging/peer-link-gc-interval) s/Int
   (s/optional-key :onyx.messaging/peer-link-idle-timeout) s/Int
   (s/optional-key :onyx.messaging/ack-daemon-timeout) s/Int
   (s/optional-key :onyx.messaging/ack-daemon-clear-interval) s/Int
   (s/optional-key :onyx.messaging/decompress-fn) Function
   (s/optional-key :onyx.messaging/compress-fn) Function
   (s/optional-key :onyx.messaging/allow-short-circuit?) s/Bool
   (s/optional-key :onyx.messaging.aeron/embedded-driver?) s/Bool
   (s/optional-key :onyx.messaging.aeron/embedded-media-driver-threading) (s/enum :dedicated :shared :shared-network)
   (s/optional-key :onyx.messaging.aeron/subscriber-count) s/Int
   (s/optional-key :onyx.messaging.aeron/write-buffer-size) s/Int
   (s/optional-key :onyx.messaging.aeron/poll-idle-strategy) AeronIdleStrategy
   (s/optional-key :onyx.messaging.aeron/offer-idle-strategy) AeronIdleStrategy
   (s/optional-key :onyx.messaging.aeron/publication-creation-timeout) s/Int
   (s/optional-key :onyx.windowing/min-value) s/Int
   (s/optional-key :onyx.task-scheduler.colocated/only-send-local?) s/Bool
   s/Any s/Any})

(s/defschema PeerId
  (s/cond-pre s/Uuid s/Keyword))

(s/defschema GroupId
  (s/cond-pre s/Uuid s/Keyword))

(s/defschema PeerState
  (s/enum :idle :backpressure :active))

(s/defschema PeerSite
  {s/Any s/Any})

(s/defschema JobId
  (s/cond-pre s/Uuid s/Keyword))

(s/defschema TaskId
  (s/cond-pre s/Uuid s/Keyword))

(s/defschema TaskScheduler
  s/Keyword)

(s/defschema SlotId
  s/Int)

(s/defschema Replica
  {:job-scheduler JobScheduler
   :messaging {:onyx.messaging/impl Messaging s/Keyword s/Any}
   :peers [PeerId]
   :orphaned-peers {GroupId [PeerId]}
   :groups [GroupId]
   :groups-index {GroupId #{PeerId}}
   :groups-reverse-index {GroupId GroupId}
   :peer-state {PeerId PeerState}
   :peer-sites {PeerId PeerSite}
   :prepared {GroupId GroupId}
   :accepted {GroupId GroupId}
   :aborted #{GroupId}
   :left #{GroupId}
   :pairs {GroupId GroupId}
   :jobs [JobId]
   :task-schedulers {JobId TaskScheduler}
   :tasks {JobId [TaskId]}
   :allocations {JobId {TaskId [PeerId]}}
   :task-metadata {JobId {TaskId s/Any}}
   :saturation {JobId s/Num}
   :task-saturation {JobId {TaskId s/Num}}
   :flux-policies {JobId {TaskId s/Any}}
   :min-required-peers {JobId {TaskId s/Num}}
   :input-tasks {JobId [TaskId]}
   :output-tasks {JobId [TaskId]}
   :exempt-tasks  {JobId [TaskId]}
   :sealed-outputs {JobId #{TaskId}}
   :ackers {JobId [PeerId]}
   :acker-percentage {JobId s/Int}
   :acker-exclude-inputs {JobId s/Bool}
   :acker-exclude-outputs {JobId s/Bool}
   :task-percentages {JobId {TaskId s/Num}}
   :percentages {JobId s/Num}
   :completed-jobs [JobId]
   :killed-jobs [JobId]
   :state-logs {JobId {TaskId {SlotId [s/Int]}}}
   :state-logs-marked #{s/Int}
   :task-slot-ids {JobId {TaskId {PeerId SlotId}}}
   :exhausted-inputs {JobId #{TaskId}}
   :required-tags {JobId {TaskId [s/Keyword]}}
   :peer-tags {PeerId [s/Keyword]}})

(s/defschema LogEntry
  {:fn s/Keyword
   :args {s/Any s/Any}
   (s/optional-key :message-id) s/Int
   (s/optional-key :created-at) s/Int
   (s/optional-key :peer-parent) s/Uuid
   (s/optional-key :entry-parent) s/Int})

(s/defschema Reactions
  (s/maybe [LogEntry]))

(s/defschema ReplicaDiff
  (s/maybe (s/cond-pre {s/Any s/Any} #{s/Any})))

(s/defschema State
  {s/Any s/Any})

(declare lookup-schema)

(defn type->schema [doc-name->schema t]
  (if (sequential? t)
    (mapv (partial lookup-schema doc-name->schema) t)
    (lookup-schema doc-name->schema t)))

(defn information-model->schema [doc-name->schema information]
  (let [model-type (:type information)
        model (:model information)]
    (if model
      (reduce (fn [m [k km]]
                (let [optional? (:optional? km)
                      schema-value (if-let [choices (:choices km)]
                                     (apply s/enum choices)
                                     (type->schema doc-name->schema (:type km)))]
                  (case model-type
                    :record (assoc m
                                   k
                                   (if optional? (s/maybe schema-value) schema-value))

                    :map (assoc m
                                (if optional? (s/optional-key k) k)
                                schema-value))))
              {}
              model))))

(defn lookup-schema [doc-name->schema k]
  (or (doc-name->schema k)
      (information-model->schema doc-name->schema (i/model k))
      (throw (Exception. (format "Unable to lookup schema for type %s." k)))))

(defn add-event-schema [doc-name->schema]
  (assoc doc-name->schema
         :event-map
         (-> (information-model->schema doc-name->schema (i/model :event-map))
             (assoc (restricted-ns :onyx.core) s/Any))))

(defn add-state-event-schema [doc-name->schema]
  (assoc doc-name->schema
         :state-event
         (-> (information-model->schema doc-name->schema (i/model :state-event))
             (assoc s/Any s/Any))))

(def schema-name->schema
  (-> {:integer s/Num
       :boolean s/Bool
       :keyword s/Keyword
       :any s/Any
       :atom clojure.lang.Atom
       :segment s/Any
       :peer-config PeerConfig
       :catalog-entry TaskMap
       :window-entry Window
       :trigger-entry Trigger
       :lifecycle-entry Lifecycle
       :workflow Workflow
       :uuid s/Uuid
       :flow-conditions-entry FlowCondition
       :job-metadata {s/Any s/Any}
       :function Function
       :string s/Str
       ;; To further restrict in the future
       :results s/Any
       :replica-atom s/Any
       :peer-replica-view-atom s/Any
       :windows-state-atom s/Any
       :map {s/Any s/Any}
       :serialized-task s/Any
       :channel s/Any
       :record s/Any
       :peer-state-atom s/Any}
      add-event-schema
      add-state-event-schema))

(s/defschema Event
  (:event-map schema-name->schema))

(s/defschema StateEvent
  (:state-event schema-name->schema))

(s/defschema WindowState
  (s/constrained
   {:window-extension WindowExtension
    :trigger-states [TriggerState]
    :window Window
    :state {s/Any s/Any}
    :state-event (s/maybe StateEvent)
    :event-results [StateEvent]
    :init-fn Function
    :create-state-update Function
    :apply-state-update Function
    :super-agg-fn (s/maybe Function)
    (s/optional-key :new-window-state-fn) Function
    (s/optional-key :grouping-fn) (s/cond-pre s/Keyword Function)}
   record? 'record?))
