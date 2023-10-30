(ns com.wsscode.chunked-transfer-test
  (:require
    [clojure.core.async :as async :refer [<! <!!]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [com.wsscode.chunked-transfer :as ct]))

(defn encode-async [x]
  (let [c (async/chan 1024)]
    (<!! (ct/encode-async! c x))
    (<!! (async/into [] c))))

(defspec round-trip 10000
  (prop/for-all [x gen/any-printable-equatable]
    (let [enc1 (ct/encode x)
          dec  (ct/decode-sync enc1)]
      (and (= x dec)
           (= (ct/encode x) enc1)))))

(defspec encode=encode-async 10000
  (prop/for-all [x gen/any-printable-equatable]
    (= (encode-async x) (ct/encode x))))

(comment
  (clojure.test.check/quick-check
    10000
    (prop/for-all [x gen/any-printable-equatable]
      (let [enc1 (ct/encode x)
            dec  (ct/decode-sync enc1)]
        (and (= x dec)
             (= (ct/encode x) enc1)))))

  (let [x '()]
    (let [enc1 (ct/encode x)
          dec  (ct/decode-sync enc1)]
      (tap> dec)
      (and (= x dec)
           #_(= (ct/encode x) enc1))))

  (-> (ct/encode ^:foo {:a 1 :b ^:bar []})
      decode
      :b
      meta)

  (-> (ct/encode ^:foo {:a ^:bar {:c 1}})
      decode)

  (encode-async @user/portal)

  (conj! '() :a)
  (gen/generate
    (gen/recursive-gen container-type gen/simple-type-printable-equatable)
    3000)

  (def res *1)

  (ct/chunked-send! res 32 500 #(tap> ["XXX" %]))

  (def a @user/portal)
  (def b @user/portal)
  (reduce ct/decode-step ct/decode-init-state (concat a b))

  (let [input @user/portal]
    (let [c (async/chan 1024 (partition-all 10))]
      (ct/encode-async! c input)
      (async/go-loop []
        (when-let [x (<! c)]
          (tap> ["GOT" x])
          (recur)))))

  (def ex *1)

  (decode (ct/encode ex)))
