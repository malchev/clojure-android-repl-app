;; Simple Clojure Android app that draws a rainbow using a custom View

(import
  '[android.util Log]
  '[android.graphics Canvas Paint Color RectF]
  '[android.graphics Paint$Style]
  '[android.view View]
  '[android.widget LinearLayout]
  '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event]
  '[android.content Context]
  '[android.view ViewGroup]
  '[android.widget.LinearLayout$LayoutParams])

(def ^String log-tag "ClojureApp")

(defn logd [msg]
  (Log/d log-tag msg))

;; Rainbow colors (outermost to innermost)
(def rainbow-colors
  [Color/RED
   0xFFFFA500 ; Orange
   Color/YELLOW
   Color/GREEN
   Color/BLUE
   0xFF4B0082 ; Indigo
   0xFF8F00FF ; Violet
  ])

(defn make-rainbow-view [^Context ctx]
  (proxy [View] [ctx]
    (onDraw [^Canvas canvas]
      (logd "RainbowView: onDraw called")
      (let [w (.getWidth this)
            h (.getHeight this)
            min-side (Math/min w h)
            center-x (/ w 2.0)
            center-y (/ h 2.0)
            arc-radius (* 0.95 (/ min-side 2.0)) ; fit into view
            num-bands (count rainbow-colors)
            stroke-width (/ arc-radius 10.0)
            bg-color Color/WHITE
            paint (doto (Paint.)
                    (.setAntiAlias true)
                    (.setStyle android.graphics.Paint$Style/STROKE))]
        ;; Paint background (ensure contrast)
        (.drawColor canvas bg-color)
        (dotimes [i num-bands]
          (let [color (nth rainbow-colors i)
                band-radius (- arc-radius (* i (+ stroke-width 4)))
                left (- center-x band-radius)
                top  (- center-y band-radius)
                right (+ center-x band-radius)
                bottom (+ center-y band-radius)
                rect (RectF. left top right bottom)]
            (.setStrokeWidth paint stroke-width)
            (.setColor paint color)
            ;; Draw semicircle arc (180 deg, sweep 180 deg)
            (.drawArc canvas rect 180 180 false paint)))))))

;; Lifecycle observer for debugging
(defn make-lifecycle-observer []
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (logd (str "Lifecycle event: " (.name event))))))

(defn setup-layout []
  (logd "Setting up layout")
  (let [ll (LinearLayout. *context*)]
    (.setOrientation ll LinearLayout/VERTICAL)
    (.setBackgroundColor ll Color/WHITE) ;; Bright background for contrast
    (let [params (android.widget.LinearLayout$LayoutParams.
                   android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                   android.widget.LinearLayout$LayoutParams/MATCH_PARENT)
          rainbow-view (make-rainbow-view *context*)]
      (.setLayoutParams rainbow-view params)
      (.addView ll rainbow-view)
      ;; Attach to root
      (.removeAllViews *content-layout*)
      (.addView *content-layout* ll params)
      (logd "Layout setup complete"))))

(defn -main []
  (logd "-main entry")
  (try
    (let [lifecycle (.. *context* (getLifecycle))]
      (logd (str "Current lifecycle state: " (.name (.getCurrentState lifecycle))))
      (let [observer (make-lifecycle-observer)]
        (.addObserver lifecycle observer)
        (logd "Lifecycle observer registered")))
    (catch Exception e
      (logd (str "Exception registering lifecycle observer: " (.getMessage e)))))
  (setup-layout)
  (logd "-main exit"))
