(ns slack-ghost.bot-core
  (:require [clj-slack.rtm :as rtm]
            [clj-slack.channels :as channels]
            [clj-slack.groups :as groups]
            [clj-slack.users :as users]
            [clj-slack.auth :as auth]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]))

(def build-in-events {:channel_created ['update-channel]
                      :team_join ['update-user]
                      :user_change ['update-user]})
(defprotocol IBot
  (url [this])
  (connect [this])
  (receiver [this response])
  (select-func [this type subtype])
  (on-type [this fns message]))

(defrecord Bot [connection ownid users channels groups event]
  IBot
  (url [this]
    (:url (rtm/start (:connection this))))
  (connect [this]
    (let [s (ws/connect (url this)
                        :on-receive #(receiver this %))]
      s))
  (select-func [this type subtype]
    (let [event (:event this)
          selected-with-type (get event type)
          selected-with-subtype (get selected-with-type subtype)
          selected-with-all (get selected-with-type :all)
          fns (remove nil? (concat selected-with-subtype selected-with-all))
          nop (fn [x] x)]
      (if (some? fns)
        #(on-type this fns %)
        nop)))
  (receiver [this response]
    (let [data (json/read-str response :key-fn keyword)
          type (keyword (:type data))
          subtype (keyword (:subtype data))
          on-all (:all (:event this))
          on (select-func this type subtype)]
      (if on-all (doseq [m on-all] (m this data)))
      (on data)))
  (on-type [this fns message]
    (doseq [message-fn fns]
      (try (message-fn this message)
           (catch Exception e (.printStackTrace e))))))

(defrecord User [id name])

(defrecord Channel [id name members])

(defrecord Group [id name members])

(defn- constructor-group [group]
  (let [id (:id group) 
        name (:name group)
        members (:members group)]
    (if id
      (->Group id name members)
      nil)))

(defn- constructor-channel [channel]
  (let [id (:id channel)
        name (:name channel)
        members (:members channel)]
    (if id
      (->Channel id name members)
      nil)))

(defn- constructor-user
  "create with hash"
  [member]
  (->User (:id member) (:name member)))

(defn- create
  ([create-fn data]
   (create create-fn (first data) (rest data)))
  ([create-fn head tail]
   (create create-fn head tail []))
  ([create-fn head tail workpieces]
   (let [workpiece (create-fn head)
        workpieces(if workpiece (conj workpieces workpiece) workpieces)]
     (if-not (empty? tail)
       (create create-fn (first tail) (rest tail) workpieces)
       workpieces))))

(defn- update-channel [this data]
  (assoc this :channels
         (create #'constructor-channel (:channels (channels/list (:connection this))))))

(defn- update-user [this data]
  (assoc this :users
         (create #'constructor-user (:members (users/list (:connection this))))))

(defn- build-in 
  ([events]
   (doseq [build-in-event-key (keys build-in-events)]
     (assoc events build-in-event-key (build-in (build-in-event-key events) (build-in-event-key build-in-events))))
   events)
  ([event build-in-events]
   (if event
     (build-in event (first build-in-events) (rest build-in-events))
     build-in-events))
  ([event build-in-event build-in-events]
   (let [event (conj build-in-event event)
         build-in-event (first build-in-events)
         build-in-events (rest build-in-events)]
     (if build-in-event
       (build-in event build-in-event build-in-events)
       event))))

(defn get-ownid [connection]
  (let [data (auth/test connection)]
    (if (:ok data)
      (:user_id data))))

(defn constructor-bot
  ([token]
   (constructor-bot token {:message [#(println %2)]}))
  ([token event]
   (let [connection {:api-url "https://slack.com/api" :token token}
         users (create #'constructor-user (:members (users/list connection)))
         channels (create #'constructor-channel (:channels (channels/list connection)))
         groups (create #'constructor-group (:groups (groups/list connection)))
         event (build-in event)
         ownid (get-ownid connection)
         bot (->Bot connection ownid users channels groups event)]
     bot)))
