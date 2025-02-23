;; Create a vertical layout with multiple buttons
(def layout (android.widget.LinearLayout. *activity*))
(.setOrientation layout android.widget.LinearLayout/VERTICAL)
(.addView *root-layout* layout)

;; Add some buttons with click handlers
(doseq [i (range 3)]
  (let [btn (android.widget.Button. *activity*)]
    (.setText btn (str "Button " i))
    (.setOnClickListener btn
      (reify android.view.View$OnClickListener
        (onClick [this v]
          (.setText btn (str "Clicked " i "!")))))
    (.addView layout btn)))
