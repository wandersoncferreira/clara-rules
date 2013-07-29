(ns clara.test-rules
  (:use clojure.test
        clara.rules
        [clara.rules.engine :only [->Token ast-to-dnf load-rules *trace-transport* description]]
        clojure.pprint
        clara.rules.testfacts)
  (:refer-clojure :exclude [==])
  (:require [clara.sample-ruleset :as sample])
  (import [clara.rules.testfacts Temperature WindSpeed Cold ColdAndWindy LousyWeather
           First Second Third Fourth]
          [clara.rules.engine LocalTransport]))

(deftest test-simple-rule
  (let [rule-output (atom nil)
        cold-rule (new-rule [[Temperature (< temperature 20)]] 
                            (reset! rule-output ?__token__) )

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (new-session)
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    (is (= 
         (->Token [(->Temperature 10 "MCI")] {})
         @rule-output))))


(deftest test-multiple-condition-rule
  (let [rule-output (atom nil)
        cold-windy-rule (new-rule [(Temperature (< temperature 20))
                                   (WindSpeed (> windspeed 25))] 
                                  (reset! rule-output ?__token__))
        session (-> (rete-network) 
                    (add-rule cold-windy-rule)
                    (new-session)
                    (insert (->WindSpeed 30 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    (is (= 
         (->Token [(->Temperature 10 "MCI") (->WindSpeed 30 "MCI")] {})
         @rule-output))))

(deftest test-multiple-simple-rules
  (let [cold-rule-output (atom nil)
        windy-rule-output (atom nil)
        cold-rule (new-rule [(Temperature (< temperature 20))] 
                            (reset! cold-rule-output ?__token__))
        windy-rule (new-rule [(WindSpeed (> windspeed 25))] 
                             (reset! windy-rule-output ?__token__))
        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (add-rule windy-rule)
                    (new-session)
                    (insert (->WindSpeed 30 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    ;; Check rule side effects contin the expected token.
    (is (= 
         (->Token [(->Temperature 10 "MCI")] {})
         @cold-rule-output))
    (is (= 
         (->Token [(->WindSpeed 30 "MCI")] {})
         @windy-rule-output))))


(deftest test-multiple-rules-same-fact

  (let [cold-rule-output (atom nil)
        subzero-rule-output (atom nil)
        cold-rule (new-rule [(Temperature (< temperature 20))] 
                            (reset! cold-rule-output ?__token__))
        subzero-rule (new-rule [(Temperature (< temperature 0))] 
                            (reset! subzero-rule-output ?__token__))

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (add-rule subzero-rule)
                    (new-session)
                    (insert (->Temperature -10 "MCI")))]

    (fire-rules session)

    (is (= 
         (->Token [(->Temperature -10 "MCI")] {})
         @cold-rule-output))

    (is (= 
         (->Token [(->Temperature -10 "MCI")] {})
         @subzero-rule-output))))


(deftest test-simple-binding
  (let [rule-output (atom nil)
        cold-rule (new-rule [(Temperature (< temperature 20) (== ?t temperature))] 
                            (reset! rule-output ?t) )

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (new-session)
                    (insert (->Temperature 10 "MCI")))]


    (fire-rules session)
    (is (= 10 @rule-output))))

(deftest test-simple-join-binding 
  (let [rule-output (atom nil)
        same-wind-and-temp (new-rule [(Temperature (== ?t temperature))
                                      (WindSpeed (== ?t windspeed))] 
                                     (reset! rule-output ?t) )

        session (-> (rete-network) 
                    (add-rule same-wind-and-temp)
                    (new-session)
                    (insert (->Temperature 10  "MCI"))
                    (insert (->WindSpeed 10  "MCI")))]

    (fire-rules session)
    (is (= 10 @rule-output))))

(deftest test-simple-join-binding-nomatch
  (let [rule-output (atom nil)
        same-wind-and-temp (new-rule [(Temperature (== ?t temperature))
                                      (WindSpeed (== ?t windspeed))] 
                                     (reset! rule-output ?t) )

        session (-> (rete-network) 
                    (add-rule same-wind-and-temp)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 20 "MCI")))]

    (fire-rules session)
    (is (= nil @rule-output))))

(deftest test-simple-query
  (let [cold-query (new-query [] [(Temperature (< temperature 20) (== ?t temperature))])

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; The query should identify all items that wer einserted and matchd the
    ;; expected criteria.
    (is (= #{{:?t 15} {:?t 10}}
           (set (query session cold-query {}))))))

(deftest test-param-query
  (let [cold-query (new-query [:?l] [(Temperature (< temperature 50)
                                                  (== ?t temperature)
                                                  (== ?l location))])

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 20 "MCI")) ; Test multiple items in result.
                    (insert (->Temperature 10 "ORD"))
                    (insert (->Temperature 35 "BOS"))
                    (insert (->Temperature 80 "BOS")))]


    ;; Query by location.
    (is (= #{{:?l "BOS" :?t 35}}
           (set (query session cold-query {:?l "BOS"}))))

    (is (= #{{:?l "MCI" :?t 15} {:?l "MCI" :?t 20}}
           (set (query session cold-query {:?l "MCI"}))))

    (is (= #{{:?l "ORD" :?t 10}}
           (set (query session cold-query {:?l "ORD"}))))))


(deftest test-simple-condition-binding
  (let [cold-query (new-query [] [(?t <-- Temperature (< temperature 20))])

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    (is (= #{{:?t #clara.rules.testfacts.Temperature{:temperature 15 :location "MCI"}} 
             {:?t #clara.rules.testfacts.Temperature{:temperature 10 :location "MCI"}}}
           (set (query session cold-query {}))))))

(deftest test-condition-and-value-binding
  (let [cold-query (new-query [] [(?t <-- Temperature (< temperature 20) (== ?v temperature))])

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI")))]

    ;; Ensure the condition's fact and values are all bound.
    (is (= #{{:?v 10, :?t #clara.rules.testfacts.Temperature{:temperature 10 :location "MCI"}} 
             {:?v 15, :?t #clara.rules.testfacts.Temperature{:temperature 15 :location "MCI"}}}
           (set (query session cold-query {}))))))

(deftest test-simple-accumulator
  (let [lowest-temp (accumulate
                     :reduce-fn (fn [value item]
                                  (if (or (= value nil)
                                          (< (:temperature item) (:temperature value) ))
                                    item
                                    value)))
        coldest-query (new-query [] [[?t <-- lowest-temp from [Temperature]]])

        session (-> (rete-network) 
                    (add-query coldest-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; Accumulator returns the lowest value.
    (is (= #{{:?t (->Temperature 10 "MCI")}}
           (set (query session coldest-query {}))))))

(defn min-fact 
  "Function to create a new accumulator for a test."
  [field]
  (accumulate
   :reduce-fn (fn [value item]
                (if (or (= value nil)
                        (< (field item) (field value) ))
                  item
                  value))))

(deftest test-defined-accumulator 
  (let [coldest-query (new-query [] [[?t <-- (min-fact :temperature) from [Temperature]]])

        session (-> (rete-network) 
                    (add-query coldest-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    ;; Accumulator returns the lowest value.
    (is (= #{{:?t (->Temperature 10 "MCI")}}
           (set (query session coldest-query {}))))))


(defn average-value 
  "Test accumulator that returns the average of a field"
  [field]
  (accumulate 
   :initial-value [0 0]
   :reduce-fn (fn [[value count] item]
                [(+ value (field item)) (inc count)])
   :combine-fn (fn [[value1 count1] [value2 count2]]
                 [(+ value1 value2) (+ count1 count2)])
   :convert-return-fn (fn [[value count]] (/ value count))))

(deftest test-accumulator-with-result 

  (let [average-temp-query (new-query [] [[?t <-- (average-value :temperature) from [Temperature]]])

        session (-> (rete-network) 
                    (add-query average-temp-query)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 20 "MCI"))
                    (insert (->Temperature 30 "MCI"))
                    (insert (->Temperature 40 "MCI"))
                    (insert (->Temperature 50 "MCI"))
                    (insert (->Temperature 60 "MCI")))]

 
    ;; Accumulator returns the lowest value.
    (is (= #{{:?t 35}}
           (set (query session average-temp-query {}))))))

(deftest test-accumulate-with-retract
  (let [coldest-query (new-query [] [[?t <-- (accumulate
                                           :reduce-fn (fn [value item]
                                                        (if (or (= value nil)
                                                                (< (:temperature item) (:temperature value) ))
                                                          item
                                                          value)))
                                   :from (Temperature (< temperature 20))]])

        session (-> (rete-network) 
                    (add-query coldest-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI"))
                    (retract (->Temperature 10 "MCI")))]

    ;; The accumulator result should be 
    (is (= #{{:?t (->Temperature 15 "MCI")}}
           (set (query session coldest-query {}))))))



(deftest test-joined-accumulator
  (let [coldest-query (new-query [] [(WindSpeed (== ?loc location))
                                  [?t <-- (accumulate
                                           :reduce-fn (fn [value item]
                                                        (if (or (= value nil)
                                                                (< (:temperature item) (:temperature value) ))
                                                          item
                                                          value)))
                                   :from (Temperature (== ?loc location))]])

        session (-> (rete-network) 
                    (add-query coldest-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 5 "SFO"))

                    ;; Insert last to exercise left activation of accumulate node.
                    (insert (->WindSpeed 30 "MCI")))
        
        session-retracted (retract session (->WindSpeed 30 "MCI"))]


    ;; Only the value that joined to WindSpeed should be visible.
    (is (= #{{:?t (->Temperature 10 "MCI") :?loc "MCI"}}
           (set (query session coldest-query {}))))
    
    (is (empty? (query session-retracted coldest-query {})))))

(deftest test-bound-accumulator-var
  (let [coldest-query (new-query [:?loc] 
                                 [[?t <-- (accumulate
                                           :reduce-fn (fn [value item]
                                                        (if (or (= value nil)
                                                                (< (:temperature item) (:temperature value) ))
                                                          item
                                                          value)))
                                   :from (Temperature (== ?loc location))]])

        session (-> (rete-network) 
                    (add-query coldest-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 5 "SFO")))]

    (is (= #{{:?t (->Temperature 10 "MCI") :?loc "MCI"}}
           (set (query session coldest-query {:?loc "MCI"}))))

    (is (= #{{:?t (->Temperature 5 "SFO") :?loc "SFO"}}
           (set (query session coldest-query {:?loc "SFO"}))))))

(deftest test-simple-negation
  (let [not-cold-query (new-query [] [(not (Temperature (< temperature 20)))])

        session (-> (rete-network) 
                    (add-query not-cold-query)
                    (new-session))
        session-with-temp (insert session (->Temperature 10 "MCI"))
        session-retracted (retract session-with-temp (->Temperature 10 "MCI"))]

    ;; No facts for the above criteria exist, so we should see a positive result
    ;; with no bindings.
    (is (= #{{}}
           (set (query session not-cold-query {}))))
    
    ;; Inserting an item into the sesion should invalidate the negation.
    (is (empty? (query session-with-temp
                       not-cold-query 
                       {})))

    ;; Retracting the inserted item should make the negation valid again.
    (is (= #{{}}
           (set (query session-retracted not-cold-query {}))))))


(deftest test-negation-with-other-conditions
  (let [windy-but-not-cold-query (new-query [] [(WindSpeed (> windspeed 30) (== ?w windspeed)) 
                                             (not (Temperature (< temperature 20)))])

        session (-> (rete-network) 
                    (add-query windy-but-not-cold-query)
                    (new-session))

        ;; Make it windy, so our query should indicate that.
        session (insert session (->WindSpeed 40 "MCI"))
        windy-result  (set (query session windy-but-not-cold-query {}))

        ;; Make it hot and windy, so our query should still succeed.
        session (insert session (->Temperature 80 "MCI"))
        hot-and-windy-result (set (query session windy-but-not-cold-query {}))

        ;; Make it cold, so our query should return nothing.
        session (insert session (->Temperature 10 "MCI"))
        cold-result  (set (query session windy-but-not-cold-query {}))]


    (is (= #{{:?w 40}} windy-result))
    (is (= #{{:?w 40}} hot-and-windy-result))

    (is (empty? cold-result))))


(deftest test-negated-conjunction
  (let [not-cold-and-windy (new-query [] [(not (and (WindSpeed (> windspeed 30))
                                                 (Temperature (< temperature 20))))])

        session (-> (rete-network) 
                    (add-query not-cold-and-windy)
                    (new-session))

        session-with-data (-> session
                              (insert (->WindSpeed 40 "MCI"))
                              (insert (->Temperature 10 "MCI")))]

    ;; It is not cold and windy, so we should have a match.
    (is (= #{{}}
           (set (query session not-cold-and-windy {}))))

    ;; Make it cold and windy, so there should be no match.
    (is (empty? (query session-with-data not-cold-and-windy {})))))

(deftest test-negated-disjunction
  (let [not-cold-or-windy (new-query [] [(not (or (WindSpeed (> windspeed 30))
                                               (Temperature (< temperature 20))))])

        session (-> (rete-network) 
                    (add-query not-cold-or-windy)
                    (new-session))

        session-with-temp (insert session (->WindSpeed 40 "MCI"))
        session-retracted (retract session-with-temp (->WindSpeed 40 "MCI"))]

    ;; It is not cold and windy, so we should have a match.
    (is (= #{{}}
           (set (query session not-cold-or-windy {}))))

    ;; Make it cold and windy, so there should be no match.
    (is (empty? (query session-with-temp not-cold-or-windy {})))

    ;; Retract the added fact and ensure we now match something.
    (is (= #{{}}
           (set (query session-retracted not-cold-or-windy {}))))))


(deftest test-simple-retraction
  (let [cold-query (new-query [] [[Temperature (< temperature 20) (== ?t temperature)]])

        temp (->Temperature 10 "MCI")

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert temp))]

    ;; Ensure the item is there as expected.
    (is (= #{{:?t 10}}
           (set (query session cold-query {}))))

    ;; Ensure the item is retracted as expected.
    (is (= #{}
           (set (query (retract session temp) cold-query {}))))))

(deftest test-noop-retraction
  (let [cold-query (new-query [] [[Temperature (< temperature 20) (== ?t temperature)]])

        session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (retract (->Temperature 15 "MCI")))] ; Ensure retracting a non-existant item has no ill effects.

    (is (= #{{:?t 10}}
           (set (query session cold-query {}))))))

(deftest test-retraction-of-join
  (let [same-wind-and-temp (new-query [] [(Temperature (== ?t temperature))
                                      (WindSpeed (== ?t windspeed))])

        session (-> (rete-network) 
                    (add-query same-wind-and-temp)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 10 "MCI")))]

    ;; Ensure expected join occurred.
    (is (= #{{:?t 10}}
           (set (query session same-wind-and-temp {}))))

    ;; Ensure item was removed as viewed by the query.

    (is (= #{}
           (set (query 
                 (retract session (->Temperature 10 "MCI"))
                 same-wind-and-temp 
                 {}))))))


(deftest test-simple-disjunction
  (let [or-query (new-query [] [(or (Temperature (< temperature 20) (== ?t temperature))
                                 (WindSpeed (> windspeed 30) (== ?w windspeed)))])

        network (-> (rete-network) 
                    (add-query or-query))

        cold-session (insert (new-session network) (->Temperature 15 "MCI"))
        windy-session (insert (new-session network) (->WindSpeed 50 "MCI"))  ]

    (is (= #{{:?t 15}}
           (set (query cold-session or-query {}))))

    (is (= #{{:?w 50}}
           (set (query windy-session or-query {}))))))

(deftest test-disjunction-with-nested-and

  (let [really-cold-or-cold-and-windy 
        (new-query [] [(or (Temperature (< temperature 0) (== ?t temperature))
                        (and (Temperature (< temperature 20) (== ?t temperature))
                             (WindSpeed (> windspeed 30) (== ?w windspeed))))])

        network (-> (rete-network) 
                    (add-query really-cold-or-cold-and-windy))

        cold-session (-> (new-session network)
                         (insert (->Temperature -10 "MCI")))

        windy-session (-> (new-session network)
                          (insert (->Temperature 15 "MCI"))
                          (insert (->WindSpeed 50 "MCI")))]

    (is (= #{{:?t -10}}
           (set (query cold-session really-cold-or-cold-and-windy {}))))

    (is (= #{{:?w 50 :?t 15}}
           (set (query windy-session really-cold-or-cold-and-windy {}))))))

(deftest test-simple-insert 
    (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (new-rule [(Temperature (< temperature 20) (== ?t temperature))] 
                            (insert! (->Cold ?t)) )

        cold-query (new-query [] [(Cold (== ?c temperature))])

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

      (is (= #{{:?c 10}}
             (set (query session cold-query {}))))))

(deftest test-insert-and-retract 
    (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (new-rule [(Temperature (< temperature 20) (== ?t temperature))] 
                            (insert! (->Cold ?t)) )

        cold-query (new-query [] [(Cold (== ?c temperature))])

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

      (is (= #{{:?c 10}}
             (set (query session cold-query {}))))

      ;; Ensure retracting the temperature also removes the logically inserted fact.
      (is (empty? 
           (query 
            (retract session (->Temperature 10 "MCI"))
            cold-query
            {} )))))


(deftest test-insert-and-retract-multi-input 
    (let [rule-output (atom nil)
        ;; Insert a new fact and ensure it exists.
        cold-rule (new-rule [(Temperature (< temperature 20) (== ?t temperature))
                             (WindSpeed (> windspeed 30) (== ?w windspeed))] 
                            (insert! (->ColdAndWindy ?t ?w)) )

        cold-query (new-query [] [(ColdAndWindy (== ?ct temperature) (== ?cw windspeed))])

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (insert (->WindSpeed 40 "MCI"))
                    (fire-rules))]

      (is (= #{{:?ct 10 :?cw 40}}
             (set (query session cold-query {}))))

      ;; Ensure retracting the temperature also removes the logically inserted fact.
      (is (empty? 
           (query 
            (retract session (->Temperature 10 "MCI"))
            cold-query
            {} )))))

(deftest test-ast-to-dnf 

  ;; Test simple condition.
  (is (= {:type :condition :content :placeholder} 
         (ast-to-dnf {:type :condition :content :placeholder} )))

  ;; Test single-item conjunection.
  (is (= {:type :and 
            :content [{:type :condition :content :placeholder}]} 
         (ast-to-dnf {:type :and 
                      :content [{:type :condition :content :placeholder}]})))

  ;; Test multi-item conjunction.
  (is (= {:type :and 
            :content [{:type :condition :content :placeholder1}
                      {:type :condition :content :placeholder2}
                      {:type :condition :content :placeholder3}]} 
         (ast-to-dnf {:type :and 
                      :content [{:type :condition :content :placeholder1}
                                {:type :condition :content :placeholder2}
                                {:type :condition :content :placeholder3}]})))
  
  ;; Test simple disjunction
  (is (= {:type :or
          :content [{:type :condition :content :placeholder1}
                    {:type :condition :content :placeholder2}
                    {:type :condition :content :placeholder3}]}
         (ast-to-dnf {:type :or
                      :content [{:type :condition :content :placeholder1}
                                {:type :condition :content :placeholder2}
                                {:type :condition :content :placeholder3}]})))


  ;; Test simple disjunction with nested conjunction.
  (is (= {:type :or
          :content [{:type :condition :content :placeholder1}
                    {:type :and
                     :content [{:type :condition :content :placeholder2}
                               {:type :condition :content :placeholder3}]}]}
         (ast-to-dnf {:type :or
                      :content [{:type :condition :content :placeholder1}
                                {:type :and
                                 :content [{:type :condition :content :placeholder2}
                                           {:type :condition :content :placeholder3}]}]}))) 

  ;; Test simple distribution of a nested or expression.
  (is (= {:type :or,
          :content
          [{:type :and,
            :content
            [{:content :placeholder1, :type :condition}
             {:content :placeholder3, :type :condition}]}
           {:type :and,
            :content
            [{:content :placeholder2, :type :condition}
             {:content :placeholder3, :type :condition}]}]}

         (ast-to-dnf {:type :and
                      :content 
                      [{:type :or 
                        :content 
                        [{:type :condition :content :placeholder1}
                         {:type :condition :content :placeholder2}]}                                 
                       {:type :condition :content :placeholder3}]})))

  ;; Test push negation to edges.
  (is (= {:type :and, 
           :content 
           [{:type :not, :content [{:content :placeholder1, :type :condition}]} 
            {:type :not, :content [{:content :placeholder2, :type :condition}]} 
            {:type :not, :content [{:content :placeholder3, :type :condition}]}]}
          (ast-to-dnf {:type :not 
                       :content
                       [{:type :or 
                         :content 
                         [{:type :condition :content :placeholder1}
                          {:type :condition :content :placeholder2}
                          {:type :condition :content :placeholder3}]}]})))

  (is (= {:type :and,
          :content [{:type :and,
                     :content
                     [{:type :not, :content [{:content :placeholder1, :type :condition}]}
                      {:type :not, :content [{:content :placeholder2, :type :condition}]}]}]}
         
       (ast-to-dnf {:type :and
                      :content
                      [{:type :not
                        :content
                        [{:type :or
                          :content
                          [{:type :condition :content :placeholder1}
                           {:type :condition :content :placeholder2}]}]}]})))
  
  ;; Test push negation to edges.
  (is (= {:type :or, 
          :content [{:type :not, :content [{:content :placeholder1, :type :condition}]} 
                    {:type :not, :content [{:content :placeholder2, :type :condition}]} 
                    {:type :not, :content [{:content :placeholder3, :type :condition}]}]}
         (ast-to-dnf {:type :not 
                      :content
                      [{:type :and
                        :content 
                        [{:type :condition :content :placeholder1}
                         {:type :condition :content :placeholder2}
                         {:type :condition :content :placeholder3}]}]})))

  ;; Test simple identity disjunction.
  (is (= {:type :or
          :content
          [{:type :not :content [{:type :condition :content :placeholder1}]}
           {:type :not :content [{:type :condition :content :placeholder2}]}]}
         (ast-to-dnf {:type :or
                      :content
                      [{:type :not :content [{:type :condition :content :placeholder1}]}
                       {:type :not :content [{:type :condition :content :placeholder2}]}]})))

  ;; Test distribution over multiple and expressions.
  (is (= {:type :or,
          :content
          [{:type :and,
            :content
            [{:content :placeholder1, :type :condition}
             {:content :placeholder4, :type :condition}
             {:content :placeholder5, :type :condition}]}
           {:type :and,
            :content
            [{:content :placeholder2, :type :condition}
             {:content :placeholder4, :type :condition}
             {:content :placeholder5, :type :condition}]}
           {:type :and,
            :content
            [{:content :placeholder3, :type :condition}
             {:content :placeholder4, :type :condition}
             {:content :placeholder5, :type :condition}]}]}
         (ast-to-dnf {:type :and
                      :content 
                      [{:type :or 
                        :content 
                        [{:type :condition :content :placeholder1}
                         {:type :condition :content :placeholder2}
                         {:type :condition :content :placeholder3}]}                                 
                       {:type :condition :content :placeholder4}
                       {:type :condition :content :placeholder5}]}))))

(def simple-defrule-side-effect (atom nil))

(defrule test-rule 
  (Temperature (< temperature 20))
  =>
  (reset! simple-defrule-side-effect ?__token__))

(deftest test-simple-defrule
  (let [session (-> (rete-network) 
                    (add-rule test-rule)
                    (new-session)
                    (insert (->Temperature 10 "MCI")))]

    (fire-rules session)

    (is (= 
         (->Token [(->Temperature 10 "MCI")] {})
         @simple-defrule-side-effect))))

(defquery cold-query  
  [:?l] 
  (Temperature (< temperature 50)
               (== ?t temperature)
               (== ?l location)))

(deftest test-defquery
  (let [session (-> (rete-network) 
                    (add-query cold-query)
                    (new-session)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 20 "MCI")) ; Test multiple items in result.
                    (insert (->Temperature 10 "ORD"))
                    (insert (->Temperature 35 "BOS"))
                    (insert (->Temperature 80 "BOS")))]


    ;; Query by location.
    (is (= #{{:?l "BOS" :?t 35}}
           (set (query session cold-query {:?l "BOS"}))))

    (is (= #{{:?l "MCI" :?t 15} {:?l "MCI" :?t 20}}
           (set (query session cold-query {:?l "MCI"}))))

    (is (= #{{:?l "ORD" :?t 10}}
           (set (query session cold-query {:?l "ORD"}))))))

(deftest test-rules-from-ns

  (is (= #{{:?loc "MCI"} {:?loc "BOS"}}
       (set (-> (new-session 'clara.sample-ruleset
                             )
                (insert (->Temperature 15 "MCI"))
                (insert (->Temperature 22 "BOS"))
                (insert (->Temperature 50 "SFO"))
                (query sample/freezing-locations {})))))

  (let [session (-> (new-session 'clara.sample-ruleset)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->WindSpeed 45 "MCI"))
                    (fire-rules))]

    (is (= #{{:?fact (->ColdAndWindy 15 45)}}  
           (set 
            (query session sample/find-cold-and-windy {}))))))

(deftest test-transitive-rule

  (is (= #{{:?fact (->LousyWeather)}}  
         (set (-> (new-session 'clara.sample-ruleset)
                  (insert (->Temperature 15 "MCI"))
                  (insert (->WindSpeed 45 "MCI"))
                  (fire-rules)
                  (query sample/find-lousy-weather {}))))))


(deftest test-mark-as-fired
  (let [rule-output (atom nil)
        cold-rule (new-rule [[Temperature (< temperature 20)]] 
                            (reset! rule-output ?__token__) )

        session (-> (rete-network) 
                    (add-rule cold-rule)
                    (new-session)
                    (insert (->Temperature 10 "MCI"))
                    (fire-rules))]

    (is (= 
         (->Token [(->Temperature 10 "MCI")] {})
         @rule-output))
    
    ;; Reset the side effect then re-fire the rules
    ;; to ensure the same one isn't fired twice.
    (reset! rule-output nil)
    (fire-rules session)
    (is (= nil @rule-output))

    ;; Retract and re-add the item to yield a new execution of the rule.
    (-> session 
        (retract (->Temperature 10 "MCI"))
        (insert (->Temperature 10 "MCI"))
        (fire-rules))
    
    (is (= 
         (->Token [(->Temperature 10 "MCI")] {})
         @rule-output))))


(deftest test-chained-inference
  (let [item-query (new-query [] [(?item <-- Fourth)])

        session (-> (rete-network)                    
                    (add-rule (new-rule [(Third)] (insert! (->Fourth)))) ; Rule order shouldn't matter, but test it anyway.
                    (add-rule (new-rule [(First)] (insert! (->Second))))
                    (add-rule (new-rule [(Second)] (insert! (->Third))))
                    (add-query item-query)
                    (new-session)
                    (insert (->First))
                    (fire-rules))]

    ;; The query should identify all items that wer einserted and matchd the
    ;; expected criteria.
    (is (= #{{:?item (->Fourth)}}
           (set (query session item-query {}))))))


(run-tests)