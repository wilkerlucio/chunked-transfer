(ns com.wsscode.chunked-transfer
  (:require
    [clojure.core.async :as async :refer [<! >!]]
    [clojure.core.async.impl.protocols :as async.prot]
    [com.wsscode.misc.coll :as coll]))

(declare
  element-meta open-map close-map map-entry open-vector open-set open-list
  close-vector close-set close-list)

(defrecord FinalValue [value])

(defn chan?
  "Check if c is a core.async channel."
  [c]
  (satisfies? async.prot/ReadPort c))

; region encode

(def special-elements
  #{`element-meta
    `open-map
    `close-map
    `map-entry
    `open-vector
    `open-set
    `open-list
    `close-vector
    `close-set
    `close-list
    ::head-root
    ::entry-key
    ::entry-val
    ::meta
    ::meta-val})

(defn encode
  ([x] (encode [] x))
  ([out x]
   (cond
     (seq (meta x))
     (-> out
         (conj `element-meta)
         (encode (meta x))
         (encode (with-meta x nil)))

     (coll/native-map? x)
     (as-> out <>
       (conj <> `open-map)
       (reduce
         (fn [out entry]
           (encode out entry))
         <>
         x)
       (conj <> `close-map))

     (map-entry? x)
     (-> out
         (conj `map-entry)
         (encode (key x))
         (encode (val x)))

     (vector? x)
     (as-> out <>
       (conj <> `open-vector)
       (reduce
         (fn [out entry]
           (encode out entry))
         <>
         x)
       (conj <> `close-vector))

     (set? x)
     (as-> out <>
       (conj <> `open-set)
       (reduce
         (fn [out entry]
           (encode out entry))
         <>
         x)
       (conj <> `close-set))

     (sequential? x)
     (as-> out <>
       (conj <> `open-list)
       (reduce
         (fn [out entry]
           (encode out entry))
         <>
         x)
       (conj <> `close-list))

     (contains? special-elements x)
     (throw (ex-info (str "User sent reserved value: " (pr-str x)) {:value x}))

     :else
     (conj out x))))

(defn encode-async*
  ([out-chan x]
   (async/go
     (cond
       (seq (meta x))
       (when (>! out-chan `element-meta)
         (<! (encode-async* out-chan (meta x)))
         (<! (encode-async* out-chan (with-meta x nil))))

       (map-entry? x)
       (when (>! out-chan `map-entry)
         (<! (encode-async* out-chan (key x)))
         (<! (encode-async* out-chan (val x))))

       (coll/native-map? x)
       (when (>! out-chan `open-map)
         (doseq [entry x]
           (<! (encode-async* out-chan entry)))
         (>! out-chan `close-map))

       (vector? x)
       (when (>! out-chan `open-vector)
         (doseq [entry x]
           (<! (encode-async* out-chan entry)))
         (>! out-chan `close-vector))

       (set? x)
       (when (>! out-chan `open-set)
         (doseq [entry x]
           (<! (encode-async* out-chan entry)))
         (>! out-chan `close-set))

       (sequential? x)
       (when (>! out-chan `open-list)
         (doseq [entry x]
           (<! (encode-async* out-chan entry)))
         (>! out-chan `close-list))

       (contains? special-elements x)
       (do
         (async/close! out-chan)
         (println (ex-info (str "User sent reserved value: " (pr-str x)) {:value x})))

       :else
       (>! out-chan x)))))

(defn encode-async!
  "Start sending ops to that channel encoding x. Returns the channel, note
  when the channel is full, the encoding process will park until slots are released.
  Also note you must not use a channel with a sliding/dropping buffer, dropping any
  ops will cause a corrupted data structure in the other end."
  [out-chan x]
  (async/go
    (<! (encode-async* out-chan x))
    (async/close! out-chan)
    out-chan))

(defn chunked-send!
  "Helper to make the process of sending chunks of steps to some external source.
  The buffer-size will decide how many chunks to progress while chunk-size will
  define the number of steps to send at each chunk. f is fn that receives chunk-size
  number of ops once they are ready. f may return a core-async channel. In case it
  does, the sender will wait for that channel to return a message before consuming
  the next chunk."
  [x buffer-size chunk-size f]
  (let [c (async/chan buffer-size (partition-all chunk-size))]
    (encode-async! c x)
    (async/go-loop []
      (when-let [x (<! c)]
        (let [res (f x)]
          (when (chan? res) (<! res)))
        (recur)))))

; endregion

; region decode

(defn- terminate-value [[head & stack] x]
  (case head
    ::head-root
    (->FinalValue x)

    ::entry-key
    (conj stack x ::entry-val)

    ::entry-val
    (let [[k m & stack'] stack]
      (conj stack' (conj (assoc! m k x))))

    ::meta
    (conj stack x ::meta-val)

    ::meta-val
    (let [[meta & stack'] stack]
      (terminate-value stack' (with-meta x meta)))

    (conj stack (conj! head x))))

(defn- open-seq [stack blank-type]
  (conj stack (transient blank-type)))

(defn- close-seq [[head & stack]]
  (terminate-value stack (persistent! head)))

(def decode-init-state
  '(::head-root))

(defn decode-step
  "Consumes a single decode operation step. Moves the state to the next iteration applying the op."
  [state op]
  (case op
    com.wsscode.chunked-transfer/open-vector
    (open-seq state [])

    com.wsscode.chunked-transfer/open-map
    (open-seq state {})

    com.wsscode.chunked-transfer/open-set
    (open-seq state #{})

    com.wsscode.chunked-transfer/open-list
    (open-seq state [])

    (com.wsscode.chunked-transfer/close-vector
      com.wsscode.chunked-transfer/close-map
      com.wsscode.chunked-transfer/close-set)
    (close-seq state)

    com.wsscode.chunked-transfer/close-list
    (let [[head & stack] state]
      (terminate-value stack (or (seq (persistent! head)) '())))

    com.wsscode.chunked-transfer/map-entry
    (conj state ::entry-key)

    com.wsscode.chunked-transfer/element-meta
    (conj state ::meta)

    (terminate-value state op)))

(defn decode-sync
  "Decode ops is a helper to get one sequence with all the ops to rebuild a structure, and process it."
  [ops]
  (let [final (reduce decode-step decode-init-state ops)]
    (if (instance? FinalValue final)
      (:value final)
      (throw (ex-info "Incomplete ops, chunked data was incomplete" {})))))

(defn decode-async
  "Takes a channel that spits chunks of ops, returns a promise channel that will output the restored
  value once all the chunks are consumed."
  [in-chan]
  (let [c (async/promise-chan)]
    (async/go-loop [state decode-init-state]
      (if-some [ops (<! in-chan)]
        (let [state' (reduce decode-step state ops)]
          (if (instance? FinalValue state')
            (>! c (:value state'))
            (recur state')))
        (async/close! c)))
    c))

; endregion

(comment
  (encode 3)
  (encode "foo")

  (encode ["foo" :bar])
  (encode ^:some-meta {"foo" :bar})

  (encode {"foo"                        :bar
           ^:stuff [:cool #{"nesting"}] {:complex ["value"]}})

  (-> (encode {"foo"                        :bar
               ^:stuff [:cool #{"nesting"}] {:complex ["value"]}})
      decode-sync)

  (let [ch (async/chan 3 (partition-all 5))]
    (encode-async! ch {"foo"                        :bar
                       ^:stuff [:cool #{"nesting"}] {:complex ["value"]}})
    (async/<!! (decode-async ch)))

  (chunked-send! (range 20) 2 5
                 (fn [chunk]
                   (println "SEND" chunk))))

