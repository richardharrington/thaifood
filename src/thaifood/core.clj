(ns thaifood.core
  (:gen-class)
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(defn lower-case [s]
  (.toLowerCase s))

(defn keywordify [capitalized-phrase]
  (-> capitalized-phrase
      (str/replace #" " "-")
      (lower-case)
      (keyword)))


(defn copy-thai-food-csv [from lines]
  (with-open [reader (io/reader from)]
    (let [[[header] rows] (split-at 1 (csv/read-csv reader))]
      {:header header
       :rows (->> rows
                  (filter (fn [row]
                            (->> (nth row 7)
                                 (re-find #"Thai"))))
                  (take lines)
                  (mapv identity))})))

(defn make-get-from-row
  "Caches the conversion of header names into
   index numbers for quick lookup in row vectors"
  [header]
  (let [lookup (zipmap header (range))]
    (fn [row key]
      (let [idx (get lookup key)]
        (if idx
          (nth row idx)
          nil)))))

(defn restaurant-info-consistent-per-camis?
  "Ensures that every row with the same CAMIS
   restaurant id field also has all the same
   restaurant info fields."
  [{:keys [rows header]}]
  (let [get-from-row (make-get-from-row header)]
    (boolean
      (reduce
        (fn [dict row]
          (let [camis (get-from-row row "CAMIS")
                restaurant-info (map (partial get-from-row row)
                                     ["DBA"
                                      "BORO"
                                      "BUILDING"
                                      "STREET"
                                      "ZIPCODE"
                                      "PHONE"
                                      "CUISINE DESCRIPTION"])
                stored-restaurant-info (get dict camis)]
            (cond
              (nil? stored-restaurant-info) (assoc dict camis restaurant-info)
              (not= stored-restaurant-info restaurant-info) (reduced false)
              :else dict)))
        {}
        rows))))

(defn validate-primary-key
  "Ensures that every combination of CAMIS,
   INSPECTION DATE and VIOLATION CODE is unique."
  [{:keys [rows header]}]
  (let [get-from-row (make-get-from-row header)]
    (boolean
      (reduce
        (fn [s row]
          (let [primary-key (map (partial get-from-row row)
                                 ["CAMIS" "INSPECTION DATE" "VIOLATION CODE"])]
            (if (get s primary-key)
              (reduced nil)
              (conj s primary-key))))
        #{}
        rows))))




;; (take 10 (vals (group-by #(nth % 0) (map (partial take 8) x))))

;; (defn copy-csv [from to]
;;   (with-open [reader (io/reader from)
;;               writer (io/writer to)]
;;     (->> (read-csv reader)
;;          (map #(rest (butlast %)))
;;          (write-csv writer))))
