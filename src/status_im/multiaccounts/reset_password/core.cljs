(ns status-im.multiaccounts.reset-password.core
  (:require [re-frame.core :as re-frame]
            [status-im.utils.fx :as fx]
            [status-im.utils.types :as types]
            [clojure.string :as string]
            [status-im.utils.security :as security]
            [status-im.popover.core :as popover]
            [status-im.native-module.core :as status]
            [status-im.ethereum.core :as ethereum]))

(fx/defn on-input-change
  {:events [::handle-input-change]}
  [{:keys [db]} input-id value]
  (let [new-password (get-in db [:multiaccount/reset-password-form-vals :new-password])
        error (when (and (= input-id :confirm-new-password)
                         (pos? (count new-password))
                         (pos? (count value))
                         (not= value new-password))
                :t/password-mismatch)]
    {:db (-> db
             (assoc-in [:multiaccount/reset-password-form-vals input-id] value)
             (assoc-in [:multiaccount/reset-password-errors input-id] error))}))

(fx/defn clear-form-vals
  {:events [::clear-form-vals]}
  [{:keys [db]}]
  {:db (dissoc db :multiaccount/reset-password-form-vals :multiaccount/reset-password-errors)})

(fx/defn set-current-password-error
  {:events [::handle-verification-error ::password-reset-error]}
  [{:keys [db]} error]
  {:db (assoc-in db [:multiaccount/reset-password-errors :current-password] error)})

(fx/defn password-reset-success
  {:events [::password-reset-success]}
  [{:keys [db] :as cofx}]
  (fx/merge cofx
            {:db (dissoc
                  db
                  :multiaccount/reset-password-form-vals
                  :multiaccount/reset-password-errors
                  :multiaccount/reset-password-next-enabled?)}
            (popover/show-popover {:view :password-reset-success})))

(defn change-db-password-cb [res]
  (let [{:keys [error]} (types/json->clj res)]
    (if (not (string/blank? error))
      (re-frame/dispatch [::password-reset-error error])
      (re-frame/dispatch [::password-reset-success]))))

(re-frame/reg-fx
 ::change-db-password
 (fn [[key-uid {:keys [current-password new-password]}]]
   (status/reset-password
    key-uid
    (ethereum/sha3 (security/safe-unmask-data current-password))
    (ethereum/sha3 (security/safe-unmask-data new-password))
    change-db-password-cb)))

(fx/defn handle-verification-success
  {:events [::handle-verification-success]}
  [{:keys [db]} form-vals]
  (let [{:keys [key-uid name]} (:multiaccount db)]
    {::change-db-password [key-uid form-vals]}))

(defn handle-verification [form-vals result]
  (let [{:keys [error]} (types/json->clj result)]
    (if (not (string/blank? error))
      (re-frame/dispatch [::handle-verification-error :t/wrong-password])
      (re-frame/dispatch [::handle-verification-success form-vals]))))

(re-frame/reg-fx
 ::validate-current-password-and-reset
 (fn  [{:keys [address current-password] :as form-vals}]
   (let [hashed-pass (ethereum/sha3 (security/safe-unmask-data current-password))]
     (status/verify address hashed-pass
                    (partial handle-verification form-vals)))))

(fx/defn reset
  {:events [::reset]}
  [{:keys [db]} form-vals]
  {::validate-current-password-and-reset
   (assoc form-vals
          :address
          (get-in db [:multiaccount :wallet-root-address]))})
