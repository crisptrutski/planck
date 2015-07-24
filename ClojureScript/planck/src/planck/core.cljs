(ns planck.core
  (:require [cljs.js :as cljs]
            [cljs.pprint :refer [pprint]]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
            [cljs.analyzer :as ana]
            [cljs.repl :as repl]
            [clojure.string :as s]
            [cljs.env]))

(def st (cljs/empty-state))

(def current-ns (atom 'cljs.user))

(defn ^:export setup-cljs-user []
  (comment
    (js/eval "goog.provide('cljs.user')")
    (js/eval "goog.require('cljs.core')")))

(defn repl-read-string [line]
  (r/read-string {:read-cond :allow :features #{:cljs}} line))

(defn ^:export is-readable? [line]
  (binding [r/*data-readers* tags/*cljs-data-readers*]
    (try
      (repl-read-string line)
      true
      (catch :default _
        false))))

(defn ns-form? [form]
  (and (seq? form) (= 'ns (first form))))

(def repl-specials '#{in-ns doc})

(defn repl-special? [form]
  (and (seq? form) (repl-specials (first form))))

(def repl-special-doc-map
  '{in-ns {:arglists ([name])
           :doc      "Sets *cljs-ns* to the namespace named by the symbol, creating it if needed."}
    doc   {:arglists ([name])
           :doc      "Prints documentation for a var or special form given its name"}})

(defn- repl-special-doc [name-symbol]
  (assoc (repl-special-doc-map name-symbol)
    :name name-symbol
    :repl-special-function true))

;; Copied from cljs.analyzer.api (which hasn't yet been converted to cljc)
(defn resolve
  "Given an analysis environment resolve a var. Analogous to
   clojure.core/resolve"
  [env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (ana/resolve-var env sym
      (ana/confirm-var-exists-throw))
    (catch :default _
      (ana/resolve-macro-var env sym))))

(defn ^:export print-prompt []
  (print (str @current-ns "=> ")))

(defn form-full-path [relative-path extension]
  (str "/tmp/test-planck-src/" relative-path extension))

(defn extension->lang [extension]
  (if (= ".js" extension)
    :js
    :clj))

(defn load-and-callback! [path extension cb]
  (let [full-path (form-full-path path extension)]
    #_(println "Trying to load" full-path)
    (cb {:lang   (extension->lang extension)
         :source (cljs.user/slurp full-path)})))

(defn ^:export read-eval-print [line]
  #_(println "Line passed to eval:" line)
  (binding [ana/*cljs-ns* @current-ns
            *ns* (create-ns @current-ns)
            r/*data-readers* tags/*cljs-data-readers*]
    (let [env (assoc (ana/empty-env) :context :expr
                                     :ns {:name @current-ns})
          form (repl-read-string line)]
      (if (repl-special? form)
        (case (first form)
          in-ns (reset! current-ns (second (second form)))
          doc (if (repl-specials (second form))
                (repl/print-doc (repl-special-doc (second form)))
                (repl/print-doc
                  (let [sym (second form)
                        var (resolve env sym)]
                    (:meta var)))))
        (cljs/eval-str
          st
          line
          nil
          {:load          (fn [{:keys [name macros path]} cb]
                            (loop [extensions (if macros
                                                [".clj" ".cljc"]
                                                [".cljs" ".cljc" ".js"])]
                              (if extensions
                                (try
                                  (load-and-callback! path (first extensions) cb)
                                  (catch :default _
                                    (recur (next extensions))))
                                (cb nil))))
           :eval          (fn [{:keys [source]}]
                            (try
                              {:result (js/eval source)}
                              (catch :default e
                                {:error     true
                                 :exception e})))
           :verbose       true
           #_:context       #_:expr
           :def-emits-var true}
          (fn [{:keys [ns value] :as ret}]
            #_(prn ret)
            (if-not (:error value)
              (let [result (:result value)]
                (prn result)
                (when-not
                  (or ('#{*1 *2 *3 *e} form)
                    (ns-form? form))
                  (set! *3 *2)
                  (set! *2 *1)
                  (set! *1 result))
                (reset! current-ns ns))
              (let [e (:exception value)]
                (set! *e e)
                (print (.-message e) "\n"
                  (first (s/split (.-stack e) #"eval code")))))))))))