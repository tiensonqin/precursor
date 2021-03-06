(ns frontend.db
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close! put!]]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.db.trans :as trans]
            [frontend.sente :as sente]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer (dissoc-in)]
            [om.core :as om]
            [taoensso.sente])
  (:import [goog.ui IdGenerator]))

(def doc-schema {:layer/child {:db/cardinality :db.cardinality/many}
                 :layer/points-to {:db/cardinality :db.cardinality/many
                                   :db/type :db.type/ref}
                 :error/id {:db/unique :db.unique/identity}
                 :permission/permits {:db/cardinality :db.cardinality/many}
                 :document/chat-bot {:db/type :db.type/ref}
                 :chat-bot/name {:db/unique :db.unique/identity}})

(def team-schema {:team/plan {:db/type :db.type/ref}
                  :plan/active-custs {:db/cardinality :db.cardinality/many}
                  :plan/invoices {:db/cardinality :db.cardinality/many
                                  :db/type :db.type/ref}})

(def issue-schema {:issue/votes {:db/type :db.type/ref
                                 :db/cardinality :db.cardinality/many}
                   :issue/comments {:db/type :db.type/ref
                                    :db/cardinality :db.cardinality/many}
                   :frontend/issue-id {:db/unique :db.unique/identity}
                   :comment/parent {:db/type :db.type/ref}})

(def schema (merge doc-schema team-schema issue-schema))

