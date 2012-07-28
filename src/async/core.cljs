(ns async.core
  (:refer-clojure :exclude [map reduce]))

(defn- wrap-err
  [f cb-atom]
  (fn [x success]
    (f x (fn [& err]
           (if err
             (do (@cb-atom err)
                 (reset! cb-atom (fn [_])))
             (success))))))

(defn each
  ([coll iterator]
     (each coll iterator (fn [_])))
  ([coll iterator callback]
     (let [callback  (atom callback)
           completed (atom 0)]
       (doseq [x coll]
         ((wrap-err iterator callback)
          x
          #(do (swap! completed inc)
               (when (= (count coll) @completed)
                 (@callback nil))))))))

(defn each-series
  ([coll iterator]
     (each-series coll iterator (fn [_])))
  ([coll iterator callback]
     (let [callback (atom callback)
           iterate  (fn iterate [coll]
                      ((wrap-err iterator callback)
                       (first coll)
                       #(if-let [ncoll (next coll)]
                          (iterate ncoll)
                          (@callback nil))))]
       (iterate coll))))

;; (defn each-limit ...)

(defn- do-parallel [f]
  #(apply f (conj %& each)))

(defn- do-series [f]
  #(apply f (conj %& each-series)))

(defn- indexed-map->seq
  "Converts a map of the form {index val} into a seq where
  index corresponds to the index of the value in the seq.
  Gaps will be filled with nil.

  ex. (indexed-map->seq {1 :y, 0 :x, 2 :z})
      => (:x :y :z)
  "
  [m]
  (take (inc (clojure.core/reduce max (keys m)))
        (clojure.core/map m (range))))

(defn- async-map [each-fn coll iterator callback]
  (let [results (atom {})
        coll (map-indexed #(identity %&) coll)]
    (each-fn coll
             (fn [[i x] callback]
               (iterator x (fn [err val]
                             (swap! results assoc i val)
                             (callback))))
             (fn [err]
               (callback err (indexed-map->seq @results))))))

(def map (do-parallel async-map))
(def map-series (do-series async-map))

(defn reduce [coll memo iterator callback]
  (let [memo (atom memo)]
    (each-series coll
                 (fn [x callback]
                   (iterator @memo x (fn [err val]
                                       (reset! memo val)
                                       (callback))))
                 (fn [err]
                   (callback err @memo)))))

(def inject reduce)
(def foldl reduce)

(defn reduce-right [coll memo iterator callback]
  (reduce (reverse coll) memo iterator callback))

(def foldr reduce-right)

;; (defn -filter ...)
;; (def filter (do-parallel -filter))
;; (def filter-series (do-series -filter))
;; (def select filter)
;; (def select-series filter-series)

;; (defn -reject ...)
;; (def reject (do-parallel -reject))
;; (def reject-series (do-series -reject))

(defn- -detect [each-fn coll iterator main-cb]
  (let [main-cb (atom main-cb)]
    (each-fn coll
             (fn [x callback]
               (iterator x (fn [result]
                             (if result
                               (do (@main-cb x)
                                   (reset! main-cb (fn [_])))
                               (callback)))))
             (fn [err]
               (@main-cb nil)))))

(def detect (do-parallel -detect))
(def detect-series (do-series -detect))
