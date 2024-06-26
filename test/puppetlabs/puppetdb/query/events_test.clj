(ns puppetlabs.puppetdb.query.events-test
  (:require [clojure.set]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!
                                                           enumerated-resource-events-map
                                                           with-corrective-change]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils.db :refer [with-test-db]]
            [puppetlabs.puppetdb.testutils.events
             :refer [expected-resource-events
                     query-resource-events
                     raw-expected-resource-events
                     timestamps->str]]
            [puppetlabs.puppetdb.testutils :refer [dotestseq select-values']]
            [puppetlabs.puppetdb.time :refer [now to-timestamp]])
  (:import
   (clojure.lang ExceptionInfo)))

(def distinct-resource-events (comp set timestamps->str query-resource-events))

(def versions [:v4])

;; Begin tests

(deftest test-compile-resource-event-term
  (let [version :v4
        ops (query/resource-event-ops version)]
    (testing "should succesfully compile a valid equality query"
      (is (= (query/compile-term ops ["=" "report" "blah"])
             {:where   (format "%s = ?" (sutils/sql-hash-as-str "reports.hash"))
              :params  ["blah"]}))
      (is (= (query/compile-term ops ["=" "corrective_change" true])
             {:where   "resource_events.corrective_change = ? AND resource_events.corrective_change IS NOT NULL"
              :params  [true]})))
    (testing "should fail with an invalid equality query"
      (is (thrown-with-msg?
           ExceptionInfo
           (re-pattern (str "'foo' is not a queryable object for version " (last (name version))))
           (query/compile-term ops ["=" "foo" "foo"]))))
    (testing "should successfully compile valid inequality queries"
      (let [start-time  "2011-01-01T12:00:01-03:00"
            end-time    "2011-01-01T12:00:03-03:00"]
        (is (= (query/compile-term ops [">" "timestamp" start-time])
               {:where   "resource_events.timestamp > ?"
                :params  [(to-timestamp start-time)]}))
        (is (= (query/compile-term ops ["<" "timestamp" end-time])
               {:where   "resource_events.timestamp < ?"
                :params  [(to-timestamp end-time)]}))
        (is (= (query/compile-term ops
                                   ["and" [">=" "timestamp" start-time] ["<=" "timestamp" end-time]])
               {:where   "(resource_events.timestamp >= ?) AND (resource_events.timestamp <= ?)"
                :params  [(to-timestamp start-time) (to-timestamp end-time)]}))))
    (testing "should fail with invalid inequality queries"
      (is (thrown-with-msg?
           ExceptionInfo #"> requires exactly two arguments"
           (query/compile-term ops [">" "timestamp"])))
      (is (thrown-with-msg?
           ExceptionInfo #"'foo' is not a valid timestamp value"
           (query/compile-term ops [">" "timestamp" "foo"])))
      (is (thrown-with-msg?
           ExceptionInfo #"> operator does not support object 'resource_type'"
           (query/compile-term ops [">" "resource_type" "foo"]))))))

