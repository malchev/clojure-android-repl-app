;; Five bouncing balls that alternate red/green, expand/shrink, and bounce off each other and the walls.

(import '[android.content Context]
        '[android.graphics Canvas Paint Paint$Style Color]
        '[android.view View]
        '[android.widget LinearLayout]
        '[android.os Handler Looper]
        '[android.util Log]
        '[androidx.lifecycle LifecycleEventObserver]
        '[androidx.lifecycle Lifecycle$Event Lifecycle$State])

(def TAG "ClojureApp")

(defn debug-log [msg]
  (Log/d TAG (str msg)))

(defn interpolate-color [color1 color2 factor]
  "Interpolate between two colors based on factor (0.0 to 1.0)"
  (let [r1 (bit-and (bit-shift-right color1 16) 0xFF)
        g1 (bit-and (bit-shift-right color1 8) 0xFF)
        b1 (bit-and color1 0xFF)
        r2 (bit-and (bit-shift-right color2 16) 0xFF)
        g2 (bit-and (bit-shift-right color2 8) 0xFF)
        b2 (bit-and color2 0xFF)
        r (int (+ r1 (* factor (- r2 r1))))
        g (int (+ g1 (* factor (- g2 g1))))
        b (int (+ b1 (* factor (- b2 b1))))]
    (Color/rgb r g b)))

(defn distance [x1 y1 x2 y2]
  "Calculate distance between two points"
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1)) (* (- y2 y1) (- y2 y1)))))

(defn handle-ball-collision [ball1 ball2]
  "Handle collision between two balls using conservation of momentum"
  (let [dx (- (:x @ball2) (:x @ball1))
        dy (- (:y @ball2) (:y @ball1))
        dist (distance (:x @ball1) (:y @ball1) (:x @ball2) (:y @ball2))
        min-dist (+ (:radius @ball1) (:radius @ball2))]
    (when (< dist min-dist)
      (let [nx (if (zero? dist) 1.0 (/ dx dist))
            ny (if (zero? dist) 0.0 (/ dy dist))
            dvx (- (:vx @ball2) (:vx @ball1))
            dvy (- (:vy @ball2) (:vy @ball1))
            dvn (+ (* dvx nx) (* dvy ny))]
        (when (< dvn 0)
          (let [impulse dvn
                new-vx1 (+ (:vx @ball1) (* impulse nx))
                new-vy1 (+ (:vy @ball1) (* impulse ny))
                new-vx2 (- (:vx @ball2) (* impulse nx))
                new-vy2 (- (:vy @ball2) (* impulse ny))
                overlap (- min-dist dist)
                separation (/ overlap 2)
                sep-x (* separation nx)
                sep-y (* separation ny)]
            (swap! ball1 assoc
                   :vx new-vx1 :vy new-vy1
                   :x (- (:x @ball1) sep-x)
                   :y (- (:y @ball1) sep-y))
            (swap! ball2 assoc
                   :vx new-vx2 :vy new-vy2
                   :x (+ (:x @ball2) sep-x)
                   :y (+ (:y @ball2) sep-y))))))))

(defn update-ball-position [ball width height]
  "Update ball position and handle wall bouncing"
  (let [radius (:radius @ball)
        new-x (+ (:x @ball) (:vx @ball))
        new-y (+ (:y @ball) (:vy @ball))]
    (let [bounce-x (or (<= new-x radius) (>= new-x (- width radius)))
          bounce-y (or (<= new-y radius) (>= new-y (- height radius)))
          final-vx (if bounce-x (- (:vx @ball)) (:vx @ball))
          final-vy (if bounce-y (- (:vy @ball)) (:vy @ball))
          final-x (max radius (min (- width radius) new-x))
          final-y (max radius (min (- height radius) new-y))]
      (swap! ball assoc
             :x final-x :y final-y
             :vx final-vx :vy final-vy))))

(defn random-between [a b]
  (+ a (* (Math/random) (- b a))))

(defn generate-initial-balls [width height]
  "Generate five balls with initial positions and velocities, spaced apart"
  (let [center-x (/ width 2.0)
        center-y (/ height 2.0)
        angle-step (/ (* 2 Math/PI) 5)
        base-radius (min (/ width 12.0) (/ height 12.0))
        min-speed 2.5
        max-speed 4.5]
    (vec
     (for [i (range 5)]
       (let [angle (* i angle-step)
             phase-offset (* i (/ Math/PI 2.0))
             speed (random-between min-speed max-speed)
             vx (* speed (Math/cos angle))
             vy (* speed (Math/sin angle))
             ;; Place balls in a pentagon formation
             pos-radius (min (/ width 3.0) (/ height 3.0))
             x (+ center-x (* pos-radius (Math/cos angle)))
             y (+ center-y (* pos-radius (Math/sin angle)))]
         (atom {:x x :y y :vx vx :vy vy
                :phase-offset phase-offset :radius base-radius}))))))

(defn handle-all-collisions [balls]
  "Handle collisions between all unique pairs of balls"
  (doseq [i (range (count balls))
          j (range (inc i) (count balls))]
    (handle-ball-collision (balls i) (balls j))))

