(ns fastmath.calculus
  (:require [fastmath.core :as m]
            [fastmath.random :as r]
            [fastmath.vector :as v]
            [fastmath.stats :as stats]

            [clojure.math.combinatorics :as combo])
  (:import [org.apache.commons.math3.analysis UnivariateFunction]
           [org.apache.commons.math3.analysis.integration UnivariateIntegrator
            IterativeLegendreGaussIntegrator MidPointIntegrator
            RombergIntegrator SimpsonIntegrator TrapezoidIntegrator
            BaseAbstractUnivariateIntegrator]
           [java.util TreeSet Comparator]
           [fastmath.vector Vec2]
           [org.apache.commons.math3.linear Array2DRowRealMatrix LUDecomposition ArrayRealVector
            EigenDecomposition]
           [fastmath.java Array]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

;; https://arxiv.org/pdf/2009.05112.pdf
;; https://github.com/ranjanan/MonteCarloIntegration.jl/blob/master/src/vegas.jl

;; vegas / vegas+

(defn- vegas-build-initial-grid
  [lower upper ^long nbins]
  [(mapv (fn [^double lb ^double ub]
           (vec (m/slice-range lb ub (m/inc nbins)))) lower upper)
   (mapv (fn [^double lb ^double ub]
           (vec (repeat nbins (m// (m/- ub lb) nbins)))) lower upper)])

(defn- vegas-random-sequence
  [^long dims {:keys [random-sequence ^double jitter]
               :or {random-sequence :uniform jitter 0.75}}]
  (if (and (not (= random-sequence :uniform))
           (m/pos? jitter))
    (r/jittered-sequence-generator random-sequence dims jitter)
    (r/sequence-generator random-sequence dims)))

(defn- Itot-sd
  [integrals rev-sigma-squares]
  (let [sum-rev-sigma-squares (v/sum rev-sigma-squares)]
    [(m// (v/sum (v/emult integrals rev-sigma-squares)) sum-rev-sigma-squares)
     (m// (m/sqrt sum-rev-sigma-squares))]))

(defn- normalize-and-compress-d
  [d ^double sumd ^double alpha]
  (mapv (fn [^double v]
          (let [d (m// v sumd)]
            (m/pow (m// (m/- 1.0 d)
                        (m/log (m// d))) alpha))) d))

(defn- smooth-d
  [^doubles d ^long nbins ^double alpha]
  (let [nbins-1 (dec nbins)
        buff (double-array nbins)
        sumd (m/* 8.0 (v/sum d))]
    
    (aset buff 0 (m/+ (m/* 7.0 (aget d 0))
                      (aget d 1)))
    (doseq [^long id (range 1 nbins-1)]
      (aset buff id (m/+ (aget d (dec id))
                         (m/* 6.0 (aget d id))
                         (aget d (inc id)))))
    (aset buff nbins-1 (m/+ (aget d (dec nbins-1))
                            (m/* 7.0 (aget d nbins-1))))
    
    (normalize-and-compress-d buff sumd alpha)))

(defn- calculate-d
  [dims Jsf imat nbins alpha]
  (let [ncalls (count Jsf)
        ni (m// ncalls (double nbins))
        Jsf22 (map (fn [idxs ^double v]
                     [idxs (m// (m/sq v) ni)]) imat Jsf)]
    (for [dim (range dims)]
      (let [buffer (double-array nbins)]
        (run! (fn [[idxs ^double v]]
                (let [id (idxs dim)
                      curr (aget buffer id)]
                  (aset buffer id (m/+ curr v)))) Jsf22)
        (smooth-d buffer nbins alpha)))))

(defn- update-grid-dim [x dx d ^long nbins]
  (let [cx (count x)
        end (m/dec cx)
        buff (double-array cx)
        delta-d (stats/mean d)]
    (aset buff 0 ^double (x 0))
    (aset buff end ^double (x end))
    (loop [i (long 1)
           j (long 0)
           sd 0.0]
      (when (m/< i nbins)
        (let [[^long nj ^double nsd] (loop [nsd sd
                                            nj j]
                                       (if (m/< nsd delta-d)
                                         (recur (m/+ nsd ^double (d nj))
                                                (m/inc nj))
                                         [nj nsd]))
              nsd (m/- nsd delta-d)
              nj- (m/dec nj)]
          (aset buff i (m/- ^double (x nj)
                            (m// (m/* nsd ^double (dx nj-)) ^double (d nj-))))
          (recur (m/inc i) (long nj) nsd))))
    (vec buff)))

(defn- calc-dx [x]
  (mapv (fn [[^double a ^double b]]
          (m/- b a)) (partition 2 1 x)))

(defn- update-grid [xs dxs ds ^long nbins]
  (let [nxs (mapv (fn [x dx d]
                    (update-grid-dim x dx d nbins)) xs dxs ds)
        ndxs (mapv calc-dx nxs)]
    [nxs ndxs]))

;; strata

(defn- stratifications-number
  "Calculate optimal number of stratas per dimension, eq 41"
  ^long [^long nev ^long dims]
  (-> (m// nev 4.0)
      (m/pow (m// dims))
      (m/floor)
      (unchecked-int)
      (m/max 1)))

(defn- ensure-bins-count
  "Make grid size divisible by number of stratas, comment to eq 41"
  ^long [^long nbins ^long nst]
  (if (m/zero? (m/mod nbins nst))
    nbins
    (m/* (m/inc (m// nbins nst)) nst)))

(defn- reshape-hypercube [hc] (apply mapv vector hc))

(defn- build-hypercubes
  [{:keys [^long nstrats ^long ncalls ^long nbins ^long dims]
    :or {nstrats -1 nbins 1000}}]
  (let [nstrats (if-not (m/pos? nstrats) (stratifications-number ncalls dims) nstrats)
        nbins (ensure-bins-count nbins nstrats)
        
        strata-1d (->> (m/slice-range 0.0 1.0 (inc nstrats))
                       (partition 2 1))
        hypercubes (->> (repeat dims strata-1d)
                        (apply combo/cartesian-product)
                        (map reshape-hypercube))
        nhcubes (count hypercubes)
        nhs (repeat nhcubes (-> (m// ncalls (double nhcubes))
                                (m/floor)
                                (unchecked-int)
                                (m/+ 2)))]
    {:nstrats nstrats
     :nbins nbins
     :nhs nhs
     :hcubes hypercubes
     :nhcubes nhcubes
     :volume (m/pow (m// nstrats) dims)}))

(defn random-sequence-samples
  [random-sequence hcubes nhs]
  (loop [[nh & rest-nhs] nhs
         [[left right] & rest-hcubes] hcubes
         rs random-sequence
         res []]
    (if left
      (let [[samples rest-samples] (split-at nh rs)
            nres (reduce (fn [buff sample]
                           (conj buff (v/einterpolate left right sample))) res samples)]
        (recur rest-nhs rest-hcubes rest-samples nres))
      [res rs])))

(defn sigmas-in-hypercubes-and-integral-mc
  [Jsf nhs ^double volume]
  (loop [jsf Jsf
         [^long nh & nhrest] nhs
         sigmas []
         Imc 0.0]
    (if (seq jsf)
      (let [[jsf0 rst] (split-at nh jsf)
            fdv (map (fn [^double jsf] (m/* jsf volume)) jsf0)
            fdv-sum (v/sum fdv)
            fdv2-sum (v/sum (map m/sq fdv))
            I (m// fdv-sum nh)
            nsigmas (conj sigmas (m/+ (m/abs (m/- (m// fdv2-sum nh) (m/* I I)))
                                      m/EPSILON))]
        (recur rst nhrest nsigmas (m/+ Imc I)))
      [sigmas Imc])))

(defn recalculate-nhs
  [hcsigmas ^long ncalls ^double hbeta]
  (let [dhs (->> hcsigmas
                 (map (fn [^double s] (m/pow s hbeta))))
        dhssum (v/sum dhs)]
    (map (fn [^double dh]
           (unchecked-int (m/+ (m/floor (m/* ncalls (m// dh dhssum))) 2))) dhs)))

;;

(defn vegas
  "VEGAS - Monte Carlo integration"
  ([f lower upper] (vegas f lower upper nil))
  ([f lower upper {:keys [^long max-iters ^double rel ^double abs ^long ncalls
                          alpha ^double beta ^long warmup]
                   :or {max-iters 10 rel 5.0e-4 abs 5.0e-4 ncalls 10000 beta 0.75 warmup 0}
                   :as options}]
   (let [dims (count lower)
         max-iters (m/+ (m/max 1 max-iters)
                        (m/max 0 warmup))
         
         random-sequence (vegas-random-sequence dims options)
         
         {:keys [^long nbins ^long nstrats
                 hcubes nhs ^double volume]} (build-hypercubes (assoc options
                                                                      :dims dims
                                                                      :ncalls ncalls))

         ;; lower damping alpha for statified sampling
         alpha (double (or alpha (if (m/<= nstrats 1) 1.5 0.5)))
         hbeta (m// beta 2.0)
         
         ;; convert y-space to x-space
         y->x (fn ^double [x dx ^long dim ^double y]
                (let [nby (m/* nbins y)
                      i (unchecked-long (m/floor nby))
                      dy (m/- nby i)]
                  (m/+ ^double (get-in x [dim i])
                       (m/* ^double (get-in dx [dim i]) dy))))
         
         ;; Jacobian function
         J (fn ^double [dx ^long dim ^double y]
             (m/* nbins ^double (get-in dx [dim (unchecked-long (m/floor (m/* nbins y)))])))]
     
     (loop [iter (long 1)
            total-calls (long 1)
            [x dx] (vegas-build-initial-grid lower upper nbins)
            random-sequence random-sequence
            nhs nhs
            integrals []
            rev-sigma-squares []]
       (let [[samples rst] (random-sequence-samples random-sequence hcubes nhs)

             xss (->> samples
                      (map (fn [ys]
                             (vec (map-indexed (fn [^long dim ^double y]
                                                 (y->x x dx dim y)) ys)))))
             Js (map (fn [ys]
                       (reduce m/fast* (map-indexed (partial J dx) ys))) samples)

             fevals (map f xss)
             Jsf (map m/fast* Js fevals)

             real-calls (count Jsf)
             total-calls (+ total-calls real-calls)
             
             [hcsigmas integral-mc] (sigmas-in-hypercubes-and-integral-mc Jsf nhs volume)
             nhs (recalculate-nhs hcsigmas ncalls hbeta)

             imat (map (fn [ys]
                         (mapv (fn [^double y]
                                 (unchecked-long (m/floor (m/* nbins y)))) ys)) samples)
             
             d (calculate-d dims Jsf imat nbins alpha)
             x-dx (update-grid x dx d nbins)]
         
         (if (m/<= iter warmup)
           (recur (m/inc iter) total-calls
                  x-dx rst nhs integrals rev-sigma-squares)

           (let [variance-mc (m/+ (v/sum (map (fn [^double s ^double nh]
                                                (m// s nh)) hcsigmas nhs)))
                 new-integrals (conj integrals integral-mc)
                 new-rev-sigma-squares (conj rev-sigma-squares (m// variance-mc))
                 
                 [^double Itot ^double sd] (Itot-sd new-integrals new-rev-sigma-squares)]
             
             (if (or (m/== iter max-iters)
                     (and (m/< (m/abs (m// sd Itot)) rel)
                          (m/< (m/abs sd) abs)))
               (let [chisq (-> new-integrals
                               (v/shift (m/- Itot))
                               (v/sq)
                               (v/emult new-rev-sigma-squares)
                               (v/sum))
                     dof (m/dec (count new-integrals))]
                 {:iters (m/- iter warmup)
                  :result Itot
                  :sd sd
                  :nbins nbins
                  :nstrats nstrats
                  :total-calls total-calls
                  :chi2-avg (/ chisq dof)
                  :dof dof
                  :Q (m/regularized-gamma-q (m// dof 2.0) (m// chisq 2.0))})
               
               (recur (m/inc iter) total-calls
                      x-dx rst nhs new-integrals new-rev-sigma-squares)))))))))

;;
;; hcubature
;; https://github.com/JuliaMath/HCubature.jl/blob/master/src/HCubature.jl

(defn- combos
  [^long k ^double lambda ^long n]
  (mapv vec (combo/permutations (concat (repeat k lambda)
                                        (repeat (m/- n k) 0.0)))))

(defn- signcombos
  [^long k ^double lambda ^long n]
  (->> (apply combo/cartesian-product (repeat k [lambda (m/- lambda)]))
       (map (partial concat (repeat (m/- n k) 0.0)))
       (mapcat combo/permutations)
       (distinct)
       (mapv vec)))

(defn- genz-malik-
  [^long n]
  (let [twon (m/<< 1 n)]
    {:p [(combos 1 0.35856858280031806 n)     ;; sqrt(9/70)
         (combos 1 0.9486832980505138 n)      ;; sqrt(9/10)
         (signcombos 2 0.9486832980505138 n)  ;; sqrt(9/10)
         (signcombos n 0.6882472016116853 n)] ;; sqrt(9/19)
     :w [(m/* twon (m// (m/+ 12824.0
                             (m/* -9120.0 n)
                             (m/* 400.0 n n)) 19683.0))
         (m/* twon 0.14936747447035512) ;; 980/6561
         (m/* twon (m// (m/- 1820.0 (m/* 400.0 n)) 19683.0))
         (m/* twon 0.010161052685058172) ;; 200/19683
         0.34847330183407] ;; 6859/19683
     :w' (v/mult [(m// (m/+ 729.0 (m/* -950 n) (m/* 50.0 n n)) 729.0)
                  0.5041152263374485 ;; 245/486
                  (m// (m/- 265.0 (m/* 100.0 n)) 1458.0)
                  0.03429355281207133] ;; 25/729
                 twon)
     :gm-evals (m/inc (m/+ (m/* 4 n)
                           (m/* 2 n (m/dec n))
                           (m/<< 1 n)))}))

(def genz-malik (memoize genz-malik-))

(defrecord CubatureBox [a b ^double I ^double E ^long kdiv])

(defn integrate-gm
  ^CubatureBox [f lower upper dims gp1 gp2 gp3 gp4 w w']
  (let [c (v/mult (v/add lower upper) 0.5)
        delta (vec (v/mult (v/abs (v/sub upper lower)) 0.5))
        V (v/prod delta)
        ^double f1 (f c)
        twelfef1 (m/* 12.0 f1)
        
        f23s (map (fn [^long i]
                    (let [p2 (v/emult delta (gp1 i))
                          f2i (m/+ ^double (f (v/add c p2))
                                   ^double (f (v/sub c p2)))
                          p3 (v/emult delta (gp2 i))
                          f3i (m/+ ^double (f (v/add c p3))
                                   ^double (f (v/sub c p3)))]
                      (Vec2. f2i f3i))) (range dims))
        
        divdiff (mapv (fn [^Vec2 v]
                        (m/abs (m/+ (.y v) twelfef1 (m/* -7.0 (.x v))))) f23s)
        
        ^Vec2 f23 (reduce v/add f23s)
        
        f4 (->> gp3
                (map #(f (v/add c (v/emult delta %))))
                (v/sum))
        f5 (->> gp4
                (map #(f (v/add c (v/emult delta %))))
                (v/sum))

        I (m/* V (m/+ (m/* ^double (w 0) f1)
                      (m/* ^double (w 1) (.x f23))
                      (m/* ^double (w 2) (.y f23))
                      (m/* ^double (w 3) f4)
                      (m/* ^double (w 4) f5)))

        I' (m/* V (m/+ (m/* ^double (w' 0) f1)
                       (m/* ^double (w' 1) (.x f23))
                       (m/* ^double (w' 2) (.y f23))
                       (m/* ^double (w' 3) f4)))
        
        E (m/abs (- I I'))
        deltaf (m// E (m/* (m/pow 10.0 dims) V))
        
        kdivide (->> (range dims)
                     (reduce (fn [[^double mx ^long kdv] ^long dim]
                               (let [^double dd (divdiff dim) 
                                     d (m/- dd mx)
                                     pred (m/> d deltaf)
                                     nkdv (if pred dim kdv)
                                     nmx (if pred dd dim)
                                     nkdv (if (and (m/<= (m/abs d) deltaf)
                                                   (m/> ^double (delta dim)
                                                        ^double (delta nkdv))) dim nkdv)]
                                 [nmx nkdv])) [0.0 0])
                     (second))]
    
    (CubatureBox. lower upper I E kdivide)))

(def ^:private cb-comparator (comparator (fn [^CubatureBox x ^CubatureBox y]
                                           (compare (.E x) (.E y)))))

(defn- enumerate-boxes
  [lower upper ^long div]
  (->> (map (fn [^double a ^double b]
              (->> (m/slice-range a b (m/inc div))
                   (partition 2 1))) lower upper)
       (apply combo/cartesian-product)
       (map (partial apply map vector))))

(defn- build-initial-boxes
  [f lower upper div dims gp1 gp2 gp3 gp4 w w']
  (let [^TreeSet boxes (TreeSet. ^Comparator cb-comparator)]
    (doseq [[a b] (enumerate-boxes lower upper div)]
      (.add boxes (integrate-gm f a b dims gp1 gp2 gp3 gp4 w w')))
    boxes))

(defn- sum-boxes
  [boxes]
  (reduce (fn [[^double I ^double E] ^CubatureBox b]
            [(m/+ I (.I b))
             (m/+ E (.E b))]) [0.0 0.0] boxes))

(defn split-box
  [^CubatureBox box]
  (let [split-id (.kdiv box)
        a (.a box)
        b (.b box)
        ^double ida (a split-id)
        ^double idb (b split-id)
        mid (* 0.5 (m/+ ida idb))]
    [a (assoc b split-id mid)
     (assoc a split-id mid) b]))

(defn cubature
  ([f lower upper] (cubature f lower upper nil))
  ([f lower upper {:keys [^double rel ^double abs ^int max-evals ^int initdiv]
                   :or {rel 1.0e-7 abs 1.0e-7 max-evals Integer/MAX_VALUE initdiv 2}}]
   (let [dims (count lower)
         {:keys [^long gm-evals p w w']} (genz-malik dims)
         [gp1 gp2 gp3 gp4] p
         ^TreeSet boxes (build-initial-boxes f lower upper initdiv dims gp1 gp2 gp3 gp4 w w')
         initial-evals (m/* gm-evals (count boxes))
         [^double I ^double E] (sum-boxes boxes)]
     (if (or (m/<= E (m/max (m/* rel (m/abs I)) abs))
             (m/>= initial-evals max-evals))
       {:result I :error E
        :evaluations initial-evals
        :iterations 1
        :subdivisions (count boxes)}
       (loop [iters (long 1)
              evals initial-evals
              I I
              E E]
         (let [niters (m/inc iters)
               nevals (m/+ evals gm-evals)
               ^CubatureBox max-E-box (.pollLast boxes)
               [a1 b1 a2 b2] (split-box max-E-box)
               ^CubatureBox nbox1 (integrate-gm f a1 b1 dims gp1 gp2 gp3 gp4 w w')
               ^CubatureBox nbox2 (integrate-gm f a2 b2 dims gp1 gp2 gp3 gp4 w w')
               nI (m/+ (.I nbox1) (.I nbox2) (m/- I (.I max-E-box)))
               nE (m/+ (.E nbox1) (.E nbox2) (m/- E (.E max-E-box)))              ]
           (.add boxes nbox1)
           (.add boxes nbox2)
           (if (or (m/<= nE (m/max (m/* rel (m/abs nI)) abs))
                   (m/>= evals max-evals))
             (let [[^double I ^double E] (sum-boxes boxes)]
               {:result I :error E
                :evaluations nevals
                :iterations niters
                :subdivisions (count boxes)})
             (recur niters nevals nI nE))))))))

;; quadgk
;; https://github.com/JuliaMath/QuadGK.jl/blob/master/src/gausskronrod.jl
;; https://github.com/ESSS/cquadpack/blob/master/src/

(defn- eigpoly
  ^double [b ^double z]
  (let [[^double p
         ^double pderiv] (reduce (fn [[^double d1 ^double d1deriv ^double d2 ^double d2deriv] ^double Hev]
                                   (let [b2 (m/sq Hev)
                                         a z
                                         d (m/- (m/* a d1)
                                                (m/* b2 d2))
                                         dderiv (m/+ d1 (m/- (m/* a d1deriv)
                                                             (m/* b2 d2deriv)))]
                                     [d dderiv d1 d1deriv])) [z 1.0 1.0 0.0] b)]
    (m// p pderiv)))

(defn- newton-iterations
  [nb ^double lambda]
  (reduce (fn [^double lambda _]
            (let [dlambda (eigpoly nb lambda)]
              (if (m/valid-double? dlambda)
                (let [nlambda (m/- lambda dlambda)]
                  (if (m/< (m/abs dlambda)
                           (m/ulp nlambda))
                    (let [dlambda (eigpoly nb nlambda)]
                      (reduced (if (m/valid-double? dlambda)
                                 (m/- nlambda dlambda)
                                 nlambda)))
                    nlambda))
                (reduced lambda)))) lambda (range 1000)))

(defn- get-quadrature-points
  [b ^long n]
  (let [cb (count b)
        m (m/inc cb)
        buff (double-array (m/sq m))]
    (doseq [^long i (range cb)
            :let [^double v (b i)]]
      (Array/set2d buff m i (m/inc i) v)
      (Array/set2d buff m (m/inc i) i v))
    (let [lambdas (->> buff
                       (partition m)
                       (m/seq->double-double-array)
                       (Array2DRowRealMatrix.)
                       (EigenDecomposition.)
                       (.getRealEigenvalues)
                       (reverse)
                       (take n))]
      (mapv (partial newton-iterations b) lambdas))))

(defn- eigvec
  ^double [b ^double lambda]
  (let [v1 (m// lambda ^double (b 0))]
    (->> (partition 2 1 b)
         (reduce (fn [[curr ^double v-2 ^double v-1] [^double b-2 ^double b-1]]
                   (let [newv (m// (m/- (m/* lambda v-1)
                                        (m/* b-2 v-2)) b-1)]
                     [(conj curr newv) v-1 newv])) [[1.0 v1] 1.0 v1])
         (first)
         (v/mag)
         (m//))))

;; https://github.com/JuliaMath/QuadGK.jl/blob/master/src/gausskronrod.jl#L448-L497
(defn- kronrodjacobi
  [b ^long n]
  (let [s (vec (repeat (m/+ (m/quot n 2) 2) 0.0))
        t (assoc (vec (repeat (count s) 0.0)) 1 (b n))
        [s t] (reduce (fn [[s t] ^long m]
                        [t (->> (range (m/quot (m/inc m) 2) -1 -1)
                                (reduce (fn [[s ^double u] ^long k]
                                          (let [nu (m/+ u (m/- (m/* ^double (b (m/+ k n))
                                                                    ^double (s k))
                                                               (if (m/> m k)
                                                                 (m/* ^double (b (m/- m k 1))
                                                                      ^double (s (m/inc k)))
                                                                 0.0)))]
                                            [(assoc s (m/inc k) nu) nu]))
                                        [s 0.0])
                                (first))])
                      [s t] (range (m/dec n)))
        s (reduce (fn [s ^long j]
                    (assoc s (m/inc j) (s j))) s (range (m/quot n 2) -1 -1))
        nb (->> (range (m/dec n) (m/+ n n -2))
                (reduce (fn [[b s t] ^long m]
                          (let [ns (->> (range (m/- m n -1) (m/inc (m/quot (m/dec m) 2)))
                                        (reduce (fn [[s ^double u] ^long k]
                                                  (let [j (m/dec (m/- n (m/- m k)))
                                                        nu (m/- u (m/- (m/* ^double (b (m/+ k n))
                                                                            ^double (s (m/inc j)))
                                                                       (m/* ^double (b (m/- m k 1))
                                                                            ^double (s (m/+ j 2)))))]
                                                    [(assoc s (m/inc j) nu) nu]))
                                                [s 0.0])
                                        (first))
                                k (m/quot (m/inc m) 2)
                                j (m/- n (m/+ (m/- m k) 2))
                                nb (if (m/not== (m/* 2 k) m)
                                     (assoc b (m/+ k n) (m// ^double (ns (m/+ j 1))
                                                             ^double (ns (m/+ j 2))))
                                     b)]
                            [nb t ns]))
                        [b s t])
                (first)
                (v/sqrt))
        x (get-quadrature-points nb (m/inc n))
        w (mapv (fn [^double x] (m/* 2.0 (m/sq (eigvec nb x)))) x)
        nb (map-indexed (fn [^long j ^double b]
                          (let [j (m/inc j)]
                            (if (m/< j n)
                              (m// j (m/sqrt (m/dec (m/* 4.0 j j))))
                              b))) nb)
        gw (mapv (fn [^double x]
                   (m/* 2.0 (m/sq (eigvec (vec (take (m/dec n) nb)) x))))(->> x rest (take-nth 2)))]
    {:x x :w w :gw gw :gk-evals (m/+ (m/* 4 n) 2)}))

(defn- kronrod-
  [^long n]
  (let [last-j (m/quot (m/inc (m/* 3 n)) 2)
        b (mapv (fn [^double j]
                  (if (m/<= j last-j)
                    (let [j2 (m/sq j)]
                      (m// j2 (m/dec (m/* 4.0 j2)) ))
                    0.0)) (range 1 (inc (m/* 2 n))))]
    (kronrodjacobi b n)))

(def kronrod (memoize kronrod-))

(defrecord QuadGKSegment [^Vec2 ab ^double I ^double E])

(defn- integrate-gk
  ^QuadGKSegment [f ^Vec2 ab x w gw]
  (let [s (m/* 0.5 (m/- (.y ab) (.x ab)))
        lastxw (m/dec (count w))
        lastxw- (m/dec lastxw)
        lastgw (m/dec (count gw))
        n1 (m/- 1 (m/bit-and (m/inc lastxw) 1))
        a (.x ab)
        ^double f0 (f (m/+ a s))
        Igk (if (m/zero? n1)
              (Vec2. 0.0 (m/* f0 ^double (w lastxw)))
              (let [^double x-1 (x lastxw-)]
                (Vec2. (m/* f0 ^double (gw lastgw))
                       (m/+ (m/* f0 ^double (w lastxw))
                            (m/* (m/+ ^double (f (m/+ a (m/* (m/+ 1.0 x-1) s)))
                                      ^double (f (m/+ a (m/* (m/- 1.0 x-1) s))))
                                 ^double (w lastxw-))))))
        ^Vec2 Igk (-> (->> (if (m/zero? n1) gw (butlast gw))
                           (map-indexed (fn [^long i ^double gwi]
                                          (let [i (m/inc i)
                                                ii (m/dec (m/* 2 i))
                                                ii-  (m/dec ii)
                                                ^double x2i (x ii)
                                                ^double x2i-1 (x ii-)
                                                ^double w2i (w ii)
                                                ^double w2i-1 (w ii-)
                                                fg (m/+ ^double (f (m/+ a (m/* (m/+ 1.0 x2i) s)))
                                                        ^double (f (m/+ a (m/* (m/- 1.0 x2i) s))))
                                                fk (m/+ ^double (f (m/+ a (m/* (m/+ 1.0 x2i-1) s)))
                                                        ^double (f (m/+ a (m/* (m/- 1.0 x2i-1) s))))]
                                            (Vec2. (m/* fg gwi)
                                                   (m/+ (m/* fg w2i)
                                                        (m/* fk w2i-1))))))
                           (reduce v/add Igk))
                      (v/mult s))
        E (m/abs (m/- (.y Igk) (.x Igk)))]
    (QuadGKSegment. ab (.y Igk) E)))

(def ^:private gk-comparator (comparator (fn [^QuadGKSegment x ^QuadGKSegment y]
                                         (compare (.E x) (.E y)))))

(defn- build-initial-segments
  [f lower upper initdiv x w gw]
  (let [^TreeSet boxes (TreeSet. ^Comparator gk-comparator)
        segments (->> (m/slice-range lower upper (m/inc ^long initdiv))
                      (partition 2 1))]
    (doseq [[a b] segments]
      (.add boxes (integrate-gk f (Vec2. a b) x w gw)))
    boxes))

(defn- sum-segments
  [segments]
  (reduce (fn [[^double I ^double E] ^QuadGKSegment s]
            [(m/+ I (.I s))
             (m/+ E (.E s))]) [0.0 0.0] segments))

(defn- split-segment
  [^QuadGKSegment s]
  (let [^Vec2 ab (.ab s)
        mid (m/* 0.5 (v/sum ab))]
    [(Vec2. (.x ab) mid)
     (Vec2. mid (.y ab))]))

(defn integrate-gk-final
  ([f lower upper] (integrate-gk-final f lower upper nil))
  ([f lower upper {:keys [^double rel ^double abs ^int max-evals ^int max-iters
                          ^int integration-points ^int initdiv]
                   :or {rel 1.0e-8 abs 1.0e-8 max-evals Integer/MAX_VALUE max-iters 64
                        integration-points 7 initdiv 1}}]
   (let [{:keys [^long gk-evals x w gw]} (kronrod integration-points)
         ^TreeSet segments (build-initial-segments f lower upper initdiv x w gw)
         initial-evals (m/* gk-evals (count segments))
         [^double I ^double E] (sum-segments segments)]
     (if (or (m/<= E (m/max (m/* rel (m/abs I)) abs))
             (m/>= initial-evals max-evals))
       {:result I :error E
        :evaluations initial-evals
        :iterations 1
        :subdivisions (count segments)}
       (loop [iters (long 1)
              evals initial-evals
              I I
              E E]
         (let [niters (m/inc iters)
               nevals (m/+ evals gk-evals)
               ^QuadGKSegment max-E-segment (.pollLast segments)
               [ab1 ab2] (split-segment max-E-segment)
               ^QuadGKSegment nsegment1 (integrate-gk f ab1 x w gw)
               ^QuadGKSegment nsegment2 (integrate-gk f ab2 x w gw)
               nI (m/+ (.I nsegment1) (.I nsegment2) (m/- I (.I max-E-segment)))
               nE (m/+ (.E nsegment1) (.E nsegment2) (m/- E (.E max-E-segment)))]
           (.add segments nsegment1)
           (.add segments nsegment2)
           (if (or (m/<= nE (m/max (m/* rel (m/abs nI)) abs))
                   (m/>= evals max-evals)
                   (m/>= niters max-iters))
             (let [[^double I ^double E] (sum-segments segments)]
               {:result I :error E
                :evaluations nevals
                :iterations niters
                :subdivisions (count segments)})
             (recur niters nevals nI nE))))))))

;;

(defn- subst-upper
  [f ^double lower]
  (fn [^double t]
    (let [inv (m// (m/- 1.0 t))]
      (m/* ^double (f (m/+ lower (m/* t inv))) (m/sq inv)))))

(defn- subst-lower
  [f ^double upper]
  (fn [^double t]
    (let [inv (m// (m/inc t))]
      (m/* ^double (f (m/+ upper (m/* t inv))) (m/sq inv)))))

(defn- subst-both
  [f]
  (fn [^double t]
    (let [t2 (m/* t t)
          den (m// (m/- 1.0 t2))]
      (m/* ^double (f (m/* t den))
           (m/* (m/inc t2)
                (m/sq den))))))

(defn- make-integrant
  [f]
  (reify UnivariateFunction
    (value [_ x] (f x))))

(defn integrate
  ([f] (integrate f 0.0 1.0))
  ([f ^double lower ^double upper] (integrate f lower upper nil))
  ([f ^double lower ^double upper
    {:keys [^double rel ^double abs max-iters ^int min-iters ^int max-evals
            integrator ^long integration-points]
     :or {rel BaseAbstractUnivariateIntegrator/DEFAULT_RELATIVE_ACCURACY
          abs BaseAbstractUnivariateIntegrator/DEFAULT_ABSOLUTE_ACCURACY
          min-iters BaseAbstractUnivariateIntegrator/DEFAULT_MIN_ITERATIONS_COUNT
          max-evals Integer/MAX_VALUE
          integration-points 7
          integrator :romberg}
     :as options}]
   (let [max-iters (unchecked-int (or max-iters
                                      (case integrator
                                        :romberg RombergIntegrator/ROMBERG_MAX_ITERATIONS_COUNT
                                        :trapezoid TrapezoidIntegrator/TRAPEZOID_MAX_ITERATIONS_COUNT
                                        :midpoint MidPointIntegrator/MIDPOINT_MAX_ITERATIONS_COUNT
                                        :simpson SimpsonIntegrator/SIMPSON_MAX_ITERATIONS_COUNT
                                        :legendregauss 64                                        
                                        BaseAbstractUnivariateIntegrator/DEFAULT_MAX_ITERATIONS_COUNT)))
         ^UnivariateIntegrator integrator-obj (if (keyword? integrator)
                                                (case integrator
                                                  :romberg (RombergIntegrator. rel abs min-iters max-iters)
                                                  :trapezoid (TrapezoidIntegrator. rel abs min-iters max-iters)
                                                  :midpoint (MidPointIntegrator. rel abs min-iters max-iters)
                                                  :simpson (SimpsonIntegrator. rel abs min-iters max-iters)
                                                  :legendregauss (IterativeLegendreGaussIntegrator. integration-points
                                                                                                    rel abs min-iters max-iters)
                                                  integrator)
                                                integrator)
         [f ^double lower ^double upper] (cond
                                           (and (m/neg-inf? lower)
                                                (m/pos-inf? upper)) [(subst-both f)
                                                                     (m/next-double -1.0)
                                                                     (m/prev-double 1.0)]
                                           (m/neg-inf? lower) [(subst-lower f upper)
                                                               (m/next-double -1.0) 0.0]
                                           (m/pos-inf? upper) [(subst-upper f lower)
                                                               0.0 (m/prev-double 1.0)]
                                           :else [f lower upper])]
     (cond (instance? UnivariateIntegrator integrator-obj)
           (let [integrant (make-integrant f)
                 result (.integrate integrator-obj max-evals integrant lower upper)]
             {:result result
              :iterations (.getIterations integrator-obj)
              :evaluations (.getEvaluations integrator-obj)
              :integrator integrator})

           (= integrator-obj :gausskronrod)
           (integrate-gk-final f lower upper options)
           
           :else (throw (Exception. (str "Unknown integrator: " integrator)))))))


;;; finite difference

(defn- fd-coeffs
  [n offsets]
  (let [noffsets (count offsets)
        matrix (->> (range 1 noffsets)
                    (reduce (fn [buff ^long i]
                              (conj buff (map (fn [^long j]
                                                (m/fpow j i)) offsets)))
                            [(repeat noffsets 1)])
                    (m/seq->double-double-array)
                    (Array2DRowRealMatrix.))
        rhs (->> (range noffsets)
                 (map (fn [^long id]
                        (if (m/== id n) (m/factorial n) 0)))
                 (m/seq->double-array)
                 (ArrayRealVector.))]
    [offsets (-> (LUDecomposition. matrix)
                 (.getSolver)
                 (.solve rhs)
                 (.getDataRef)
                 (m/double-array->seq))]))

(defn- fd-coeffs-for-accuracy
  ([^long n] (fd-coeffs-for-accuracy n 2))
  ([^long n ^long acc] (fd-coeffs-for-accuracy n acc :central))
  ([^long n ^long acc kind]
   (let [num-central (-> (m/inc n)
                         (m// 2.0)
                         (m/floor)
                         (m/* 2.0)
                         (m/dec)
                         (m/+ acc)
                         (unchecked-long))
         num-side (m/quot num-central 2)
         num-coeffs (if (m/even? n) (m/inc num-central) num-central)]
     (fd-coeffs n (case kind
                    :forward (range num-coeffs)
                    :backward (range (m/inc (m/- num-coeffs)) 1)
                    (range (m/- num-side) (m/inc num-side)))))))

(defmacro ^:private produce-symbols
  [n acc kind]
  (let [[offsets coeffs] (fd-coeffs-for-accuracy n acc kind)
        x (with-meta (symbol "x") {:tag 'double})]
    `(fn [~x]
       (m// (m/+ ~@(map (fn [id coeff]
                          `(m/* ~coeff (double (~'f (m/+ ~x (m/* ~id ~'h)))))) offsets coeffs)) ~'hn))))

(defmacro ^:private produce-case-for-methods
  [n acc]
  `(case ~'method
     :forward (produce-symbols ~n ~acc :forward)
     :backward (produce-symbols ~n ~acc :backward)
     (produce-symbols ~n ~acc :central)))

(defmacro ^:private produce-cases-for-accuracy
  [n]
  `(case ~'acc
     1 (produce-case-for-methods ~n 1)
     2 (produce-case-for-methods ~n 2)
     3 (produce-case-for-methods ~n 3)
     4 (produce-case-for-methods ~n 4)
     5 (produce-case-for-methods ~n 5)
     6 (produce-case-for-methods ~n 6)
     (let [[offsets# coeffs#] (fd-coeffs-for-accuracy ~n ~'acc ~'method)]
       (fn [x#] (m// (v/sum (map (fn [id# coeff#]
                                  (m/* coeff# (double (~'f (m/+ x# (m/* (long id#) ~'h)))))) offsets# coeffs#)) ~'hn)))))

(defn derivative
  ([f ^long n] (derivative f n nil))
  ([f ^long n {:keys [^long acc ^double h method]
               :or {acc 2 h 0.0 method :central}}]
   (if (m/zero? n)
     f
     (let [h (if (m/zero? h) (m/pow m/MACHINE-EPSILON (m// (m/+ n 2.0))) h)
           hn (m/fpow h n)]
       (case n
         1 (produce-cases-for-accuracy 1)
         2 (produce-cases-for-accuracy 2)
         3 (produce-cases-for-accuracy 3)
         4 (produce-cases-for-accuracy 4)
         5 (produce-cases-for-accuracy 5)
         6 (produce-cases-for-accuracy 6)
         (let [[offsets coeffs] (fd-coeffs-for-accuracy n acc method)]
           (fn [^double x] (m// (v/sum (map (fn [^long id ^double coeff]
                                             (m/* coeff ^double (f (m/+ x (m/* id h))))) offsets coeffs))
                               hn))))))))

(defn f' [f] (derivative f 1))
(defn f'' [f] (derivative f 2))
(defn f''' [f] (derivative f 3))

(defn gradient
  ([f] (gradient f nil))
  ([f {:keys [^double h ^long acc]
       :or {h 1.0e-6 acc 2}}]
   (case acc
     2 (let [h2 (m// (m/* 2.0 h))]
         (fn [v]
           (let [v (vec v)]
             (map (fn [^long id]
                    (let [^double vid (v id)
                          ^double x1 (f (assoc v id (m/+ vid h)))
                          ^double x2 (f (assoc v id (m/- vid h)))]
                      (m/* (m/- x1 x2) h2))) (range (count v))))))
     4 (fn [v]
         (let [v (vec v)]
           (map (fn [^long id]
                  (let [^double vid (v id)
                        ^double x1 (f (assoc v id (m/- vid h h)))
                        ^double x2 (f (assoc v id (m/- vid h)))
                        ^double x3 (f (assoc v id (m/+ vid h)))
                        ^double x4 (f (assoc v id (m/+ vid h h)))]
                    (m// (m/+ (m/* 0.08333333333333333 x1)
                              (m/* -0.6666666666666666 x2)
                              (m/* 0.6666666666666666 x3)
                              (m/* -0.08333333333333333 x4)) h))) (range (count v))))))))

(defn hessian
  ([f] (hessian f nil))
  ([f {:keys [^double h ]
       :or {h 5.0e-3}}]
   (let [h4 (m// (m/* 4.0 h h))
         h2 (m// (m/* h h))]
     (fn [v]
       (let [v (vec v)
             cv (count v)
             r (range cv)
             buff (double-array (m/* cv cv))
             fv-2 (m/* -2.0 ^double (f v))]
         (doseq [^long i r
                 ^long j r
                 :when (m/<= i j)]
           (if (m/== i j)
             (let [^double vi (v i)
                   ^double x1 (f (assoc v i (m/+ vi h)))
                   ^double x2 (f (assoc v i (m/- vi h)))]
               (Array/set2d buff cv i i (m/* (m/+ x1 fv-2 x2) h2)))
             (let [^double vi (v i)
                   ^double vj (v j)
                   ^double x1 (f (assoc v i (m/+ vi h) j (m/+ vj h)))
                   ^double x2 (f (assoc v i (m/+ vi h) j (m/- vj h)))
                   ^double x3 (f (assoc v i (m/- vi h) j (m/+ vj h)))
                   ^double x4 (f (assoc v i (m/- vi h) j (m/- vj h)))
                   val (m/* (m/- (m/+ x1 x4) x2 x3) h4)]
               (Array/set2d buff cv i j val)
               (Array/set2d buff cv j i val))))
         (partition cv buff))))))




















;;;

(defn xyz2
  [[^double x ^double y ^double z]]
  (m/+ (m/sq x) (m/sq y) (m/* x y) z (m/* x y z)))


(defn x3-2xy-y6
  [[^double x ^double y]]
  (m/+ (m/cb x)
       (m/* -2.0 x y)
       (m/* -1.0 (m/sq (m/cb y)))))

((hessian x3-2xy-y6) [1 2])

((hessian sinxcosy {:h 1.0e-4}) [m/PI m/HALF_PI])

((hessian xyz2 {:h 1.0e-4}) [1 1 1])
;; => ((1.999999987845058 1.999999987845058 0.999999993922529) (1.999999987845058 1.999999987845058 0.999999993922529) (0.999999993922529 0.999999993922529 0.0))

(def long-xs (repeatedly 1000 rand))

(require '[criterium.core :as crit])

(crit/quick-bench ((gradient (comp v/sum v/sin)) (repeatedly 1000 rand)))

(defn xyz
  [[^double x ^double y ^double z]]
  (m/+ (m/sq x) (m/sq y) (m/* 2.0 x y z)))


((gradient xyz {:acc 2 :h 1.0e-0}) [1 1 1])

;; => (0.5403023057848344 -0.41614683654600526 -0.9899924966472184)

((gradient (comp v/sum v/sin)) (repeatedly 1000 rand))

((comp v/sum v/cos) [1 2 3])
;; => -0.865837027279448

(let [v [1 2 3]]
  (identical? v (vec v)))

(identical? [1 2 3] [1 2 3])

((derivative i2 1 {:h 1.0e-8}) 1)
;; => -0.6908866452748006
;; => -0.6908866453519957
;; => -0.6908866453381179

(i2' 1)
;; => -0.690886645338018

(defn ftest
  ^double [[^double x ^double y ^double z]]
  (m/sin (m/+ x (m/* 3.0 (m/sin (m/+ (m/* 2.0 y)
                                     (m/* 4.0 (m/sin (m/* 3.0 z)))))))))

(time (cubature ftest [0 0 0] [1 1 1] {:initdiv 3}))


(defn i2
  ^double [^double x]
  (m// (m/cos x)
       (m/inc (m/* x x))))

(defn i2'
  ^double [^double x]
  (let [d (m/inc (m/* x x))]
    (m/- (m// (m/+ (m/* d (m/sin x))
                   (m/* 2.0 x (m/cos x)))
              (m/sq d)))))

(m// m/PI m/E)
;; => 1.1557273497909217

(integrate (fn [^double x] (m/exp x)) ##-Inf 1.0 {:integrator :gausskronrod})

(defn cos200x
  ^double [^double x]
  (m/cos (m/* 200.0 x)))

(integrate cos200x 0.0 1.0 {:integrator :gausskronrod :max-iters 20})
;; => {:result -0.004366486486077295, :iterations 4, :evaluations 819, :integrator :legendregauss}
;; => {:result 0.8223681763590893, :iterations 4, :evaluations 9, :integrator :romberg}

(integrate (fn [^double x] (m/sin x)) 0.0 1.0 {:integrator :gausskronrod :initdiv 5})
;; => {:result 0.45969769413186023, :iterations 2, :evaluations 70, :integrator :legendregauss}
;; => {:result 0.4596976965677095, :iterations 6, :evaluations 35, :integrator :simpson}
;; => {:result 0.4596975479978896, :iterations 9, :evaluations 513, :integrator :trapezoid}
;; => {:result 0.4596976965677095, :iterations 6, :evaluations 35, :integrator :simpson}
;; => {:result 0.4596976942278417, :iterations 4, :evaluations 9, :integrator :romberg}

;;

;; integral_0^1 integral_0^1 sin(x) cos(y) dx dy = 2 sin^2(1/2) sin(1)≈0.38682227139505565895449238867442658

(def result-sinxcosy 0.38682227139505565895449238867442658)

(defn sinxcosy
  ^double [v]
  (m/* (m/sin (v 0)) (m/cos (v 1))))

(time (vegas sinxcosy [[0 1] [0 1]] {:max-iters 10 :ncalls 10000 :random-sequence :r2 :nstrats 5 :warmup 5}))
;; => {:iters 2, :dof 1, :Q 0.7127345486661348, :nstrats 5, :total-calls 70280, :chi2-avg 0.13556093057669014, :result 0.3869527936845437, :nbins 1000, :sd 1.8083497089536342E-4}

(defn u2
  ^double [v]
  (v/sum (v/sq v)))

(vegas u2 [[-1 1] [-1 1] [-2 2]] {:warmup 5 :max-iters 10 :ncalls 10000 :alpha 0.5
                                  :random-sequence :sobol :nstrats 5 :jitter 0.75})
;; => {:iters 10, :dof 9, :Q 0.6777327223191949, :nstrats 5, :total-calls 152878, :chi2-avg 0.7343563112233644, :result 31.99892944378498, :nbins 1000, :sd 0.008143720148407148}

(defn sinu
  ^double [v]
  (v/sum (v/sin v)))

(defn cosu
  ^double [v]
  (v/sum (v/cos v)))

(vegas cosu [[1 3] [1 3] [1 3]] {:max-iters 20 :ncalls 50000 :nstrats 10 :warmup 5})


(let [pdf (partial r/pdf (r/distribution :multi-normal {:means (repeat 3 0)}))]
  (vegas pdf (repeat 3 [-12 12]) {:random-sequence :sobol :nstrats 10 :warmup 5 :ncalls 100000 :max-iters 20
                                  :rel 1.0e-5}))

(defn xexpx
  ^double [vx]
  (m/exp (m/* -10.0 (v/mag vx))))

(vegas xexpx [[0 1] [0 1]] {:random-sequence :uniform :jitter 0.5 :warmup 5 :beta 0.75 :alpha 0.5})

(defn f1
  ^double [[^double x ^double y ^double z]]
  (m/* (m/sin x) (m/cos y) (m/exp z)))

(defn f2
  ^double [[^double x ^double y ^double z]]
  (m/* (m// (m/+ 0.03 (m/sq (m/+ x y))))
       (m/cos y) (m/exp z)))

(defn f4
  ^double [v]
  (m/abs (m/- (v/magsq v) 0.125)))

(defn f5
  ^double [v]
  (m/exp (m/- (v/magsq v))))


(defn f11
  ^double [v]
  (if (m/< (v/magsq v) 1.0) 1.0 0.0))

(defn f12
  ^double [[^double x ^double y ^double z]]
  (m/sin (m/+ x (m/* 3.0 (m/sin (m/+ (m/* 2.0 y)
                                     (m/* 4.0 (m/sin (m/* 3.0 z)))))))))

(vegas f12 [[0 1] [0 1] [0 1]] {:max-iters 20 :ncalls 10000 :nstrats 2 :rel 1.0e-3 :abs 1.0e-12
                                :random-sequence :halton :jitter 0.25})

#_(vegas f12 [[0 3] [0 3] [0 3]] {:max-iters 50 :ncalls 50000 :rel 1.0e-4 :abs 1.0e-12
                                  :random-sequence :halton :jitter 0.75})

(vegas (constantly 1) (repeat 15 0) (repeat 15 1))

(vegas v/sum [0 0 0] [1 1 1])

(vegas (comp v/sum v/sin) (repeat 4 0) (repeat 4 1))


