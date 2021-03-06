(ns frontend.components.document-access
  (:require [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn permission-denied-overlay [app owner]
  (reify
    om/IDisplayName (display-name [_] "Permission Denied Overlay")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :access-request/document (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [db cast!]} (om/get-shared owner)
            doc-id (:document/id app)
            document (d/entity @db doc-id)
            access-requests (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-request/document ?doc-id]] @db doc-id)]
        (html
         [:section.menu-view
          [:div.content
           [:h2.make "This document is " (if (:document/privacy document)
                                           (name (:document/privacy document))
                                           "private") "."]

           (if (:cust app)
             (if (seq access-requests)
               [:p.make
                [:span
                 "Okay, we notified the owner of this document about your request. "
                 "While you wait for a response, try prototyping in "]
                [:a {:href "/new"} "your own document"]
                [:span "."]]

               (list
                 [:p.make
                  "Anything you prototype here will only be visible to you. "
                  (if (:team app)
                    "Request access below."
                    (list
                     "You can try to request access or even"
                     [:a {:href "/new"} "create your own"]
                     " document."))]
                 [:div.make
                  [:div.menu-buttons
                   [:a.menu-button {:on-click #(cast! :permission-requested {:doc-id doc-id})
                                    :role "button"}
                    "Request Access"]]]))

             (list
               [:p.make
                "Anything you prototype here will only be visible to you and won't save. "
                "If you sign in with Google you can request access from the owner of this document."]
               [:div.calls-to-action.make
                (om/build common/google-login {:source "Permission Denied Menu"})]))]])))))

;; TODO is this used anymore, should we kill it?
(defn manage-permissions-overlay [app owner]
  (reify
    om/IDisplayName (display-name [_] "Manage Permissions Overlay")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %))
                                           (= :permission/document (:a %))
                                           (= :access-grant/document (:a %))
                                           (= :access-request/document (:a %))
                                           (= :access-request/status (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))

    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            private? (= :document.privacy/private (:document/privacy doc))
            permission-grant-email (get-in app state/permission-grant-email-path)
            permissions (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :permission/document ?doc-id]] @db doc-id)
            access-grants (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-grant/document ?doc-id]] @db doc-id)
            access-requests (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-request/document ?doc-id]] @db doc-id)
            {pending-requests false denied-requests true} (group-by #(= :access-request.status/denied (:access-request/status %))
                                                                    access-requests)]
        (html
         [:section.menu-view
          [:section.menu-view-frame
           [:article
            [:label
             [:input {:type "checkbox"
                      :checked private?
                      :onChange #(cast! :document-privacy-changed
                                        {:doc-id doc-id
                                         :setting (if private?
                                                    :document.privacy/public
                                                    :document.privacy/private)})}]
             (if private?
               "Uncheck to make document public"
               "Check to make document private")]
            (when private?
              (list
               [:p "Grant access to a colleague"]
               [:form.menu-invite-form {:on-submit #(do (cast! :permission-grant-submitted)
                                                        false)
                                        :on-key-down #(when (= "Enter" (.-key %))
                                                        (cast! :permission-grant-submitted)
                                                        false)}
                [:input {:type "text"
                         :required "true"
                         :data-adaptive ""
                         :value (or permission-grant-email "")
                         :on-change #(cast! :permission-grant-email-changed {:value (.. % -target -value)})}]
                [:label {:data-placeholder "Teammate's Email"
                         :data-placeholder-nil "What's your teammate's email?"
                         :data-placeholder-forgot "Don't forget to submit."}]]
               (when (or (seq permissions)
                         (seq access-grants))
                 "People with access:")
               (for [p (sort-by :db/id permissions)]
                 [:div (:permission/cust p)])
               (for [g (sort-by :db/id access-grants)]
                 [:div (:access-grant/email g)])
               (when (seq pending-requests)
                 "People requesting access:")
               (for [r (sort-by :db/id pending-requests)]
                 [:div (:access-request/cust r)
                  " "
                  [:a {:role "button"
                       :on-click #(cast! :access-request-granted {:request-id (:db/id r)
                                                                  :doc-id doc-id})}
                   "Grant access"]
                  " "
                  [:a {:role "button"
                       :on-click #(cast! :access-request-denied {:request-id (:db/id r)
                                                                 :doc-id doc-id})}
                   "Deny access"]])
               (when (seq denied-requests)
                 [:div
                  "Denied requests:"
                  (for [r (sort-by :db/id denied-requests)]
                    [:div (:access-request/cust r)
                     " "
                     [:a {:role "button"
                          :on-click #(cast! :access-request-granted {:request-id (:db/id r)
                                                                     :doc-id doc-id})}
                      "Grant access"]])])))]]])))))
