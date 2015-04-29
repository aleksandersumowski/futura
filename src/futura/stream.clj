(ns futura.stream
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as asyncp]
            [manifold.stream :as ms]
            [manifold.deferred :as md]
            [futura.atomic :as atomic]
            [futura.promise :as p])
  (:import java.util.concurrent.CompletableFuture
           clojure.lang.Seqable
           java.lang.AutoCloseable
           org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription))

(declare publisher->seq)
(declare subscribe)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default Abstractions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(definterface IPushStream
  (push [v] "Push a value into stream.")
  (complete [] "Mark publisher as complete."))

(definterface IPullStream
  (pull [] "Pull a value from the stream."))

(defprotocol IPublisher
  (publisher* [source xform] "Create a publisher."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn chan->subscription
  "Coerce a core.async channel to an instance
  of subscription interface."
  {:no-doc true}
  ([subscriber source]
   (chan->subscription subscriber source false))
  ([subscriber source close]
   (let [completed (atomic/boolean false)
         requests (async/chan)
         pipeline (fn [n]
                    (async/go-loop [n n]
                      (when (and (pos? n) (not @completed))
                        (if-let [value (async/<! source)]
                          (do
                            (.onNext subscriber value)
                            (recur (dec n)))
                          (do
                            (.onComplete subscriber)
                            (async/close! requests)
                            (when close (async/close! source))
                            (atomic/compare-and-set! completed false true))))))]
     (async/go-loop []
       (when-let [n (async/<! requests)]
         (when-not @completed
           (async/<! (pipeline n))
           (recur))))
     (reify Subscription
       (^void request [_ ^long n]
         (when-not @completed
           (async/put! requests n)))
       (^void cancel [_]
         (atomic/set! completed true)
         (when close (async/close! source))
         (async/close! requests))))))

(defn- promise->subscription
  "Coerce a promise instance to an instance
  of subscription interface."
  [subscriber source]
  (let [canceled (atomic/boolean false)
        completed (atomic/boolean false)]
    (reify Subscription
      (^void request [_ ^long n]
        (when (atomic/compare-and-set! completed false true)
          (p/then source (fn [v]
                           (when-not @canceled
                             (.onNext subscriber v)
                             (.onComplete subscriber))))))
      (^void cancel [_]
        (atomic/set! completed true)
        (atomic/set! canceled true)))))

(defn- proxy-subscriber
  "Create a proxy subscriber.
  The main purpose of this proxy is apply some
  kind of transformations to the proxied publisher
  using transducers."
  [^Publisher p xform subscriber]
  (let [rf (xform (fn
                    ([s] s)
                    ([s v] (.onNext s v))))
        completed (atomic/boolean false)
        subscription (atomic/ref nil)]
    (reify Subscriber
      (onSubscribe [_ s]
        (atomic/set! subscription s)
        (.onSubscribe subscriber s))
      (onNext [_ v]
        (when-not @completed
          (let [res (rf subscriber v)]
            (cond
              (identical? res subscriber)
              (.request @subscription 1)

              (reduced? res)
              (do
                (.cancel @subscription)
                (.onComplete (rf subscriber))
                (atomic/set! completed true))))))
      (onError [_ e]
        (atomic/set! completed true)
        (rf subscriber)
        (.onError subscriber e))
      (onComplete [_]
        (when-not @completed
          (atomic/set! completed true)
          (rf subscriber)
          (.onComplete subscriber))))))

(defn- promise->publisher
  "Create a publisher with promise as source."
  [source]
  (reify
    Seqable
    (seq [p]
      (seq (subscribe p)))

    Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscription (promise->subscription subscriber source)]
        (.onSubscribe subscriber subscription)))))

(defn- channel->publisher
  "Create a publisher with core.async channel as source."
  [source]
  (reify
    Seqable
    (seq [p]
      (seq (subscribe p)))

    Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscription (chan->subscription subscriber source)]
        (.onSubscribe subscriber subscription)))))

(defn- publisher->publisher
  "Create a publisher from an other publisher."
  [source xform]
  (reify
    Seqable
    (seq [p]
      (seq (subscribe p)))

    Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [subscriber (proxy-subscriber source xform subscriber)]
        (.subscribe source subscriber)))))

