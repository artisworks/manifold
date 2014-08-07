(ns manifold.stream.core
  (:require
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.utils :as utils]
    [manifold.stream :as s]
    [manifold.stream.graph :as g]
    [manifold.time :as time])
  (:import
    [java.util.concurrent.atomic
     AtomicReference]))

(s/def-source SeqSource
  [s-ref
   ^AtomicReference last-take]

  (isSynchronous [_]
    true)

  (close [_]
    (let [s @s-ref]
      (if (instance? java.io.Closeable s)
        (.close ^java.io.Closeable s))))

  (description [this]
    (merge
      {:type "seq"
       :drained? (s/drained? this)}
      (let [s @s-ref]
        (when (counted? s)
          {:count (count s)}))))

  (take [this blocking? default-val]
    (if blocking?

      (let [s @s-ref]
        (if (empty? s)
          (do
            (.markDrained this)
            default-val)
          (let [x (first s)]
            (swap! s-ref rest)
            x)))

      (let [d  (d/deferred)
            d' (.getAndSet last-take d)
            f  (fn [_]
                 (let [s @s-ref]
                   (if (or (not (instance? clojure.lang.IPending s))
                         (realized? s))
                     (if (empty? s)
                       (do
                         (.markDrained this)
                         (d/success! d default-val))
                       (let [x (first s)]
                         (when-let [token (d/claim! d)]
                           (swap! s-ref rest)
                           (d/success! d x token))))
                     (utils/wait-for
                       (if (empty? s)
                         (do
                           (.markDrained this)
                           (d/success! d default-val))
                         (let [x (first s)]
                           (when-let [token (d/claim! d)]
                             (swap! s-ref rest)
                             (d/success! d x token))))))))]
        (if (d/realized? d')
          (f nil)
          (d/on-realized d' f f))
        d)))

  (take [this blocking? default-val timeout timeout-val]
    (if (nil? timeout)
      (.take this blocking? default-val)
      (let [d (-> (.take this false default-val)
                (d/timeout! timeout timeout-val))]
        (if blocking?
          @d
          d)))))

(extend-protocol s/Sourceable

  clojure.lang.ISeq
  (to-source [s]
    (->SeqSource
      (atom s)
      (AtomicReference. (d/success-deferred true))))

  clojure.lang.Seqable
  (to-source [s]
    (->SeqSource
      (atom (seq s))
      (AtomicReference. (d/success-deferred true)))))
