
(ns cail.core
  (:import (javax.mail Address BodyPart Message Multipart Part)))

(def ^{:dynamic true} *with-content-stream* false)

(def default-address {:name nil :email ""})

(def default-fields [:id :subject :body :from :recipients :to :reply-to :sent-on :content-type :size :attachments])

(defn address->map [^Address address]
  (if address
    {:name (.getPersonal address)
     :email (.getAddress address)}
    default-address))

(defn multiparts [^Multipart multipart]
  (for [i (range 0 (.getCount multipart))]
    (.getBodyPart multipart i)))

(defn content-type [^Part part]
  (if-let [ct (.getContentType part)]
    (if (.contains ct ";")
      (.toLowerCase (.substring ct 0 (.indexOf ct ";")))
      ct)))

;; Attachments
;; -----------

(defn attachment? [multipart]
  (.equalsIgnoreCase
    Part/ATTACHMENT
    (.getDisposition multipart)))

(defn part->attachment [^BodyPart part]
  {:content-type (content-type part)
   :file-name (.getFileName part)
   :size (.getSize part)
   :content-stream (if *with-content-stream*
                     (.getContent part))})

(defn attachment-parts [^Multipart multipart]
  (->> (multiparts multipart)
       (filter attachment?)))

(defmulti attachments class)

(defmethod attachments String
  [content]
  [{:content-type "text/plain"
    :size (count content)
    :content content}])

(defmethod attachments Multipart
  [multipart]
  (map part->attachment
       (attachment-parts multipart)))

;; Message Body
;; ------------

(defn textual? [multipart]
  (let [ct (content-type multipart)]
    (or (= "text/plain" ct)
        (= "text/html" ct)
        (= "multipart/alternative" ct))))

(defmulti message-body class)

(defmethod message-body String
  [content]
  content)

(defmethod message-body Multipart
  [multipart]
  (if-let [part (->> (multiparts multipart)
                     (filter (complement attachment?))
                     (filter textual?)
                     (last))]
    (message-body (.getContent part))))

;; Fields
;; ------

(defmulti field (fn [id _] id))

(defmethod field :id
  [_ msg]
  (.getMessageNumber msg))

(defmethod field :subject
  [_ msg]
  (.getSubject msg))

(defmethod field :body
  [_ msg]
  (message-body (.getContent msg)))

(defmethod field :from
  [_ msg]
  (address->map (first (.getFrom msg))))

(defmethod field :to
  [_ msg]
  (address->map (first (.getAllRecipients msg))))

(defmethod field :recipients
  [_ msg]
  (map address->map (.getAllRecipients msg)))

(defmethod field :reply-to
  [_ msg]
  (address->map (first (.getReplyTo msg))))

(defmethod field :sent-on
  [_ msg]
  (.getSentDate msg))

(defmethod field :content-type
  [_ msg]
  (content-type msg))

(defmethod field :size
  [_ msg]
  (.getSize msg))

(defmethod field :attachments
  [_ msg]
  (attachments (.getContent msg)))

(defn- ->field-map [msg fields]
  (reduce #(merge %1 {%2 (field %2 msg)}) {} fields))

;; Public
;; ------

(defmacro with-content-stream [& body]
  `(binding [*with-content-stream* true]
     (do ~@body)))

(defn ^{:doc "Parse a Message into a map, optionally specifying which fields to return"}
  message->map
  ([^Message msg] (message->map msg default-fields))
  ([^Message msg fields] (->field-map msg fields)))

(defn ^{:doc "Fetch stream for reading the content of the attachment at index"}
  message->attachment [^Message msg index]
  (if-let [part (nth (attachment-parts (.getContent msg)) index)]
    (part->attachment part)))

