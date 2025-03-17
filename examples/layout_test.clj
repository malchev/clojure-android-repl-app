;; Simple layout test
(let [activity *context*
      content *content-layout*]

  ;; Create a simple TextView
  (let [text-view (doto (android.widget.TextView. activity)
                    (.setText "Hello Weather!")
                    (.setTextSize 32.0)
                    (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)))]

    ;; Clear and add the view
    (.removeAllViews content)
    (.addView content text-view))

  "Test layout added")
