;; Analog Clock App - Fixed visibility by drawing a dark clock face background
(import '[android.content Context]
        '[android.view View]
        '[android.graphics Canvas Paint Paint$Style Paint$Cap Color Rect Path RectF]
        '[android.util Log]
        '[java.util Calendar TimeZone]
        '[android.os Handler Looper]
        '[android.widget LinearLayout LinearLayout$LayoutParams]
        '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event])

(def TAG "ClojureApp")

(defn make-paint [color style width cap]
  (doto (Paint.)
    (.setColor color)
    (.setStyle style)
    (.setStrokeWidth (float width))
    (.setStrokeCap cap)
    (.setAntiAlias true)))

(defn -main []
  (Log/d TAG "Starting Analog Clock App")

  ;; Configure the main layout
  (doto *content-layout*
    (.setBackgroundColor (Color/parseColor "#EEEEEE")) ; Light background for contrast with the dark clock body
    (.setGravity android.view.Gravity/CENTER))

  (let [handler (Handler. (Looper/getMainLooper))
        calendar (Calendar/getInstance)
        
        ;; Define Paints
        ;; Dark background for the clock face so white hands are visible
        face-paint (make-paint (Color/parseColor "#121212") Paint$Style/FILL 0 Paint$Cap/BUTT)
        rim-paint (make-paint (Color/parseColor "#333333") Paint$Style/STROKE 16.0 Paint$Cap/ROUND)
        
        ;; Ticks and Hands
        tick-paint-hour (make-paint (Color/parseColor "#E0E0E0") Paint$Style/STROKE 8.0 Paint$Cap/ROUND)
        tick-paint-min (make-paint (Color/parseColor "#666666") Paint$Style/STROKE 4.0 Paint$Cap/ROUND)
        
        ;; Hands - White color requires dark background
        hour-hand-paint (make-paint (Color/WHITE) Paint$Style/STROKE 16.0 Paint$Cap/ROUND)
        min-hand-paint (make-paint (Color/WHITE) Paint$Style/STROKE 10.0 Paint$Cap/ROUND)
        sec-hand-paint (make-paint (Color/parseColor "#FF5252") Paint$Style/STROKE 4.0 Paint$Cap/ROUND)
        center-paint (make-paint (Color/parseColor "#FF5252") Paint$Style/FILL 0 Paint$Cap/BUTT)

        ;; Custom Clock View
        clock-view (proxy [View] [*context*]
                     (onDraw [canvas]
                       (let [w (.getWidth this)
                             h (.getHeight this)
                             cx (/ w 2.0)
                             cy (/ h 2.0)
                             radius (* (min w h) 0.40)]
                         
                         ;; Update time
                         (.setTimeInMillis calendar (System/currentTimeMillis))
                         (let [hours (.get calendar Calendar/HOUR)
                               mins (.get calendar Calendar/MINUTE)
                               secs (.get calendar Calendar/SECOND)
                               milis (.get calendar Calendar/MILLISECOND)
                               
                               ;; Calculate angles
                               sec-angle (* 6.0 (+ secs (/ milis 1000.0)))
                               min-angle (* 6.0 (+ mins (/ secs 60.0)))
                               hour-angle (* 30.0 (+ hours (/ mins 60.0)))]
                           
                           ;; 1. Draw Clock Face Background (Fixes visibility issue)
                           (.drawCircle canvas cx cy radius face-paint)
                           
                           ;; 2. Draw Rim
                           (.drawCircle canvas cx cy radius rim-paint)
                           
                           ;; 3. Draw Ticks
                           (doto canvas (.save))
                           (dotimes [i 60]
                             (let [is-hour (zero? (mod i 5))
                                   tick-len (if is-hour 35.0 15.0)
                                   p (if is-hour tick-paint-hour tick-paint-min)]
                               ;; Draw ticks slightly inside the radius
                               (.drawLine canvas cx (- cy radius -8) cx (+ (- cy radius) tick-len) p)
                               (.rotate canvas 6.0 cx cy)))
                           (.restore canvas)
                           
                           ;; Helper to draw rotated hands
                           (letfn [(draw-hand [angle len paint]
                                     (doto canvas
                                       (.save)
                                       (.rotate angle cx cy)
                                       (.drawLine cx cy cx (- cy len) paint)
                                       (.restore)))]
                             
                             ;; 4. Draw Hands
                             (draw-hand hour-angle (* radius 0.5) hour-hand-paint)
                             (draw-hand min-angle (* radius 0.75) min-hand-paint)
                             (draw-hand sec-angle (* radius 0.85) sec-hand-paint))
                             
                           ;; 5. Draw Center Dot
                           (.drawCircle canvas cx cy 12.0 center-paint)))))

        ;; Animation Loop
        ticker (proxy [Runnable] []
                 (run []
                   (.invalidate clock-view)
                   (.postDelayed handler this 16)))

        ;; Lifecycle Observer
        lifecycle-observer (proxy [LifecycleEventObserver] []
                             (onStateChanged [source event]
                               (cond
                                 (= event Lifecycle$Event/ON_RESUME)
                                 (do 
                                   (Log/d TAG "Resuming clock animation")
                                   (.post handler ticker))
                                 
                                 (= event Lifecycle$Event/ON_PAUSE)
                                 (do
                                   (Log/d TAG "Pausing clock animation")
                                   (.removeCallbacks handler ticker)))))]

    ;; Add view to layout
    (.addView *content-layout* clock-view 
              (android.widget.LinearLayout$LayoutParams. 
               android.widget.LinearLayout$LayoutParams/MATCH_PARENT 
               android.widget.LinearLayout$LayoutParams/MATCH_PARENT))

    ;; Register Lifecycle Observer
    (try
      (.addObserver (.getLifecycle *context*) lifecycle-observer)
      (catch Exception e
        (Log/e TAG "Failed to register lifecycle observer" e)))))
