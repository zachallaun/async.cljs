(ns async.core)

(defn each
  ([coll iterator] (each coll iterator #()))
  ([coll iterator callback]
     (let [callback (atom callback)
           completed (atom 0)]
       (doseq [x coll]
         (iterator x (fn [err]
                       (if err
                         (do (@callback err)
                             (reset! callback #()))
                         (do (swap! completed inc)
                             (when (= (count coll) @completed)
                               (callback nil))))))))))
