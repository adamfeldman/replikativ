(ns replikativ.gset-test
  (:require [clojure.test :refer :all]
            [replikativ.peer :refer [server-peer]]
            [konserve.memory :refer [new-mem-store]]
            [kabel.http-kit :refer [start stop]]
            [replikativ.environ :refer [*date-fn* store-blob-trans-value]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.crdt.simple-gset.stage :as gs]
            [clojure.inspector :refer [inspect-tree]]
            [full.async :refer [<??]])
  (:import [replikativ.crdt SimpleGSet]))


(deftest gset-stage-test
  (testing "gset creation"
    (let [user-mail "mail:a@mail.com"
          store (<?? (new-mem-store (atom {#uuid "06118e59-303f-51ed-8595-64a2119bf30d"
                                           {:transactions [],
                                            :parents [],
                                            :ts #inst "2016-08-18T16:39:28.178-00:00"
                                            :author user-mail}})))
          peer (<?? (server-peer store "ws://127.0.0.1:9090"
                                 :id "PEER A"
                                 :middleware (comp fetch ensure-hash)))
          _ (start peer)
          stage (<?? (create-stage! "mail:a@mail.com" peer))
          _ (<?? (gs/create-simple-gset! stage :user user-mail :description "some Set" :public false))
          gset-id (-> stage deref (get-in [:config :subs user-mail]) first)]
      (is (= (get-in @stage [user-mail gset-id :state :elements]) #{}))
      (is (= (get-in @stage [user-mail gset-id :downstream :crdt]) :simple-gset))
      (stop peer))))
