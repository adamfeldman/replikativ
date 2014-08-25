(ns geschichte.p2p.fetch
  "Fetching middleware for geschichte."
  (:require [geschichte.platform-log :refer [debug info warn error]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -assoc-in -get-in -update-in]]
            [clojure.set :as set]
            #+clj [clojure.core.async :as async
                   :refer [<! >! >!! <!! timeout chan alt! go put!
                           filter< map< go-loop pub sub unsub close!]]
            #+cljs [cljs.core.async :as async
                    :refer [<! >! timeout chan put! filter< map< pub sub unsub close!]])
  #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(defn- possible-commits
  [meta]
  (set (keys (:causal-order meta))))


(defn- new-commits! [store metas]
  (go (->> (for [[user repos] metas
                 [repo meta] repos]
             (go [meta (<! (-get-in store [user repo]))]))
           async/merge
           (async/into [])
           <!
           (map #(set/difference (possible-commits (first %))
                                 (possible-commits (second %))))
           (apply set/union)
           (map #(go [(not (<!(-get-in store [%]))) %]))
           async/merge
           (filter< first)
           (map< second)
           (async/into #{})
           <!)))


(defn- new-transactions! [store commit-values]
  (->> (map #(go [(not (<! (-get-in store [%]))) %])
            (flatten (map :transactions (vals commit-values))))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))


(defn- fetch-commits-and-transactions
  "Implements two phase (commits, transactions) fetching."
  [store pull-ch [in out]]
  (go-loop [{:keys [topic metas values peer] :as m} (<! pull-ch)
            meta-pub (when (= topic :meta-pub) m)]
    (when m
      (case topic
        :meta-pub (let [ncs (<! (new-commits! store metas))]
                    (if-not (empty? ncs)
                      (do
                        (info "starting to fetch " ncs "from" peer)
                        (>! out {:topic :fetch
                                 :ids ncs})
                        (recur (<! pull-ch) m))
                      (do
                        (>! in m)
                        (recur (<! pull-ch) nil))))
        :fetched (let [cvs values
                       ntc (<! (new-transactions! store cvs))
                       _ (when-not (empty? ntc)
                           (debug "fetching new transactions" ntc "from" peer)
                           (>! out {:topic :fetch
                                    :ids ntc}))
                       tvs (when-not (empty? ntc)
                             ;; TODO might not be message (but meta-pub)?
                             ;; could happen if updates don't happen serially with ack
                             (:values (<! pull-ch)))]
                   (doseq [[id val] tvs] ;; transactions first
                     (debug "trans assoc-in" id (pr-str val))
                     (<! (-assoc-in store [id] val)))
                   (doseq [[id val] cvs] ;; now commits
                     (debug "commit assoc-in" id (pr-str val))
                     (<! (-assoc-in store [id] val)))
                   (>! in meta-pub)
                   (recur (<! pull-ch) nil))))))


(defn- fetched [store fetch-ch out]
  (go-loop [{:keys [ids peer] :as m} (<! fetch-ch)]
      (when m
        (info "fetch:" ids)
        (let [fetched (->> ids
                           (map (fn [id] (go [id (<! (-get-in store [id]))])))
                           async/merge
                           (async/into {})
                           <!)]
          (>! out {:topic :fetched
                   :values fetched
                   :peer peer})
          (debug "sent fetched:" fetched)
          (recur (<! fetch-ch))))))


(defn- fetch-dispatch [{:keys [topic]}]
  (case topic
    :meta-pub :pull
    :fetched :pull
    :fetch :fetch
    :unrelated))

(defn fetch [store [in out]]
  (let [new-in (chan)
        p (pub in fetch-dispatch)
        pull-ch (chan)
        fetch-ch (chan)]
    (sub p :pull pull-ch)
    (fetch-commits-and-transactions store pull-ch [new-in out])

    (sub p :fetch fetch-ch)
    (fetched store fetch-ch out)

    (sub p :unrelated new-in)
    [new-in out]))