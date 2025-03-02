package com.example.clojurerepl;

import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import clojure.lang.DynamicClassLoader;
import android.util.Log;
import java.lang.reflect.Field;
import android.content.Intent;
import clojure.lang.Symbol;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.TextView;

public class RenderActivity extends AppCompatActivity {
    private static final String TAG = "ClojureRender";
    private LinearLayout contentLayout;
    private Var contextVar;
    private Var contentLayoutVar;
    private Var nsVar;
    private DynamicClassLoader clojureClassLoader;
    private long activityStartTime;
    private TextView timingView;
    private StringBuilder timingData = new StringBuilder();
    private volatile boolean isDestroyed = false;
    private Thread evaluationThread = null;

    private class UiSafeViewGroup extends LinearLayout {
        private final LinearLayout delegate;

        public UiSafeViewGroup(LinearLayout delegate) {
            super(delegate.getContext());
            this.delegate = delegate;
        }

        @Override
        public void addView(android.view.View child) {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                delegate.addView(child);
            } else {
                runOnUiThread(() -> delegate.addView(child));
            }
        }

        @Override
        public void addView(android.view.View child, android.view.ViewGroup.LayoutParams params) {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                delegate.addView(child, params);
            } else {
                runOnUiThread(() -> delegate.addView(child, params));
            }
        }

        @Override
        public void removeAllViews() {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                delegate.removeAllViews();
            } else {
                runOnUiThread(() -> delegate.removeAllViews());
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activityStartTime = System.currentTimeMillis();
        Log.d(TAG, "RenderActivity onCreate started");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_render);
        
        // Add timing view at the top
        timingView = new TextView(this);
        timingView.setTextSize(12);
        timingView.setPadding(16, 16, 16, 16);
        timingView.setTypeface(Typeface.MONOSPACE);
        timingView.setTextColor(Color.parseColor("#1976D2")); // Material Blue
        timingView.setBackgroundColor(Color.parseColor("#F5F5F5")); // Light Gray
        
        contentLayout = findViewById(R.id.content_layout);
        LinearLayout root = (LinearLayout) contentLayout.getParent();
        root.addView(timingView, 0);
        
        // Set content layout background for better contrast
        contentLayout.setBackgroundColor(Color.WHITE);
        
        Log.d(TAG, "Content layout found: " + (contentLayout != null));
        
        try {
            long rtStartTime = System.currentTimeMillis();
            // Initialize RT before any Clojure operations
            Log.d(TAG, "Initializing RT");
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            RT.init();
            long rtTime = System.currentTimeMillis() - rtStartTime;
            Log.d(TAG, "RT initialized successfully in " + rtTime + "ms");
            updateTimings("RT init", rtTime);
            
            long classLoaderStartTime = System.currentTimeMillis();
            Log.d(TAG, "Setting up Clojure class loader");
            setupClojureClassLoader();
            long classLoaderTime = System.currentTimeMillis() - classLoaderStartTime;
            Log.d(TAG, "Class loader setup completed in " + classLoaderTime + "ms");
            updateTimings("ClassLoader", classLoaderTime);
            
            long varsStartTime = System.currentTimeMillis();
            Log.d(TAG, "Setting up Clojure vars");
            setupClojureVars();
            long varsTime = System.currentTimeMillis() - varsStartTime;
            Log.d(TAG, "Vars setup completed in " + varsTime + "ms");
            updateTimings("Vars setup", varsTime);
            
            long envStartTime = System.currentTimeMillis();
            Log.d(TAG, "Initializing Clojure environment");
            initializeClojureEnvironment();
            long envTime = System.currentTimeMillis() - envStartTime;
            Log.d(TAG, "Clojure environment setup complete in " + envTime + "ms");
            updateTimings("Env init", envTime);
            
            // Get the code from the intent
            Intent intent = getIntent();
            if (intent == null) {
                Log.e(TAG, "No intent provided");
                showError("No intent provided");
                return;
            }
            
            String code = intent.getStringExtra("code");
            Log.d(TAG, "Received intent with code: " + (code != null ? "length=" + code.length() : "null"));
            
            if (code != null && !code.trim().isEmpty()) {
                Log.d(TAG, "About to render code");
                renderCode(code);
            } else {
                Log.w(TAG, "No code provided in intent");
                showError("No code provided in intent");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in RenderActivity", e);
            showError("Error: " + e.getMessage());
        }
    }

    private void updateTimings(String stage, long timeMs) {
        runOnUiThread(() -> {
            String entry = String.format("%s: %dms\n", stage, timeMs);
            timingData.append(entry);
            timingView.setText(timingData.toString());
            
            // Send timing data back to MainActivity
            Intent resultIntent = new Intent();
            resultIntent.putExtra("timings", timingData.toString());
            setResult(RESULT_OK, resultIntent);
        });
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "Back button pressed, marking activity as destroyed");
        isDestroyed = true;
        
        // Interrupt the evaluation thread if it's running
        if (evaluationThread != null && evaluationThread.isAlive()) {
            Log.d(TAG, "Interrupting evaluation thread");
            evaluationThread.interrupt();
            try {
                // Wait briefly for the thread to clean up
                evaluationThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for evaluation thread to finish");
            }
        }

        // Ensure final timing data is sent back
        Intent resultIntent = new Intent();
        resultIntent.putExtra("timings", timingData.toString());
        setResult(RESULT_OK, resultIntent);
        
        super.onBackPressed();
    }

    private void setupClojureClassLoader() {
        try {
            // Create a custom class loader that can handle dynamic classes
            clojureClassLoader = new DynamicClassLoader(getClass().getClassLoader());
            
            // Set up the Android delegate
            AndroidClassLoaderDelegate delegate = new AndroidClassLoaderDelegate(
                getApplicationContext(),
                clojureClassLoader
            );
            
            // Set the delegate via reflection since we're using our own implementation
            Field delegateField = DynamicClassLoader.class.getDeclaredField("androidDelegate");
            delegateField.setAccessible(true);
            delegateField.set(null, delegate);
            
            // Set the context class loader
            Thread.currentThread().setContextClassLoader(clojureClassLoader);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up class loader", e);
            throw new RuntimeException(e);
        }
    }

    private void setupClojureVars() {
        try {
            nsVar = RT.var("clojure.core", "*ns*");
            contextVar = RT.var("clojure.core", "*context*");
            contentLayoutVar = RT.var("clojure.core", "*content-layout*");
            
            contextVar.setDynamic(true);
            contentLayoutVar.setDynamic(true);
            
            // Bind these vars permanently, using the UI-safe wrapper for contentLayout
            contextVar.bindRoot(this);
            contentLayoutVar.bindRoot(new UiSafeViewGroup(contentLayout));
        } catch (Exception e) {
            Log.e(TAG, "Error setting up vars", e);
            throw new RuntimeException(e);
        }
    }

    private void initializeClojureEnvironment() {
        try {
            // Create and switch to user namespace
            Object userNS = RT.var("clojure.core", "find-ns").invoke(RT.var("clojure.core", "symbol").invoke("user"));
            if (userNS == null) {
                // Create the namespace if it doesn't exist
                userNS = RT.var("clojure.core", "create-ns").invoke(RT.var("clojure.core", "symbol").invoke("user"));
            }
            
            // Switch to user namespace and set it up
            Var.pushThreadBindings(RT.map(nsVar, userNS));
            try {
                // Refer all clojure.core functions
                RT.var("clojure.core", "refer").invoke(RT.var("clojure.core", "symbol").invoke("clojure.core"));
                
                // Define the vars in the user namespace
                RT.var("clojure.core", "intern").invoke(
                    userNS,
                    Symbol.intern("*context*"),
                    this
                );
                RT.var("clojure.core", "intern").invoke(
                    userNS,
                    Symbol.intern("*content-layout*"),
                    new UiSafeViewGroup(contentLayout)
                );
                
                Log.d(TAG, "Vars defined in user namespace");
            } finally {
                Var.popThreadBindings();
            }
            
            Log.d(TAG, "Clojure environment initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure environment", e);
            throw new RuntimeException(e);
        }
    }

    private void renderCode(final String code) {
        Log.d(TAG, "Starting renderCode with code length: " + code.length());
        // Clear existing views on UI thread
        runOnUiThread(() -> contentLayout.removeAllViews());
        
        // Create and store the evaluation thread
        evaluationThread = new Thread(() -> {
            Log.d(TAG, "Starting evaluation thread");
            long evalStartTime = System.currentTimeMillis();
            try {
                if (isDestroyed) {  // Check if already destroyed
                    Log.d(TAG, "Activity destroyed, abandoning evaluation");
                    return;
                }

                // Ensure classloader is set
                Thread.currentThread().setContextClassLoader(clojureClassLoader);
                Log.d(TAG, "ClassLoader set for evaluation thread");
                
                // Push thread bindings for this thread
                Log.d(TAG, "Setting up thread bindings");
                Var.pushThreadBindings(RT.map(
                    nsVar, RT.var("clojure.core", "find-ns").invoke(RT.var("clojure.core", "symbol").invoke("user")),
                    contextVar, RenderActivity.this,
                    contentLayoutVar, new UiSafeViewGroup(contentLayout)
                ));
                Log.d(TAG, "Thread bindings established");
                
                try {
                    // Read and evaluate the code
                    Log.d(TAG, "Reading code string");
                    long parseStartTime = System.currentTimeMillis();
                    Object form = RT.var("clojure.core", "read-string").invoke(code);
                    long parseTime = System.currentTimeMillis() - parseStartTime;
                    Log.d(TAG, "Code parsed in " + parseTime + "ms");
                    updateTimings("Parse", parseTime);
                    
                    if (isDestroyed) {  // Check again before evaluation
                        Log.d(TAG, "Activity destroyed before evaluation, abandoning");
                        return;
                    }

                    Log.d(TAG, "Starting evaluation");
                    long execStartTime = System.currentTimeMillis();
                    Object result = RT.var("clojure.core", "eval").invoke(form);
                    long execTime = System.currentTimeMillis() - execStartTime;
                    Log.i(TAG, "Render result: " + result);
                    Log.d(TAG, "Code executed in " + execTime + "ms");
                    updateTimings("Execute", execTime);
                    
                    if (!isDestroyed) {  // Only update total time if not destroyed
                        long totalTime = System.currentTimeMillis() - activityStartTime;
                        updateTimings("Total", totalTime);
                    }
                } finally {
                    try {
                        Log.d(TAG, "Cleaning up thread bindings");
                        Var.popThreadBindings();
                    } catch (Exception e) {
                        Log.w(TAG, "Error cleaning up thread bindings in renderCode", e);
                    }
                }
            } catch (Exception e) {
                if (!isDestroyed) {  // Only show error if not destroyed
                    Log.e(TAG, "Error rendering code", e);
                    Log.e(TAG, "Stack trace: ", e);
                    long errorTime = System.currentTimeMillis() - evalStartTime;
                    updateTimings("Error", errorTime);
                    runOnUiThread(() -> {
                        // Show error in UI
                        android.widget.TextView errorView = new android.widget.TextView(RenderActivity.this);
                        errorView.setText("Error: " + e.getMessage());
                        errorView.setTextColor(Color.parseColor("#D32F2F"));
                        errorView.setBackgroundColor(Color.parseColor("#FFEBEE"));
                        errorView.setPadding(16, 16, 16, 16);
                        contentLayout.addView(errorView);
                    });
                }
            }
        });
        evaluationThread.start();
    }

    private void showError(String message) {
        Log.e(TAG, "Showing error: " + message);
        runOnUiThread(() -> {
            android.widget.TextView errorView = new android.widget.TextView(this);
            errorView.setText(message);
            errorView.setTextColor(Color.parseColor("#D32F2F")); // Material Red
            errorView.setBackgroundColor(Color.parseColor("#FFEBEE")); // Light Red
            errorView.setPadding(16, 16, 16, 16);
            contentLayout.addView(errorView);
        });
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy called");
        isDestroyed = true;

        // Interrupt the evaluation thread if it's running
        if (evaluationThread != null && evaluationThread.isAlive()) {
            Log.d(TAG, "Interrupting evaluation thread in onDestroy");
            evaluationThread.interrupt();
            try {
                // Wait briefly for the thread to clean up
                evaluationThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for evaluation thread to finish");
            }
        }

        super.onDestroy();
        Log.d(TAG, "RenderActivity destroyed");
    }
} 