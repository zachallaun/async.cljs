(ns async.core
  (:refer-clojure :exclude [map reduce some filter]))

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

(def map (partial async-map each))
(def map-series (partial async-map each-series))

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

(defn- filter-on [pred-fn]
  (fn [each-fn coll iterator callback]
    (let [results (atom [])
          coll (map-indexed #(identity %&) coll)]
      (each-fn coll
               (fn [indexed-val callback]
                 (iterator (second indexed-val)
                           (fn [v]
                             (when (pred-fn v)
                               (swap! results conj indexed-val))
                             (callback))))
               (fn [err]
                 (callback (clojure.core/map second (sort-by first @results))))))))

(def -filter (filter-on true?))
(def filter (partial -filter each))
(def filter-series (partial -filter each-series))
(def select filter)
(def select-series filter-series)

(def -reject (filter-on false?))
(def reject (partial -reject each))
(def reject-series (partial -reject each-series))

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

(def detect (partial -detect each))
(def detect-series (partial -detect each-series))

(defn- some-if [pred]
  (fn [coll iterator main-cb]
    (let [main-cb (atom main-cb)]
      (each coll
            (fn [x callback]
              (iterator x (fn [v]
                            (when (pred v)
                              (@main-cb (pred true))
                              (reset! main-cb (fn [_])))
                            (callback))))
            (fn [err]
              (@main-cb (pred false)))))))

(def some (some-if identity))
(def every (some-if not))
