(ns fastmath.vector-test
  (:require [fastmath.vector :refer :all]
            [fastmath.core :as m]
            [clojure.test :refer :all]))

;; test protocol

(def cv-in1 [-1.0 4.0])
(def cv-in2 [3.0 2.0])

(deftest clojure-vector-test
  (is (= [-1.0 4.0] (to-vec cv-in1)))
  (is (= [0.0 5.0] (fmap cv-in1 inc)))
  (is (m/approx-eq 17.0 (magsq cv-in1)))
  (is (m/approx-eq (m/sqrt 17.0) (mag cv-in1)))
  (is (m/approx-eq 5.0 (dot cv-in1 cv-in2)))
  (is (= cv-in1 (add cv-in1)))
  (is (= [2.0 6.0] (add cv-in1 cv-in2)))
  (is (= [1.0 -4.0] (sub cv-in1)))
  (is (= [-4.0 2.0] (sub cv-in1 cv-in2)))
  (is (= [-2.0 8.0] (mult cv-in1 2.0)))
  (is (= [-3.0 8.0] (emult cv-in1 cv-in2)))
  (is (= [-0.5 2.0] (div cv-in1 2.0)))
  (is (= [1.0 4.0] (abs cv-in1)))
  (is (== 4.0 (mx cv-in1)))
  (is (== -1.0 (mn cv-in1)))
  (is (= [3.0 4.0] (emx cv-in1 cv-in2)))
  (is (= [-1.0 2.0] (emn cv-in2 cv-in1)))
  (is (== 1 (maxdim cv-in1)))
  (is (== 0 (mindim cv-in1)))
  (is (== 3.0 (sum cv-in1)))
  (is (= [4.0 -1.0] (permute cv-in1 [1 0])))
  (is (= [-1.0 0.25] (reciprocal cv-in1)))
  (is (= [1.0 3.0] (interpolate cv-in1 cv-in2 0.5)))
  (is (= [1.0 3.0] (einterpolate cv-in1 cv-in2 [0.5 0.5])))
  (is (= [0.0 2.0] (econstrain cv-in1 0.0 2.0)))
  (is (not (is-zero? cv-in1)))
  (is (is-zero? [0.0 0.0]))
  (is (not (is-near-zero? cv-in1)))
  (is (is-near-zero? [-0.0000001 0.0])))

(def av-in1 (array-vec cv-in1))
(def av-in2 (array-vec cv-in2))

(deftest array-vec-test
  (is (== 2 (count av-in1)))
  (is (== 4.0 (av-in1 1)))
  (is (= [-1.0 4.0] (to-vec av-in1)))
  (is (= (array-vec [0.0 5.0]) (fmap av-in1 inc)))
  (is (m/approx-eq 17.0 (magsq av-in1)))
  (is (m/approx-eq (m/sqrt 17.0) (mag av-in1)))
  (is (m/approx-eq 5.0 (dot av-in1 av-in2)))
  (is (= av-in1 (add av-in1)))
  (is (= (array-vec [2.0 6.0]) (add av-in1 av-in2)))
  (is (= (array-vec [1.0 -4.0]) (sub av-in1)))
  (is (= (array-vec [-4.0 2.0]) (sub av-in1 av-in2)))
  (is (= (array-vec [-2.0 8.0]) (mult av-in1 2.0)))
  (is (= (array-vec [-3.0 8.0]) (emult av-in1 av-in2)))
  (is (= (array-vec [-0.5 2.0]) (div av-in1 2.0)))
  (is (= (array-vec [1.0 4.0]) (abs av-in1)))
  (is (== 4.0 (mx av-in1)))
  (is (== -1.0 (mn av-in1)))
  (is (= (array-vec [3.0 4.0]) (emx av-in1 av-in2)))
  (is (= (array-vec [-1.0 2.0]) (emn av-in2 av-in1)))
  (is (== 3.0 (sum av-in1)))
  (is (= (array-vec [-1.0 0.25]) (reciprocal av-in1)))
  (is (= (array-vec [1.0 3.0]) (interpolate av-in1 av-in2 0.5)))
  (is (= (array-vec [1.0 3.0]) (einterpolate av-in1 av-in2 [0.5 0.5])))
  (is (= (array-vec [0.0 2.0]) (econstrain av-in1 0.0 2.0)))
  (is (not (is-zero? av-in1)))
  (is (is-zero? (array-vec [0.0 0.0])))
  (is (not (is-near-zero? av-in1)))
  (is (is-near-zero? (array-vec [-0.0000001 0.0]))))

