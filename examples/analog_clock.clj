;; Import necessary Android classes
(import '[android.content Context]
        '[android.graphics Canvas Color Paint Paint$Style RectF]
        '[android.os Handler Looper SystemClock]
        '[android.util AttributeSet]
        '[android.view View]
        '[java.util Calendar])

;; Create a CustomAnalogClockView
(defn make-analog-clock-view [context]
  (let [clock-view (proxy [View] [context]
                     (onDraw [canvas]
                       (let [width (.getWidth this)
                             height (.getHeight this)
                             center-x (/ width 2)
                             center-y (/ height 2)
                             radius (min center-x center-y)
                             circle-radius (* radius 0.9)

                             ;; Create paints
                             circle-paint (doto (Paint.)
                                            (.setColor Color/BLACK)
                                            (.setStyle Paint$Style/STROKE)
                                            (.setStrokeWidth 5)
                                            (.setAntiAlias true))
                             center-paint (doto (Paint.)
                                            (.setColor Color/BLACK)
                                            (.setStyle Paint$Style/FILL)
                                            (.setAntiAlias true))
                             hour-paint (doto (Paint.)
                                          (.setColor Color/BLACK)
                                          (.setStyle Paint$Style/STROKE)
                                          (.setStrokeWidth 8)
                                          (.setAntiAlias true))
                             minute-paint (doto (Paint.)
                                            (.setColor Color/BLACK)
                                            (.setStyle Paint$Style/STROKE)
                                            (.setStrokeWidth 4)
                                            (.setAntiAlias true))
                             second-paint (doto (Paint.)
                                            (.setColor Color/RED)
                                            (.setStyle Paint$Style/STROKE)
                                            (.setStrokeWidth 2)
                                            (.setAntiAlias true))
                             tick-paint (doto (Paint.)
                                          (.setColor Color/BLACK)
                                          (.setStyle Paint$Style/STROKE)
                                          (.setStrokeWidth 2)
                                          (.setAntiAlias true))

                             ;; Get current time
                             calendar (Calendar/getInstance)
                             hours (mod (.get calendar Calendar/HOUR) 12)
                             minutes (.get calendar Calendar/MINUTE)
                             seconds (.get calendar Calendar/SECOND)

                             ;; Calculate hand angles
                             hour-angle (+ (* hours 30) (* minutes 0.5))
                             minute-angle (* minutes 6)
                             second-angle (* seconds 6)

                             ;; Hand lengths
                             hour-length (* circle-radius 0.5)
                             minute-length (* circle-radius 0.7)
                             second-length (* circle-radius 0.8)]

                         ;; Draw the clock face
                         (.drawCircle canvas center-x center-y circle-radius circle-paint)

                         ;; Draw tick marks for hours
                         (doseq [hour (range 12)]
                           (let [angle (* hour 30)
                                 inner-radius (* circle-radius 0.8)
                                 outer-radius (* circle-radius 0.9)
                                 angle-rad (* angle Math/PI 1/180)
                                 sin-angle (Math/sin angle-rad)
                                 cos-angle (Math/cos angle-rad)
                                 inner-x (+ center-x (* inner-radius sin-angle))
                                 inner-y (- center-y (* inner-radius cos-angle))
                                 outer-x (+ center-x (* outer-radius sin-angle))
                                 outer-y (- center-y (* outer-radius cos-angle))]
                             (.drawLine canvas inner-x inner-y outer-x outer-y tick-paint)))

                         ;; Draw the hour hand
                         (let [angle-rad (* (- hour-angle 90) Math/PI 1/180)
                               sin-angle (Math/sin angle-rad)
                               cos-angle (Math/cos angle-rad)
                               hour-x (+ center-x (* hour-length cos-angle))
                               hour-y (+ center-y (* hour-length sin-angle))]
                           (.drawLine canvas center-x center-y hour-x hour-y hour-paint))

                         ;; Draw the minute hand
                         (let [angle-rad (* (- minute-angle 90) Math/PI 1/180)
                               sin-angle (Math/sin angle-rad)
                               cos-angle (Math/cos angle-rad)
                               minute-x (+ center-x (* minute-length cos-angle))
                               minute-y (+ center-y (* minute-length sin-angle))]
                           (.drawLine canvas center-x center-y minute-x minute-y minute-paint))

                         ;; Draw the second hand
                         (let [angle-rad (* (- second-angle 90) Math/PI 1/180)
                               sin-angle (Math/sin angle-rad)
                               cos-angle (Math/cos angle-rad)
                               second-x (+ center-x (* second-length cos-angle))
                               second-y (+ center-y (* second-length sin-angle))]
                           (.drawLine canvas center-x center-y second-x second-y second-paint))

                         ;; Draw center circle
                         (.drawCircle canvas center-x center-y (* radius 0.05) center-paint))))

        ;; Create a handler for clock updates
        handler (Handler. (Looper/getMainLooper))
        clock-updater (let [view-ref (atom clock-view)]
                        (proxy [Runnable] []
                          (run []
                            ;; Call invalidate on the view reference
                            (.invalidate ^View @view-ref)
                            ;; Reschedule this runnable
                            (.postDelayed handler this 1000))))]

    ;; Set up a listener for when the view is attached to the window
    (.addOnAttachStateChangeListener
     clock-view
     (proxy [android.view.View$OnAttachStateChangeListener] []
       (onViewAttachedToWindow [v]
         ;; Start the updates when the view is attached
         (.postDelayed handler clock-updater 0))
       (onViewDetachedFromWindow [v]
         ;; Remove callbacks when the view is detached
         (.removeCallbacks handler clock-updater))))

    ;; Return the view
    clock-view))

;; Function to create and add the analog clock to the layout
(defn create-analog-clock! []
  (let [context *context*
        content-layout *content-layout*
        clock-view (make-analog-clock-view context)]
    (.addView content-layout clock-view)
    clock-view))

;; Entry point function that takes no arguments
(defn -main []
  (create-analog-clock!))
