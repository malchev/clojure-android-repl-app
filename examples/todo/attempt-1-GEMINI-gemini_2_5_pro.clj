;; A simple To-Do list application for Android.
(import '(android.util Log)
        '(android.view View Gravity)
        '(android.widget Button EditText LinearLayout ScrollView TextView)
        '(android.graphics Color)
        '(java.io File)
        '(androidx.lifecycle LifecycleEventObserver Lifecycle$Event))

(defonce TAG "ClojureApp")
(defonce todos-atom (atom []))

(defn log-debug [message]
  (Log/d TAG (str message)))

(defn -main []
  (log-debug "App starting, -main function invoked.")
  (.setBackgroundColor *content-layout* Color/WHITE)

  (let [;; --- UI Components ---
        main-layout (LinearLayout. *context*)
        input-layout (LinearLayout. *context*)
        edit-text (EditText. *context*)
        add-button (Button. *context*)
        scroll-view (ScrollView. *context*)
        todo-list-layout (LinearLayout. *context*)

        ;; --- Persistence ---
        persistence-file (File. *cache-dir* "todos.edn")

        ;; --- Forward declaration for rendering function ---
        ;; This atom holds the render function. It's used to break the
        ;; circular dependency: event handlers call render, and render creates
        ;; event handlers.
        render-fn (atom nil)

        ;; --- Core Logic Functions ---
        remove-todo-handler (fn [todo-id]
                              (log-debug (str "Attempting to remove item with id: " todo-id))
                              (swap! todos-atom (fn [current-todos]
                                                  (vec (remove #(= (:id %) todo-id) current-todos))))
                              (@render-fn)) ; Re-render the list

        add-todo-handler (fn []
                           (let [text (-> edit-text .getText .toString .trim)]
                             (if (not (.isEmpty text))
                               (do
                                 (log-debug (str "Adding new item: " text))
                                 (let [new-item {:id (System/currentTimeMillis) :text text}]
                                   (swap! todos-atom conj new-item))
                                 (.setText edit-text "")
                                 (@render-fn)) ; Re-render the list
                               (log-debug "Add button clicked but input was empty."))))

        render-todos (fn []
                       (log-debug (str "Rendering " (count @todos-atom) " to-do items."))
                       (.removeAllViews todo-list-layout)
                       (doseq [item @todos-atom]
                         (let [item-layout (LinearLayout. *context*)
                               text-view (TextView. *context*)
                               done-button (Button. *context*)
                               text-params (android.widget.LinearLayout$LayoutParams.
                                             0
                                             android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                                             1.0)
                               button-params (android.widget.LinearLayout$LayoutParams.
                                               android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                                               android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)]

                           ;; Configure item layout (horizontal)
                           (.setOrientation item-layout LinearLayout/HORIZONTAL)
                           (.setPadding item-layout 16 8 16 8)

                           ;; Configure TextView for the to-do item
                           (.setText text-view (:text item))
                           (.setTextColor text-view Color/BLACK)
                           (.setTextSize text-view 18.0)
                           (.setLayoutParams text-view text-params)

                           ;; Configure "Done" button
                           (.setText done-button "Done")
                           (.setLayoutParams done-button button-params)
                           (.setOnClickListener done-button
                             (proxy [View$OnClickListener] []
                               (onClick [_] (remove-todo-handler (:id item)))))

                           ;; Add views to item layout
                           (.addView item-layout text-view)
                           (.addView item-layout done-button)

                           ;; Add the complete item view to the main list
                           (.addView todo-list-layout item-layout))))

        ;; --- Persistence Functions ---
        save-todos (fn []
                     (try
                       (log-debug (str "Saving " (count @todos-atom) " items to " (.getAbsolutePath persistence-file)))
                       (spit persistence-file (pr-str @todos-atom))
                       (catch Exception e
                         (log-debug (str "Failed to save todos: " (.getMessage e))))))

        load-todos (fn []
                     (try
                       (if (.exists persistence-file)
                         (let [data (slurp persistence-file)
                               todos (clojure.edn/read-string data)]
                           (reset! todos-atom (vec todos))
                           (log-debug (str "Loaded " (count @todos-atom) " items from file.")))
                         (log-debug "Persistence file not found. Starting with empty list."))
                       (catch Exception e
                         (log-debug (str "Failed to load todos, starting fresh: " (.getMessage e)))
                         (reset! todos-atom [])))
                     (@render-fn)) ; Render after loading

        ;; --- Android Lifecycle Observer ---
        lifecycle-observer (proxy [LifecycleEventObserver] []
                             (onStateChanged [source event]
                               (let [event-name (.name event)]
                                 (log-debug (str "Lifecycle event: " event-name))
                                 (condp = event-name
                                   "ON_CREATE" (load-todos)
                                   "ON_STOP" (save-todos)
                                   nil))))]

    ;; --- UI Assembly and Initialization ---

    ;; Store the render function in the atom so handlers can call it
    (reset! render-fn render-todos)

    ;; Configure main layout
    (.setOrientation main-layout LinearLayout/VERTICAL)

    ;; Configure input area
    (.setOrientation input-layout LinearLayout/HORIZONTAL)
    (.setHint edit-text "Enter a new to-do item")
    (.setTextColor edit-text Color/BLACK)
    (.setHintTextColor edit-text Color/GRAY)
    (.setLayoutParams edit-text (android.widget.LinearLayout$LayoutParams.
                                  0
                                  android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                                  1.0))
    (.setText add-button "Add")
    (.setOnClickListener add-button
      (proxy [View$OnClickListener] []
        (onClick [_] (add-todo-handler))))

    (.addView input-layout edit-text)
    (.addView input-layout add-button)

    ;; Configure the scrollable list area
    (.setOrientation todo-list-layout LinearLayout/VERTICAL)
    (.addView scroll-view todo-list-layout)

    ;; Add all parts to the main layout
    (.addView main-layout input-layout)
    (.addView main-layout scroll-view)

    ;; Set the app's content view
    (.addView *content-layout* main-layout)

    ;; --- Register Lifecycle Observer ---
    (try
      (let [lifecycle (.. *context* getLifecycle)]
        (.addObserver lifecycle lifecycle-observer)
        (log-debug (str "Lifecycle observer successfully added. Current state: " (.. lifecycle getCurrentState name))))
      (catch Exception e
        (log-debug (str "ERROR adding lifecycle observer: " e))))

    (log-debug "Main function setup complete.")))