(def issue-ref-attrs (reduce (fn [acc [attr props]]
                               (if (keyword-identical? :db.type/ref (:db/type props))
                                 (conj acc attr)
                                 acc))
                             #{} issue-schema))

(defonce listeners (atom {}))

(defn ^:export inspect-listeners []
  (clj->js @listeners))

(defn generate-chat-bots [conn]
  [{:chat-bot/name "prcrsr"
    :db/id (trans/get-next-transient-id conn)}
   {:chat-bot/name "daniel"
    :db/id (trans/get-next-transient-id conn)}
   {:chat-bot/name "danny"
    :db/id (trans/get-next-transient-id conn)}])

(defn make-initial-db [initial-entities]
  (let [conn (d/create-conn schema)]
    (trans/reset-id conn)
    (d/transact! conn (concat (generate-chat-bots conn)
                              initial-entities)
                 {:server-update true})

    conn))

(defn reset-db! [db-atom initial-entities]
  (reset! db-atom @(make-initial-db initial-entities))
  (trans/reset-id db-atom)
  db-atom)

(defn send-datoms-to-server [sente-state sente-event datom-group annotations comms]
  (sente/send-msg sente-state [sente-event (merge {:datoms datom-group}
                                                  annotations)]
                  5000
                  (fn [reply]
                    (if (sente/cb-success? reply)
                      (when-let [rejects (seq (:rejected-datoms reply))]
                        (put! (:errors comms) [:datascript/rejected-datoms {:rejects rejects
                                                                            :sente-event sente-event
                                                                            :datom-group datom-group
                                                                            :annotations annotations}]))
                      (put! (:errors comms) [:datascript/sync-tx-error {:reason reply
                                                                        :sente-event sente-event
                                                                        :datom-group datom-group
                                                                        :annotations annotations}])))))

(defn handle-callbacks [db tx-report]
  (let [[eids attrs] (reduce (fn [[eids attrs] datom]
                               [(conj eids (:e datom))
                                (conj attrs (:a datom))])
                             [#{} #{}] (:tx-data tx-report))]
    (doseq [eid eids
            [k callback] (get-in @listeners [db :entity-listeners (str eid)])]
      (callback tx-report))
    (doseq [attr attrs
            [k callback] (get-in @listeners [db :attribute-listeners attr])]
      (callback tx-report))))

(defn setup-listener! [db key comms sente-event annotations undo-state sente-state]
  (d/listen!
   db
   key
   (fn [tx-report]
     ;; TODO: figure out why I can send tx-report through controls ch
     ;; (cast! :db-updated {:tx-report tx-report})
     (handle-callbacks db tx-report)
     (when (first (filter #(= :server/timestamp (:a %)) (:tx-data tx-report)))
       (put! (:controls comms) [:chat-db-updated []]))
     (when (-> tx-report :tx-meta :can-undo?)
       (swap! undo-state update-in [:transactions] conj tx-report)
       (when-not (-> tx-report :tx-meta :undo)
         (swap! undo-state assoc-in [:last-undo] nil)))
     (when-not (or (-> tx-report :tx-meta :server-update)
                   (-> tx-report :tx-meta :bot-layer)
                   (-> tx-report :tx-meta :frontend-only))
       (let [datoms (->> tx-report :tx-data (mapv ds/datom-read-api))]
         (doseq [datom-group (partition-all 1000 datoms)]
           (send-datoms-to-server sente-state sente-event datom-group annotations comms)))))))

(defn get-issue-id [e tx-report]
  (or (:frontend/issue-id (d/entity (:db-after tx-report) e))
      (:frontend/issue-id (d/entity (:db-before tx-report) e))))

(defn update-ref-attr [datom tx-report]
  (if (contains? issue-ref-attrs (:a datom))
    (update-in datom [:v] (fn [v] [:frontend/issue-id (get-issue-id v tx-report)]))
    datom))

(defn setup-issue-listener! [db key comms sente-state]
  (d/listen!
   db
   key
   (fn [tx-report]
     (handle-callbacks db tx-report)
     (when-not (or (-> tx-report :tx-meta :server-update)
                   (-> tx-report :tx-meta :bot-layer)
                   (-> tx-report :tx-meta :frontend-only))
       (let [datoms (->> tx-report
                      :tx-data
                      (mapv (fn [datom]
                              (-> datom
                                ds/datom-read-api
                                (update-in [:e]
                                           (fn [e]
                                             [:frontend/issue-id (get-issue-id e tx-report)]))
                                (update-ref-attr tx-report)))))]
         (doseq [datom-group (partition-all 1000 datoms)]
           (send-datoms-to-server sente-state :issue/transaction datom-group {} comms))))
     (let [cust-uuids (reduce (fn [acc d]
                                (if (contains? #{:issue/author :comment/author :vote/cust} (:a d))
                                  (conj acc (:v d))
                                  acc))
                              #{} (:tx-data tx-report))]
       (put! (:controls comms) [:new-cust-uuids {:uuids cust-uuids}])))))

(defn empty-db? [db]
  (empty? (d/datoms db :eavt)))

(defn generate-entity-id
  "Assumes that each entity has a different remainder and that all clients have
   the same multiple.
   Takes a db to guard against duplicate ids, but is not meant to be a protection
   against races. Only guards against a previous client with the same remainder skipping
   ids (probably due to an error).
   Returns a map of :entity-id and new :frontend-id-state"
  [db {:keys [multiple remainder next-id] :as frontend-id-state}]
  (let [eid (loop [res next-id]
              (if (first (d/datoms db :eavt res))
                (recur (+ res multiple))
                res))]
    {:entity-id eid
     :frontend-id-state (assoc frontend-id-state :next-id (+ eid multiple))}))

(defn generate-entity-ids
  "Same as generate-entity-id, but returns a list of :entity-ids"
  [db id-count frontend-id-state]
  (loop [i 0
         entity-ids []
         id-state frontend-id-state]
    (if (>= i id-count)
      {:entity-ids entity-ids
       :frontend-id-state id-state}
      (let [res (generate-entity-id db id-state)]
        (recur (inc i)
               (conj entity-ids (:entity-id res))
               (:frontend-id-state res))))))

(defn get-entity-id
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state]
  (let [{:keys [entity-id frontend-id-state]} (generate-entity-id @(:db app-state) (:frontend-id-state app-state))]
    {:entity-id entity-id :state (assoc app-state :frontend-id-state frontend-id-state)}))

(defn get-entity-ids
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state n]
  (let [{:keys [entity-ids frontend-id-state]} (generate-entity-ids @(:db app-state) n (:frontend-id-state app-state))]
    {:entity-ids entity-ids :state (assoc app-state :frontend-id-state frontend-id-state)}))

(defn add-entity-listener [conn eid key callback]
  (swap! listeners assoc-in [conn :entity-listeners (str eid) key] callback))

(defn add-attribute-listener [conn attribute key callback]
  (swap! listeners assoc-in [conn :attribute-listeners attribute key] callback))

(defn remove-entity-listener [conn eid key]
  (swap! listeners dissoc-in [conn :entity-listeners (str eid) key]))

(defn remove-attribute-listener [conn attribute key]
  (swap! listeners dissoc-in [conn :attribute-listeners attribute key]))

(defn add-unmounting-entity-listener [owner conn eid callback]
  (om/update-state-nr! owner (fn [s]
                               (update-in s [::listener-key] #(or % (.getNextUniqueId (.getInstance IdGenerator))))))
  (let [key (om/get-state owner ::listener-key)]
    (add-entity-listener conn eid key (fn [tx-report]
                                        (if (om/mounted? owner)
                                          (callback tx-report)
                                          (remove-entity-listener conn eid key))))))

(defn add-unmounting-attribute-listener [owner conn attribute callback]
  (om/update-state-nr! owner (fn [s]
                               (update-in s [::listener-key] #(or % (.getNextUniqueId (.getInstance IdGenerator))))))
  (let [key (om/get-state owner ::listener-key)]
    (add-attribute-listener conn attribute key (fn [tx-report]
                                                 (if (om/mounted? owner)
                                                   (callback tx-report)
                                                   (remove-attribute-listener conn attribute key))))))

(defn watch-doc-name-changes [owner]
  (add-unmounting-attribute-listener owner
                                     (om/get-shared owner :db)
                                     :document/name
                                     (fn [tx-report]
                                       ;; this is too often, but it's also way easier than trying to
                                       ;; figure out which doc-id is current.
                                       ;; If it's too slow, we can use _app-state-do-not-use
                                       (om/refresh! owner))))