;; vec2

(defn approximately-vec
  "Approximately compare vectors"
  [v in]
  (is-near-zero? (sub v in)))

(def v2-in1 (vec2 -1.0 4.0))
(def v2-in2 (vec2 3.0 2.0))

(deftest vec2-test
  (is (== 2 (count v2-in1)))
  (is (== 4.0 (v2-in1 1)))
  (is (seqable? v2-in1))
  (is (== -1.0 (first v2-in1)))
  (is (== 4.0 (second v2-in1)))
  (is (= [-1.0 4.0] (to-vec v2-in1)))
  (is (= (vec2 0.0 5.0) (fmap v2-in1 inc)))
  (is (m/approx-eq 17.0 (magsq v2-in1)))
  (is (m/approx-eq (m/sqrt 17.0) (mag v2-in1)))
  (is (m/approx-eq 5.0 (dot v2-in1 v2-in2)))
  (is (= v2-in1 (add v2-in1)))
  (is (= (vec2 2.0 6.0) (add v2-in1 v2-in2)))
  (is (= (vec2 1.0 -4.0) (sub v2-in1)))
  (is (= (vec2 -4.0 2.0) (sub v2-in1 v2-in2)))
  (is (= (vec2 -2.0 8.0) (mult v2-in1 2.0)))
  (is (= (vec2 -3.0 8.0) (emult v2-in1 v2-in2)))
  (is (= (vec2 -0.5 2.0) (div v2-in1 2.0)))
  (is (= (vec2 1.0 4.0) (abs v2-in1)))
  (is (== 4.0 (mx v2-in1)))
  (is (== -1.0 (mn v2-in1)))
  (is (= (vec2 3.0 4.0) (emx v2-in1 v2-in2)))
  (is (= (vec2 -1.0 2.0) (emn v2-in2 v2-in1)))
  (is (== 1 (maxdim v2-in1)))
  (is (== 0 (mindim v2-in1)))
  (is (== 3.0 (sum v2-in1)))
  (is (= (vec2 4.0 -1.0) (permute v2-in1 [1 0])))
  (is (= (vec2 -1.0 0.25) (reciprocal v2-in1)))
  (is (= (vec2 1.0 3.0) (interpolate v2-in1 v2-in2 0.5)))
  (is (= (vec2 1.0 3.0) (einterpolate v2-in1 v2-in2 (vec2 0.5 0.5))))
  (is (= (vec2 0.0 2.0) (econstrain v2-in1 0.0 2.0)))
  (is (not (is-zero? v2-in1)))
  (is (is-zero? (vec2 0.0 0.0)))
  (is (not(is-near-zero? v2-in1)))
  (is (is-near-zero? (vec2 -0.0000001 0.0)))
  (is (m/approx-eq 1.815775 (heading v2-in1)))
  (is (m/approx-eq -14.0 (cross v2-in1 v2-in2)))
  (is (approximately-vec (vec2 1.0 -4.0) (rotate v2-in1 m/PI)))
  (is (approximately-vec (normalize (vec2 -4.0 -1.0)) (perpendicular v2-in1)))
  (is (approximately-vec (vec2 2.0 -3.0) (transform v2-in1 (vec2 1 1) (vec2 -1 0) (vec2 0 -1))))
  (is (= (vec2 1.0 0.0) (to-polar (vec2 1.0 0.0))))
  (is (approximately-vec (vec2 1.0 0.0) (from-polar (vec2 1.0 0.0)))))

;; vec3

(def v3-in1 (vec3 v2-in1 0.0))
(def v3-in2 (vec3 v2-in2 0.0))