(defn create-five-balls-view [context]
  (let [paint (Paint.)
        handler (Handler. (Looper/getMainLooper))
        start-time (atom (System/currentTimeMillis))
        view-ref (atom nil)
        animation-runnable (atom nil)
        balls-ref (atom nil)]
    ;; Configure paint
    (.setStyle paint Paint$Style/FILL)
    (.setAntiAlias paint true)
    (debug-log "Creating five bouncing balls view")
    (let [view (proxy [View] [context]
                 (onDraw [canvas]
                   (let [width (.getWidth this)
                         height (.getHeight this)
                         current-time (System/currentTimeMillis)
                         elapsed-time (- current-time @start-time)
                         time-factor (/ elapsed-time 3000.0)]
                     (when (and (> width 0) (> height 0))
                       (when (nil? @balls-ref)
                         ;; Initialize balls only once when we know the width/height
                         (reset! balls-ref (generate-initial-balls width height)))
                       ;; Fill background white for high contrast
                       (.drawColor canvas Color/WHITE)
                       ;; Update and draw balls
                       (doseq [ball @balls-ref]
                         (update-ball-position ball width height))
                       (handle-all-collisions @balls-ref)
                       (doseq [idx (range (count @balls-ref))]
                         (let [ball (@balls-ref idx)
                               ball-data @ball
                               ;; Size animation
                               max-radius (/ (min width height) 12.0)
                               min-radius (/ max-radius 2.5)
                               size-sine (Math/sin (+ (* time-factor 2 Math/PI) (:phase-offset ball-data)))
                               radius (+ min-radius (* (+ size-sine 1) (/ (- max-radius min-radius) 2)))
                               ;; Color alternation
                               color-sine (Math/cos (+ (* time-factor 2 Math/PI) (:phase-offset ball-data)))
                               color-factor (/ (+ color-sine 1) 2)
                               current-color (interpolate-color Color/RED Color/GREEN color-factor)]
                           (swap! ball assoc :radius radius)
                           (.setColor paint current-color)
                           (.drawCircle canvas (:x ball-data) (:y ball-data) radius paint)))
                       (debug-log (str "Frame drawn at time " elapsed-time " ms")))))
                 (onSizeChanged [w h oldw oldh]
                   (debug-log (str "View size changed: " w "x" h))
                   (when (and (> w 0) (> h 0))
                     ;; Re-initialize balls with new size
                     (reset! balls-ref (generate-initial-balls w h)))
                   (proxy-super onSizeChanged w h oldw oldh))
                 (onAttachedToWindow []
                   (debug-log "View attached to window - starting five balls animation")
                   (proxy-super onAttachedToWindow)
                   (reset! start-time (System/currentTimeMillis))
                   (letfn [(animate-fn []
                             (when @view-ref
                               (.invalidate @view-ref))
                             (.postDelayed handler @animation-runnable 16))]
                     (reset! animation-runnable animate-fn)
                     (animate-fn)))
                 (onDetachedFromWindow []
                   (debug-log "View detached from window - stopping animation")
                   (.removeCallbacksAndMessages handler nil)
                   (proxy-super onDetachedFromWindow)))]
      (reset! view-ref view)
      (debug-log "Five balls view created")
      view)))

(defn lifecycle-handler [source event]
  (debug-log (str "Lifecycle event: " (.name event)))
  (case (.name event)
    "ON_CREATE" (debug-log "Activity created")
    "ON_START" (debug-log "Activity started")
    "ON_RESUME" (debug-log "Activity resumed")
    "ON_PAUSE" (debug-log "Activity paused")
    "ON_STOP" (debug-log "Activity stopped")
    "ON_DESTROY" (debug-log "Activity destroyed")
    (debug-log (str "Unhandled lifecycle event: " (.name event)))))

(defn setup-lifecycle-observer []
  (let [lifecycle-observer
        (proxy [LifecycleEventObserver] []
          (onStateChanged [source event]
            (lifecycle-handler source event)))]
    (try
      (let [lifecycle (.. *context* (getLifecycle))]
        (debug-log (str "Current lifecycle state: " (.. lifecycle (getCurrentState) (name))))
        (.addObserver lifecycle lifecycle-observer)
        (debug-log "Lifecycle observer registered successfully"))
      (catch Exception e
        (debug-log (str "Error registering lifecycle observer: " (.getMessage e)))))))

(defn -main []
  (debug-log "Starting five bouncing balls app")
  (try
    (setup-lifecycle-observer)
    (let [balls-view (create-five-balls-view *context*)]
      (let [layout-params (android.widget.LinearLayout$LayoutParams.
                           android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                           android.widget.LinearLayout$LayoutParams/MATCH_PARENT)]
        (.setLayoutParams balls-view layout-params))
      (.addView *content-layout* balls-view)
      (debug-log "Five bouncing balls app setup complete"))
    (catch Exception e
      (debug-log (str "Error in main: " (.getMessage e)))
      (.printStackTrace e))))
