;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.pdf
  "A pdf renderer."
  (:require
   ["path" :as path]
   [app.browser :as bw]
   [app.common.exceptions :as ex :include-macros true]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.renderer.svg :refer [render-object]]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(defn create-cookie
  [uri token]
  (let [domain (str (:host uri)
                (when (:port uri)
                  (str ":" (:port uri))))]
    {:domain domain
     :key "auth-token"
     :value token}))

(defn- clean-tmp-data
  [tdpath data]
  (p/do!
    (sh/rmdir! tdpath)
    data))

(defn pdf-from-object
  [svg-content {:keys [file-id]}]
  (p/let [tdpath (sh/create-tmpdir! "pdfexport-")
          tfpath (path/join tdpath (str file-id))
          svgpath  (str tfpath ".svg")
          pdfpath  (str tfpath ".pdf")]
    (sh/write-file! svgpath svg-content)
    (sh/run-cmd! (str "cairosvg -o " pdfpath " " svgpath))
    (p/let [content (sh/read-file pdfpath)]
      (clean-tmp-data tdpath content))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)
(s/def ::save-path ::us/string)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::filename ::save-path]))

(defn render
  [params]
  (us/assert ::render-params params)
  (p/let [svg-content (render-object params)
          content (pdf-from-object svg-content params)]
    {:content content
     :filename (or (:filename params)
                   (str (:name params)
                        (:suffix params "")
                        ".pdf"))
     :length (alength content)
     :mime-type "application/pdf"}))

