# Chunked Transfer [![Clojars Project](https://img.shields.io/clojars/v/com.wsscode/chunked-transfer.svg)](https://clojars.org/com.wsscode/chunked-transfer) ![Test](https://github.com/wilkerlucio/chunked-transfer/workflows/Test/badge.svg) [![cljdoc badge](https://cljdoc.xyz/badge/com.wsscode/chunked-transfer)](https://cljdoc.xyz/d/com.wsscode/chunked-transfer/CURRENT) <a href="https://babashka.org" rel="nofollow"><img src="https://github.com/babashka/babashka/raw/master/logo/badge.svg" alt="bb compatible" style="max-width: 100%;"></a>

Algorithm that allows the serialization and deserialization of Clojure data structures in chunks.

## Install

```clojure
com.wsscode/chunked-transfer {:mvn/version "VERSION"}
```

## Motivation

The reason this library exists is to solve the problem with encoding/decoding of large data structures in web interfaces.

As you might know, the UI in the browser only runs in a single thread, so when the UI needs to send or receive a large
data structure, the UI freezes when it tries to do the whole computation in a single frame. The solution this library
implements is about breaking that serialize/deserialize process in chunks, so we can split the computation across
different frames, allowing it to happen without freezing the user UI.

This algo gives other benefits:
- The encoder doesn't need to serialize the whole structure in memory at once
- The encoder can stop if the consumer doesn't need to finish reading the data (supports cancelling)
- The consumer can backpressure the encoding process in case it can't keep up

The major tradeoff of this approach is that the amount of data will be bigger and more costly to compute. So you
exchange the overall performance with the appearance of performance (by not freezing the user screen). Or you might
have a different use case, like just trying to avoid the full memory usage of encoding the data as once, use as you
see fit.

## Status

Evaluating design. This library needs practical usage to be considered stable, as an encode/decode solution its relative
easy to make generative tests that cover a wide range of usages in the realm of Clojure data structures. The library
currently has such tests, and has no problems encoding and decoding any kind of standard Clojure data structures.

## Usage

To get a better understanding of how it works, you can use the `encode` function to see how some data structures get
encoded by this library:

```clojure
;; literals, you get a vector with that

(chunked/encode 3)
; => [3]

(chunked/encode "foo")
; => ["foo"]

;; vectors, where starts to get interesting

(chunked/encode ["foo" :bar])
; => [com.wsscode.chunked-transfer/open-vector "foo" :bar com.wsscode.chunked-transfer/close-vector]

;; maps

(chunked/encode {"foo" :bar})
; =>
; [com.wsscode.chunked-transfer/open-map
;  com.wsscode.chunked-transfer/map-entry
;  "foo"
;  :bar
;  com.wsscode.chunked-transfer/close-map]

;; meta gets encoded, which means you will have the meta restored back

(chunked/encode ^:some-meta {"foo" :bar})
; =>
; [com.wsscode.chunked-transfer/element-meta
;  com.wsscode.chunked-transfer/open-map
;  com.wsscode.chunked-transfer/map-entry
;  :some-meta
;  true
;  com.wsscode.chunked-transfer/close-map
;  com.wsscode.chunked-transfer/open-map
;  com.wsscode.chunked-transfer/map-entry
;  "foo"
;  :bar
;  com.wsscode.chunked-transfer/close-map]

;; mixing things up

(chunked/encode {"foo" :bar
                 ^:stuff [:cool #{"nesting"}] {:complex ["value"]}})
=>
[com.wsscode.chunked-transfer/open-map
 com.wsscode.chunked-transfer/map-entry
 "foo"
 :bar
 com.wsscode.chunked-transfer/map-entry
 com.wsscode.chunked-transfer/element-meta
 com.wsscode.chunked-transfer/open-map
 com.wsscode.chunked-transfer/map-entry
 :stuff
 true
 com.wsscode.chunked-transfer/close-map
 com.wsscode.chunked-transfer/open-vector
 :cool
 com.wsscode.chunked-transfer/open-set
 "nesting"
 com.wsscode.chunked-transfer/close-set
 com.wsscode.chunked-transfer/close-vector
 com.wsscode.chunked-transfer/open-map
 com.wsscode.chunked-transfer/map-entry
 :complex
 com.wsscode.chunked-transfer/open-vector
 "value"
 com.wsscode.chunked-transfer/close-vector
 com.wsscode.chunked-transfer/close-map
 com.wsscode.chunked-transfer/close-map]
```

You may have noticed the result encoding is always a flat vector, the decoding part can take
those steps and rebuild the original data structure.

```clojure
;; round trip sync

(chunked/decode-sync (chunked/encode [1 2 3]))
; => [1 2 3]
```

Using the sync process doesn't give the benefits of chunked encoding, it's good for demos and
understanding, but to really leverage the encoding stream, we need to use the async versions of it:

```clojure
;; async round trip example:

(let [ch (async/chan 3 (partition-all 5))]
  (chunked/encode-async! ch [1 2 3])
  ;; decode-async returns a promise channel that will give the restored data structure
  ;; once it receives all the chunks
  (async/<!! (chunked/decode-async ch)))
```

To handle the inter-process sending, this library offers the helper `chunked-send!` that helps
to build chunks to send over the wire:

```clojure
; takes the structure, the channel size and the chunk size. The channel size says how many chunks
; can get computed before the producer parks, while the chunk-size determines how many ops will be
; included on each chunk
(chunked-send! (range 20) 2 5
  (fn [chunk]
    (println "SEND" chunk)))
```

## Run Tests

Chunked transfer uses [Babashka](https://github.com/babashka/babashka) for task scripts, please install it before proceeding.

### Clojure

```shell script
bb test
```