(deftest resource-event-queries
  (with-test-db
    (let [basic             (store-example-report! (:basic reports) (now))
          report-hash       (:hash basic)
          basic-events      (get-in reports [:basic :resource_events :data])
          basic-events-map  (enumerated-resource-events-map basic-events)
          version :v4]
      (testing (str "resource event retrieval by report - version " version)
        (testing "should return the list of resource events for a given report hash"
          (let [expected  (expected-resource-events basic-events basic)
                actual (distinct-resource-events version ["=" "report" report-hash])]
            (is (= expected actual)))))

      (testing "resource event timestamp queries"
        (testing "should return the list of resource events that occurred before a given time"
          (let [end-time  "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             (kitchensink/select-values basic-events-map [0 2])
                             basic)
                actual (distinct-resource-events version ["<" "timestamp" end-time])]
            (is (= expected actual))))
        (testing "should return the list of resource events that occurred after a given time"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                expected    (expected-resource-events
                             (kitchensink/select-values basic-events-map [1 2])
                             basic)
                actual (distinct-resource-events version [">" "timestamp" start-time])]
            (is (= expected actual))))
        (testing "should return the list of resource events that occurred between a given start and end time"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                end-time    "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             (kitchensink/select-values basic-events-map [2])
                             basic)
                actual (distinct-resource-events version ["and"
                                                          [">" "timestamp" start-time]
                                                          ["<" "timestamp" end-time]])]
            (is (= expected actual))))
        (testing "should return the list of resource events that occurred between a given start and end time (inclusive)"
          (let [start-time  "2011-01-01T12:00:01-03:00"
                end-time    "2011-01-01T12:00:03-03:00"
                expected    (expected-resource-events
                             (kitchensink/select-values basic-events-map [0 1 2])
                             basic)
                actual (distinct-resource-events version ["and"
                                                          [">=" "timestamp" start-time]
                                                          ["<=" "timestamp" end-time]])]
            (is (= expected actual)))))

      (testing "equality queries"
        (doseq [[field value matches]
                [[:resource_type    "Notify"                            [0 1 2]]
                 [:resource_title   "notify, yo"                        [0]]
                 [:status           "success"                           [0]]
                 [:status           "failure"                           [1]]
                 [:property         "message"                           [0 1]]
                 [:property         nil                                 [2]]
                 [:old_value        ["what" "the" "woah"]               [0]]
                 [:new_value        "notify, yo"                        [0]]
                 [:message          "defined 'message' as 'notify, yo'" [0 1]]
                 [:message          nil                                 [2]]
                 [:resource_title   "bunk"                              []]
                 [:certname         "foo.local"                         [0 1 2]]
                 [:certname         "bunk.remote"                       []]
                 [:file             "foo.pp"                            [0]]
                 [:file             "bar"                               [2]]
                 [:file             nil                                 [1]]
                 [:line             2                                   [2]]
                 [:line             nil                                 [1]]
                 [:containing_class "Foo"                               [2]]
                 [:containing_class nil                                 [0 1]]]]
          (testing (format "equality query on field '%s'" field)
            (let [expected  (expected-resource-events
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["=" (name field) value]
                  actual (distinct-resource-events version query)]
              (is (= expected actual)
                  (format "Results didn't match for query '%s'" query))))))

      (testing (str "'not' queries for " version)
        (doseq [[field value matches]
                [[:resource_type    "Notify"                            []]
                 [:resource_title   "notify, yo"                        [1 2]]
                 [:status           "success"                           [1 2]]
                 [:status           "failure"                           [0 2]]
                 [:property         "message"                           []]
                 [:property         nil                                 [0 1]]
                 [:old_value        ["what" "the" "woah"]               [1 2]]
                 [:new_value        "notify, yo"                        [1 2]]
                 [:message          "defined 'message' as 'notify, yo'" []]
                 [:message          nil                                 [0 1]]
                 [:resource_title   "bunk"                              [0 1 2]]
                 [:certname         "foo.local"                         []]
                 [:certname         "bunk.remote"                       [0 1 2]]
                 [:file             "foo.pp"                            [2]]
                 [:file             "bar"                               [0]]
                 [:file             nil                                 [0 2]]
                 [:line             1                                   [2]]
                 [:line             2                                   [0]]
                 [:line             nil                                 [0 2]]
                 [:containing_class "Foo"                               []]
                 [:containing_class nil                                 [2]]]]
          (testing (format "'not' query on field '%s'" field)
            (let [expected  (expected-resource-events
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["not" ["=" (name field) value]]
                  actual (distinct-resource-events version query)]
              (is (= expected actual)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "regex queries"
        (doseq [[field value matches]
                [[:resource_type    "otify"                 [0 1 2]]
                 [:resource_title   "^[Nn]otify,\\s*yo$"    [0]]
                 [:status           "^.ucces."              [0]]
                 [:status           "^.failu."              []]
                 [:property         "^[Mm][\\w\\s]+"        [0 1]]
                 [:message          "notify, yo"            [0 1]]
                 [:resource_title   "^bunk$"                []]
                 [:certname         "^foo\\."               [0 1 2]]
                 [:certname         "^.*\\.mydomain\\.com$" []]
                 [:file             ".*"                    [0 2]]
                 [:file             "\\.pp"                 [0]]
                 [:containing_class "[fF]oo"                [2]]]]
          (testing (format "regex query on field '%s'" field)
            (let [expected  (expected-resource-events
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["~" (name field) value]
                  actual (distinct-resource-events version query)]
              (is (= expected actual)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "negated regex queries"
        (doseq [[field value matches]
                [[:resource_type    "otify"                 []]
                 [:resource_title   "^[Nn]otify,\\s*yo$"    [1 2]]
                 [:status           "^.ucces."              [1 2]]
                 [:property         "^[Mm][\\w\\s]+"        [2]]
                 [:message          "notify, yo"            [2]]
                 [:resource_title   "^bunk$"                [0 1 2]]
                 [:certname         "^foo\\."               []]
                 [:certname         "^.*\\.mydomain\\.com$" [0 1 2]]
                 [:file             ".*"                    [1]]
                 [:file             "\\.pp"                 [1 2]]
                 [:containing_class "[fF]oo"                [0 1]]]]
          (testing (format "negated regex query on field '%s'" field)
            (let [expected  (expected-resource-events
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                  query     ["not" ["~" (name field) value]]
                  actual (distinct-resource-events version query)]
              (is (= expected actual)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "compound queries"
        (testing "'or' equality queries"
          (doseq [[terms matches]
                  [[[[:resource_title "notify, yo"]
                     [:status         "skipped"]]       [0 2]]
                   [[[:resource_type  "bunk"]
                     [:resource_title "notify, yar"]]   [1]]
                   [[[:resource_type  "bunk"]
                     [:status         "bunk"]]          []]
                   [[[:new_value      "notify, yo"]
                     [:resource_title "notify, yar"]
                     [:resource_title "hi"]]            [0 1 2]]
                   [[[:file           "foo.pp"]
                     [:line           2]]               [0 2]]]]
            (let [expected    (expected-resource-events
                               (kitchensink/select-values basic-events-map matches)
                               basic)
                  term-fn     (fn [[field value]] ["=" (name field) value])
                  query       (vec (cons "or" (map term-fn terms)))
                  actual (distinct-resource-events version query)]
              (is (= expected actual)
                  (format "Results didn't match for query '%s'" query))))))

      (testing "'and' equality queries"
        (doseq [[terms matches]
                [[[[:resource_type    "Notify"]
                   [:status           "success"]]     [0]]
                 [[[:resource_type    "bunk"]
                   [:resource_title   "notify, yar"]] []]
                 [[[:resource_title   "notify, yo"]
                   [:status           "skipped"]]     []]
                 [[[:new_value        "notify, yo"]
                   [:resource_type    "Notify"]
                   [:certname         "foo.local"]]   [0]]
                 [[[:certname         "foo.local"]
                   [:resource_type    "Notify"]]      [0 1 2]]
                 [[[:file             "foo.pp"]
                   [:line             1]]             [0]]
                 [[[:containing_class "Foo"]]         [2]]]]
          (let [expected    (expected-resource-events
                             (kitchensink/select-values basic-events-map matches)
                             basic)
                term-fn     (fn [[field value]] ["=" (name field) value])
                query       (vec (cons "and" (map term-fn terms)))
                actual (distinct-resource-events version query)]
            (is (= expected actual)
                (format "Results didn't match for query '%s'" query)))))

      (testing "nested compound queries"
        (doseq [[query matches]
                [[["and"
                   ["or"
                    ["=" "resource_title" "hi"]
                    ["=" "resource_title" "notify, yo"]]
                   ["=" "status" "success"]]               [0]]
                 [["or"
                   ["and"
                    ["=" "resource_title" "hi"]
                    ["=" "status" "success"]]
                   ["and"
                    ["=" "resource_type" "Notify"]
                    ["=" "property" "message"]]]          [0 1]]
                 [["or"
                   ["and"
                    ["=" "file" "foo.pp"]
                    ["=" "line" 1]]
                   ["=" "line" 2]]                         [0 2]]]]
          (let [expected  (expected-resource-events
                           (kitchensink/select-values basic-events-map matches)
                           basic)
                actual (distinct-resource-events version query)]
            (is (= expected actual)
                (format "Results didn't match for query '%s'" query)))))

      (testing "compound queries with both equality and inequality"
        (doseq [[query matches]
                [[["and"
                   ["=" "status" "success"]
                   ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [0]]
                 [["or"
                   ["=" "status" "skipped"]
                   ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [0 2]]]]
          (let [expected  (expected-resource-events
                           (kitchensink/select-values basic-events-map matches)
                           basic)
                actual (distinct-resource-events version query)]
            (is (= expected actual)
                (format "Results didn't match for query '%s'" query))))))))

(deftest resource-event-queries-for-v4+
  (dotestseq [version versions]
    (with-test-db
      (let [basic             (store-example-report! (:basic reports) (now))
            basic2            (store-example-report! (:basic2 reports) (now))
            actual* (comp set timestamps->str (partial query-resource-events version))
            expected* (fn [events-map event-ids report]
                        (expected-resource-events (kitchensink/select-values events-map event-ids) report))
            basic-events-map  (enumerated-resource-events-map (get-in reports [:basic :resource_events :data]))
            basic2-events-map (enumerated-resource-events-map (get-in reports [:basic2 :resource_events :data]))]

        (are [query event-ids] (= (expected* basic-events-map event-ids basic)
                                  (actual* query))

             ["=" "configuration_version" "a81jasj123"] [0 1 2]
             ["=" "run_start_time" "2011-01-01T12:00:00-03:00"] [0 1 2]
             ["=" "run_end_time" "2011-01-01T12:10:00-03:00"] [0 1 2]
             ["=" "timestamp" "2011-01-01T12:00:01-03:00"] [0]
             ["~" "configuration_version" "a81jasj"] [0 1 2]
             ["<" "line" 2] [0]
             ["null?" "line" true] [1]
             ["or"
              ["<" "line" 2]
              ["null?" "line" true]] [0 1]
              ["<=" "line" 2] [0 2])

        (are [query basic-event-ids basic2-event-ids]
          (= (into (expected* basic-events-map basic-event-ids basic)
                   (expected* basic2-events-map basic2-event-ids basic2))
             (actual* query))

          ["=" "containment_path" "Foo"] [2] [2]
          ["~" "containment_path" "Fo"] [2] [2]
          [">" "line" 1] [2] [0 1 2]
          [">=" "line" 1] [0 2] [0 1 2]
          ["null?" "line" false] [0 2] [0 1 2])))))

(deftest latest-report-resource-event-queries
  (with-test-db
    (let [basic1        (store-example-report! (:basic reports) (now))
          events1       (get-in reports [:basic :resource_events :data])
          events1-map   (enumerated-resource-events-map events1)

          basic2        (store-example-report! (:basic2 reports) (now))
          events2       (get-in reports [:basic2 :resource_events :data])
          events2-map   (enumerated-resource-events-map events2)
          version :v4]
      (testing "retrieval of events for latest report only"
        (testing "applied to entire query"
          (let [expected (expected-resource-events events2 basic2)
                actual (distinct-resource-events version ["=" "latest_report?" true])]
            (is (= expected actual))))
        (testing "applied to subquery"
          (let [expected (expected-resource-events
                          (kitchensink/select-values events2-map [1 2]) basic2)
                actual (distinct-resource-events version ["and" ["=" "resource_type" "File"]
                                                          ["=" "latest_report?" true]])]
            (is (= expected actual)))))

      (testing (str "retrieval of events prior to latest report " version)
        (testing "applied to entire query"
          (let [expected  (expected-resource-events events1 basic1)
                actual (distinct-resource-events version ["=" "latest_report?" false])]
            (is (= expected actual))))
        (testing "applied to subquery"
          (let [expected  (expected-resource-events
                           (kitchensink/select-values events1-map [0]) basic1)
                actual (distinct-resource-events version ["and"
                                                          ["=" "status" "success"]
                                                          ["=" "latest_report?" false]])]
            (is (= expected actual)))))

      (testing "compound latest report"
        (let [results1 (expected-resource-events
                        (kitchensink/select-values events1-map [2]) basic1)
              results2 (expected-resource-events
                        (kitchensink/select-values events2-map [1 2]) basic2)
              expected (clojure.set/union results1 results2)
              actual (distinct-resource-events version ["or"
                                                        ["and" ["=" "status" "skipped"]
                                                         ["=" "latest_report?" false]]
                                                        ["and" ["=" "message" "created"]
                                                         ["=" "latest_report?" true]]])]
          (is (= expected actual)))))))

(deftest distinct-resource-event-queries
  (with-test-db
    (let [basic1        (store-example-report! (:basic reports) (now))
          basic3        (store-example-report! (:basic3 reports) (now))
          events1       (get-in reports [:basic :resource_events :data])
          events3       (get-in reports [:basic3 :resource_events :data])
          version :v4]
      (testing "retrieval of events for distinct resources only"
        (let [expected  (expected-resource-events events3 basic3)
              actual (distinct-resource-events version ["=" "certname" "foo.local"]
                                               {:distinct_resources true
                                                :distinct_start_time (to-timestamp 0)
                                                :distinct_end_time   (to-timestamp (now))})]
          (is (= (count events3) (count actual)))
          (is (= expected actual))))

      (testing "events should be contained within distinct resource timestamps"
        (let [expected  (expected-resource-events events1 basic1)
              actual (distinct-resource-events version ["=" "certname" "foo.local"]
                                               {:distinct_resources true
                                                :distinct_start_time (to-timestamp 0)
                                                :distinct_end_time (to-timestamp "2011-01-02T12:00:01-03:00")})]
          (is (= (count events1) (count actual)))
          (is (= expected actual))))

      (testing "filters (such as status) should be applied *after* the distinct list of most recent events has been built up"
        (let [expected  #{}
              actual (distinct-resource-events version ["and" ["=" "certname" "foo.local"]
                                                        ["=" "status" "success"]
                                                        ["=" "resource_title" "notify, yar"]]
                                               {:distinct_resources true
                                                :distinct_start_time (to-timestamp 0)
                                                :distinct_end_time   (to-timestamp (now))})]
          (is (= (count expected) (count actual)))
          (is (= expected actual)))))))

(deftest paging-results
  (with-test-db
    (let [basic4        (store-example-report! (:basic4 reports) (now))
          events        (get-in reports [:basic4 :resource_events :data])
          event-count   (count events)
          select-values #(select-values' (enumerated-resource-events-map events) %)
          version :v4]
      (testing "limit results"
        (doseq [[limit expected] [[1 1] [2 2] [100 event-count]]]
          (let [results (query-resource-events version [">" "timestamp" 0] {:limit limit})
                actual  (count results)]
            (is (= expected actual)))))

      (testing "order_by"
        (testing "rejects invalid fields"
          (is (thrown-with-msg?
               ExceptionInfo #"Unrecognized column 'invalid-field' specified in :order_by"
               (query-resource-events version [">" "timestamp" 0]
                                      {:order_by [[:invalid-field :ascending]]}))))

        (testing "numerical fields"
          (doseq [[order expected-events] [[:ascending  [0 1 2]]
                                           [:descending [2 1 0]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                              (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0]
                                                  {:order_by [[:line order]]})]
                (is (= expected actual))))))

        (testing "alphabetical fields"
          (doseq [[order expected-events] [[:ascending  [0 1 2]]
                                           [:descending [2 1 0]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                              (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0]
                                                  {:order_by [[:file order]]})]
                (is (= expected actual))))))

        (testing "timestamp fields"
          (doseq [[order expected-events] [[:ascending  [0 1 2]]
                                           [:descending [2 1 0]]]]
            (testing order
              (let [expected (raw-expected-resource-events
                              (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:timestamp order]]})]
                (is (= expected actual))))))

        (testing "multiple fields"
          (doseq [[[status-order title-order] expected-events] [[[:descending :ascending] [1 0 2]]
                                                                [[:ascending :descending] [2 0 1]]]]
            (testing (format "status %s resource-title %s" status-order title-order)
              (let [expected (raw-expected-resource-events
                              (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:status status-order]
                                                                                          [:resource_title title-order]]})]
                (is (= expected actual)))))))

      (testing "offset"
        (doseq [[order expected-sequences] [[:ascending  [[0 [0 1 2]]
                                                          [1 [1 2]]
                                                          [2 [2]]
                                                          [3 []]]]
                                            [:descending [[0 [2 1 0]]
                                                          [1 [1 0]]
                                                          [2 [0]]
                                                          [3 []]]]]]
          (testing order
            (doseq [[offset expected-events] expected-sequences]
              (let [expected (raw-expected-resource-events
                              (select-values expected-events) basic4)
                    actual (query-resource-events version [">" "timestamp" 0] {:order_by [[:line order]]
                                                                               :offset offset})]
                (is (= expected actual))))))))))

(deftest query-by-environment
  (with-test-db
    (let [basic (store-example-report! (:basic reports) (now))
          basic2 (store-example-report! (assoc (:basic2 reports)
                                               :environment "PROD")
                                        (now))
          basic-events (get-in reports [:basic :resource_events :data])
          basic-events2 (get-in reports [:basic2 :resource_events :data])]
      (testing "query for DEV reports"
        (let [expected (expected-resource-events basic-events basic)]
          (doseq [query [["=" "environment" "DEV"]
                         ["not" ["=" "environment" "PROD"]]
                         ["~" "environment" "DE.*"]
                         ["not"["~" "environment" "PR.*"]]]
                  :let [actual (distinct-resource-events :v4 query {})]]
            (is (every? #(= "DEV" (:environment %)) actual))
            (is (= expected actual)))))
      (testing "query for PROD reports"
        (let [expected (expected-resource-events basic-events2 basic2)]
          (doseq [query [["=" "environment" "PROD"]
                         ["not" ["=" "environment" "DEV"]]
                         ["~" "environment" "PR.*"]
                         ["not"["~" "environment" "DE.*"]]]
                  :let [actual (distinct-resource-events :v4 query {})]]
            (is (every? #(= "PROD" (:environment %)) actual))
            (is (= expected actual))))))))

(defn- test-events-query
  [report events-map version query expected-rows]
  (let [expected (expected-resource-events
                   (kitchensink/select-values events-map expected-rows)
                   report)
        actual   (distinct-resource-events version query)]
    (is (= expected actual)
        (format "Results didn't match for query '%s'" query))))

(deftest query-by-corrective-change
  (with-corrective-change
    (with-test-db
      (let [basic             (store-example-report! (:basic reports) (now))
            basic-events      (get-in reports [:basic :resource_events :data])
            basic-events-map  (enumerated-resource-events-map basic-events)
            test-query        (partial test-events-query basic basic-events-map :v4)]
        (testing "corrective_change"
          (testing "equality query"
            (test-query ["=" "corrective_change" true] [0]))
          (testing "compound query"
            (test-query ["and" ["=" "status" "success"] ["=" "corrective_change" true]] [0]))
          (testing "'not' query"
            (test-query ["not" ["=" "corrective_change" true]] [1 2]))
          (testing "'null?' query"
            (test-query ["null?" "corrective_change" true] []))
          (testing "'not null?' query"
            (test-query ["null?" "corrective_change" false] [0 1 2])))))))