(defn- iterable->publisher
  "Create a publisher with iterable as source."
  [source]
  (let [source' (async/chan)]
    (async/onto-chan source' (seq source))
    (reify
      Seqable
      (seq [p]
        (seq (subscribe p)))

      Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (chan->subscription subscriber source')]
          (.onSubscribe subscriber subscription))))))

(defn- stream->publisher
  "Create a publisher with manifold stream as source."
  [source]
  (let [source' (async/chan)]
    (ms/connect source source')
    (reify
      Seqable
      (seq [p]
        (seq (subscribe p)))

      Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (chan->subscription subscriber source')]
          (.onSubscribe subscriber subscription))))))

(defn- empty->publisher
  "Creates an empty publisher that implements the
  push stream protocol."
  [bufflen]
  (let [source (async/chan bufflen)]
    (reify
      IPushStream
      (push [_ v]
        (let [p (p/promise)]
          (async/put! source v (fn [res]
                                 (if res
                                   (p/deliver p true)
                                   (p/deliver p false))))
          p))
      (complete [_]
        (async/close! source))

      Seqable
      (seq [p]
        (seq (subscribe p)))

      Publisher
      (^void subscribe [_ ^Subscriber subscriber]
        (let [subscription (chan->subscription subscriber source)]
          (.onSubscribe subscriber subscription))))))

(extend-protocol IPublisher
  nil
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (empty->publisher source xform))

  java.lang.Long
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (empty->publisher source))

  java.lang.Iterable
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (iterable->publisher source))

  manifold.stream.default.Stream
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (stream->publisher source))

  clojure.core.async.impl.channels.ManyToManyChannel
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (channel->publisher source))

  manifold.deferred.IDeferred
  (publisher* [source xform]
    (publisher* (p/promise source) xform))

  CompletableFuture
  (publisher* [source xform]
    (publisher* (p/promise source) xform))

  futura.promise.Promise
  (publisher* [source xform]
    (assert (nil? xform) "not supported operation.")
    (promise->publisher source))

  Publisher
  (publisher* [source xform]
    (publisher->publisher source xform)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn publisher
  "A polymorphic publisher constructor."
  ([source] (publisher* source nil))
  ([xform source] (publisher* source xform)))

(defn publisher->chan
  "Helper that converts a publisher in a core.async channel."
  ([^Publisher p] (publisher->chan p (async/chan)))
  ([^Publisher p chan]
   (let [subscription (atomic/ref nil)
         subscriber (reify Subscriber
                      (onSubscribe [_ s]
                        (.request s 1)
                        (atomic/set! subscription s))
                      (onNext [_ v]
                        (async/put! chan v (fn [result]
                                             (if result
                                               (.request @subscription 1)
                                               (.cancel @subscription)))))
                      (onError [_ e]
                        (async/close! chan))
                      (onComplete [_]
                        (async/close! chan)))]
     (.subscribe p subscriber)
     chan)))

(defn take!
  "Takes a value from a stream, returning a deferred that yields the value
  when it is available or nil if the take fails."
  [^IPullStream p]
  (.pull p))

(defn put!
  "Puts a value into a stream, returning a promise that yields true
  if it succeeds, and false if it fails."
  [^IPushStream p v]
  (.push p v))

(defn- publisher->seq
  "Coerce a publisher in a blocking seq."
  [s]
  (lazy-seq
   (let [v @(take! s)]
     (if v
       (cons v (lazy-seq (publisher->seq s)))
       (.close ^AutoCloseable s)))))

(defn subscribe
  "Create a subscription to the given publisher instance.

  The returned subscription does not consumes the publisher
  data until is requested."
  [^Publisher p]
  (let [sr (async/chan)
        ss (atomic/ref nil)
        sb (reify Subscriber
             (onSubscribe [_ s]
               (atomic/set! ss s))
             (onNext [_ v]
               (async/put! sr v))
             (onError [_ e]
               (async/close! sr))
             (onComplete [_]
               (async/close! sr)))]
    (.subscribe p sb)
    (reify
      AutoCloseable
      (close [_]
        (.cancel @ss))

      Seqable
      (seq [this]
        (publisher->seq this))

      IPullStream
      (pull [_]
        (let [p (p/promise)]
          (async/take! sr #(p/deliver p %))
          (.request @ss 1)
          p))

      asyncp/ReadPort
      (take! [_ handler]
        (asyncp/take! sr handler)))))