(deftest vec3-test
  (is (== 3 (count v3-in1)))
  (is (== 4.0 (v3-in1 1)))
  (is (seqable? v3-in1))
  (is (== -1.0 (first v3-in1)))
  (is (== 4.0 (second v3-in1)))
  (is (= [-1.0 4.0 0.0] (to-vec v3-in1)))
  (is (= (vec3 0.0 5.0 1.0) (fmap v3-in1 inc)))
  (is (m/approx-eq 17.0 (magsq v3-in1)))
  (is (m/approx-eq (m/sqrt 17.0) (mag v3-in1)))
  (is (m/approx-eq 5.0 (dot v3-in1 v3-in2)))
  (is (= v3-in1 (add v3-in1)))
  (is (= (vec3 2.0 6.0 0.0) (add v3-in1 v3-in2)))
  (is (= (vec3 1.0 -4.0 0.0) (sub v3-in1)))
  (is (= (vec3 -4.0 2.0 0.0) (sub v3-in1 v3-in2)))
  (is (= (vec3 -2.0 8.0 0.0) (mult v3-in1 2.0)))
  (is (= (vec3 -3.0 8.0 0.0) (emult v3-in1 v3-in2)))
  (is (= (vec3 -0.5 2.0 0.0) (div v3-in1 2.0)))
  (is (= (vec3 1.0 4.0 0.0) (abs v3-in1)))
  (is (== 4.0 (mx v3-in1)))
  (is (== -1.0 (mn v3-in1)))
  (is (= (vec3 3.0 4.0 0.0) (emx v3-in1 v3-in2)))
  (is (= (vec3 -1.0 2.0 0.0) (emn v3-in2 v3-in1)))
  (is (== 1 (maxdim v3-in1)))
  (is (== 0 (mindim v3-in1)))
  (is (== 3.0 (sum v3-in1)))
  (is (= (vec3 4.0 -1.0 0.0) (permute v3-in1 [1 0 2])))
  (is (= (vec3 -1.0 0.25 1.0) (reciprocal (vec3 v2-in1 1.0))))
  (is (= (vec3 1.0 3.0 0.0) (interpolate v3-in1 v3-in2 0.5)))
  (is (= (vec3 1.0 3.0 0.0) (einterpolate v3-in1 v3-in2 (vec3 0.5 0.5 0.5))))
  (is (= (vec3 0.0 2.0 0.0) (econstrain v3-in1 0.0 2.0)))
  (is (not (is-zero? v3-in1)))
  (is (is-zero? (vec3 0.0 0.0 0.0)))
  (is (not (is-near-zero? v3-in1)))
  (is (is-near-zero? (vec3 -0.0000001 0.0 0.0)))
  (is (m/approx-eq 1.815775 (heading v3-in1)))
  (is (approximately-vec (vec3 0.0 0.0 -14.0) (cross v3-in1 v3-in2)))
  (is (approximately-vec (vec3 0.0 0.0 -1.0) (perpendicular v3-in1 v3-in2)))
  (is (approximately-vec (vec3 2.0 -3.0 0.0) (transform v3-in1 (vec3 1 1 0) (vec3 -1 0 0) (vec3 0 -1 0) (vec3 0 0 -1.0)))))

;; rotations
;; from/to-polar


(deftest global-fns-test
  (is (approximately-vec (vec2 -0.3333333333 2.0) (ediv v2-in1 v2-in2)))
  (is (= (vec2 1.0 3.0) (average-vectors [v2-in1 v2-in2])))

  (is (m/approx-eq (m/sqrt 20.0) (dist v2-in1 v2-in2)))
  (is (m/approx-eq 20.0 (dist-sq v2-in1 v2-in2)))
  (is (m/approx-eq 6.0 (dist-abs v2-in1 v2-in2)))
  (is (m/approx-eq 4.0 (dist-cheb v2-in1 v2-in2)))
  (is (m/approx-eq 2.0 (dist-discrete v2-in1 v2-in2)))
  (is (m/approx-eq 4.0 (dist-emd v2-in1 v2-in2)))
  (is (m/approx-eq 1.3333333 (dist-canberra v2-in1 v2-in2)))
  (is (m/approx-eq 0.390812 (dist-ang v2-in1 v2-in2)))

  (is (approximately-vec (vec2 -0.242535 0.9701425) (normalize v2-in1)))
  (is (m/approx-eq 0.70710678 (first (set-mag (vec2 1 1) 1))))
  (is (== 1.0 (mag (limit v2-in1 1.0))))
  (is (m/approx-eq (angle-between v2-in1 v2-in2) (- (relative-angle-between v2-in1 v2-in2))))
  (is (not (aligned? v2-in1 v2-in2)))
  (is (aligned? v2-in1 (add (vec2 0.0000001 -0.0000001) (mult v2-in1 0.555))))
  (is (= v2-in1 (faceforward v2-in1 v2-in2))))
