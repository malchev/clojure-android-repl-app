(ns cljdroid.core
  (:import [android.widget Toast Button TextView EditText]
           [android.app Activity]
           [android.view View ViewGroup$LayoutParams]
           [android.content Context]))

(def ^:dynamic *android-context* nil)

(defn init-context [context]
  (alter-var-root #'*android-context* (constantly context)))

(defn on-ui-thread [f]
  (if (instance? Activity *android-context*)
    (.runOnUiThread ^Activity *android-context* f)
    (f)))

(defn show-toast [message]
  (on-ui-thread
    #(doto (Toast/makeText *android-context* message Toast/LENGTH_SHORT)
       .show)))

(defn create-view [view-type]
  (on-ui-thread
    #(case view-type
       :button (Button. ^Context *android-context*)
       :text-view (TextView. ^Context *android-context*)
       :edit-text (EditText. ^Context *android-context*)
       (throw (Exception. (str "Unknown view type: " view-type))))))

(defn add-view [^View view]
  (when (instance? Activity *android-context*)
    (on-ui-thread
      #(.addContentView ^Activity *android-context*
                       view
                       (ViewGroup$LayoutParams.
                         ViewGroup$LayoutParams/WRAP_CONTENT
                         ViewGroup$LayoutParams/WRAP_CONTENT)))))

(defn hello []
  "Hello from Clojure!") 