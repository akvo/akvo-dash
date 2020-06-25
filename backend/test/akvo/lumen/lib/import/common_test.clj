(ns akvo.lumen.lib.import.common-test
  (:require [akvo.lumen.lib.import.common :as common]
            [clojure.test :refer :all]))

(deftest take-pos-test
  (testing "extract responses count"
    (let [record {:id ["c1" "c2"], :kind ["number" "text"]}]
      (is (= (common/responses-count record)
             2))))
  (testing "extract question response based on pos"
    (let [record {:id ["c1" "c2"], :kind ["number" "text"]}]
      (is (= (common/extract-question-response record 0)
             {:id "c1", :kind "number"}))
      (is (= (common/extract-question-response record 1)
             {:id "c2", :kind "text"})))))



