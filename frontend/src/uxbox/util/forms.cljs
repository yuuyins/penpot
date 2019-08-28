;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.forms
  (:refer-clojure :exclude [uuid])
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s :include-macros true]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [struct.core :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

;; --- Main Api

(defn validate
  [data spec]
  (st/validate data spec))

(defn valid?
  [data spec]
  (st/valid? data spec))

;; --- Handlers Helpers

(defn- impl-mutator
  [v update-fn]
  (specify v
    IReset
    (-reset! [_ new-value]
      (update-fn new-value))

    ISwap
    (-swap!
      ([self f] (update-fn f))
      ([self f x] (update-fn #(f % x)))
      ([self f x y] (update-fn #(f % x y)))
      ([self f x y more] (update-fn #(apply f % x y more))))))

(defn- translate-error-type
  [code]
  (case code
    ::st/string "errors.form.string"
    ::st/number "errors.form.number"
    ::st/number-str "errors.form.number"
    ::st/integer "errors.form.integer"
    ::st/integer-str "errors.form.integer"
    ::st/required "errors.form.required"
    ::st/email "errors.form.email"
    ::st/identical-to "errors.form.does-not-match"
    "errors.undefined-error"))

(defn- translate-errors
  [errors]
  (reduce-kv (fn [acc key val]
               (if (string? (:message val))
                 (assoc acc key val)
                 (->> (translate-error-type (:code val))
                      (assoc val :message)
                      (assoc acc key))))
             {} errors))

(defn use-form
  [{:keys [initial spec] :as opts}]
  (let [[state update-state] (mf/useState {:data (if (fn? initial) (initial) initial)
                                           :errors {}
                                           :touched {}})
        [errors clean-data] (validate spec (:data state))
        errors (merge (translate-errors errors)
                      (:errors state))]
    (-> (assoc state
               :errors errors
               :clean-data clean-data
               :valid (not (seq errors)))
        (impl-mutator update-state))))

(defn on-input-change
  [{:keys [data] :as form} field]
  (fn [event]
    (let [target (dom/get-target event)
          value (dom/get-value target)]
      (swap! form (fn [state]
                    (-> state
                        (assoc-in [:data field] value)
                        (update :errors dissoc field)))))))

(defn on-input-blur
  [{:keys [touched] :as form} field]
  (fn [event]
    (let [target (dom/get-target event)]
      (when-not (get touched field)
        (swap! form assoc-in [:touched field] true)))))

;; --- Helper Components

(mf/defc field-error
  [{:keys [form field type]
    :or {only (constantly true)}
    :as props}]
  (let [touched? (get-in form [:touched field])
        {:keys [message code] :as error} (get-in form [:errors field])]
    (when (and touched? error
               (cond
                 (nil? type) true
                 (ifn? type) (type (:type error))
                 (keyword? type) (= (:type error) type)
                 :else false))
      [:ul.form-errors
       [:li {:key code} (tr message)]])))

(defn error-class
  [form field]
  (when (and (get-in form [:errors field])
             (get-in form [:touched field]))
    "invalid"))

;; --- Additional Validators

(def string (assoc st/string :message "errors.should-be-string"))
(def number (assoc st/number :message "errors.should-be-number"))
(def number-str (assoc st/number-str :message "errors.should-be-number"))
(def integer (assoc st/integer :message "errors.should-be-integer"))
(def integer-str (assoc st/integer-str :message "errors.should-be-integer"))
(def required (assoc st/required :message "errors.required"))
(def email (assoc st/email :message "errors.should-be-valid-email"))
(def uuid (assoc st/uuid :message "errors.should-be-uuid"))
(def uuid-str (assoc st/uuid-str :message "errors.should-be-valid-uuid"))

;; DEPRECATED

;; --- Form Validation Api

;; --- Form Specs and Conformers

(def ^:private email-re
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def ^:private number-re
  #"^[-+]?[0-9]*\.?[0-9]+$")

(def ^:private color-re
  #"^#[0-9A-Fa-f]{6}$")

(s/def ::email
  (s/and string? #(boolean (re-matches email-re %))))

(s/def ::non-empty-string
  (s/and string? #(not (str/empty? %))))

(defn- parse-number
  [v]
  (cond
    (re-matches number-re v) (js/parseFloat v)
    (number? v) v
    :else ::s/invalid))

(s/def ::string-number
  (s/conformer parse-number str))

(s/def ::color
  (s/and string? #(boolean (re-matches color-re %))))

;; --- Form State Events

;; --- Assoc Error

(defrecord AssocError [type field error]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type field] error)))

(defn assoc-error
  ([type field]
   (assoc-error type field nil))
  ([type field error]
   {:pre [(keyword? type)
          (keyword? field)
          (any? error)]}
   (AssocError. type field error)))

;; --- Assoc Errors

(defrecord AssocErrors [type errors]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] errors)))

(defn assoc-errors
  ([type]
   (assoc-errors type nil))
  ([type errors]
   {:pre [(keyword? type)
          (or (map? errors)
              (nil? errors))]}
   (AssocErrors. type errors)))

;; --- Assoc Value

(declare clear-error)

(defrecord AssocValue [type field value]
  ptk/UpdateEvent
  (update [_ state]
    (let [form-path (into [:forms type] (if (coll? field) field [field]))]
      (assoc-in state form-path value)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (clear-error type field))))

(defn assoc-value
  [type field value]
  {:pre [(keyword? type)
         (keyword? field)
         (any? value)]}
  (AssocValue. type field value))

;; --- Clear Values

(defrecord ClearValues [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:forms type] nil)))

(defn clear-values
  [type]
  {:pre [(keyword? type)]}
  (ClearValues. type))

;; --- Clear Error

(deftype ClearError [type field]
  ptk/UpdateEvent
  (update [_ state]
    (let [errors (get-in state [:errors type])]
      (if (map? errors)
        (assoc-in state [:errors type] (dissoc errors field))
        (update state :errors dissoc type)))))

(defn clear-error
  [type field]
  {:pre [(keyword? type)
         (keyword? field)]}
  (ClearError. type field))

;; --- Clear Errors

(defrecord ClearErrors [type]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:errors type] nil)))

(defn clear-errors
  [type]
  {:pre [(keyword? type)]}
  (ClearErrors. type))

;; --- Clear Form

(deftype ClearForm [type]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (clear-values type)
           (clear-errors type))))

(defn clear-form
  [type]
  {:pre [(keyword? type)]}
  (ClearForm. type))

;; --- Helpers

(defn focus-data
  [type state]
  (-> (l/in [:forms type])
      (l/derive state)))

(defn focus-errors
  [type state]
  (-> (l/in [:errors type])
      (l/derive state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form UI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mx/defc input-error
  [errors field]
  (when-let [error (get errors field)]
    [:ul.form-errors
     [:li {:key error} (tr error)]]))

(defn clear-mixin
  [store type]
  {:will-unmount (fn [own]
                   (ptk/emit! store (clear-form type))
                   own)})
