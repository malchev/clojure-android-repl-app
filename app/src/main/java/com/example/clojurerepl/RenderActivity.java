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

public class RenderActivity extends AppCompatActivity {
    private static final String TAG = "ClojureRender";
    private LinearLayout contentLayout;
    private Var contextVar;
    private Var contentLayoutVar;
    private Var nsVar;
    private DynamicClassLoader clojureClassLoader;

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
        Log.d(TAG, "RenderActivity onCreate started");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_render);
        
        contentLayout = findViewById(R.id.content_layout);
        Log.d(TAG, "Content layout found: " + (contentLayout != null));
        
        try {
            // Initialize RT before any Clojure operations
            Log.d(TAG, "Initializing RT");
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            RT.init();
            Log.d(TAG, "RT initialized successfully");
            
            Log.d(TAG, "Setting up Clojure class loader");
            setupClojureClassLoader();
            Log.d(TAG, "Setting up Clojure vars");
            setupClojureVars();
            Log.d(TAG, "Initializing Clojure environment");
            initializeClojureEnvironment();
            Log.d(TAG, "Clojure environment setup complete");
            
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
        
        // Compile and evaluate in a background thread
        new Thread(() -> {
            Log.d(TAG, "Starting evaluation thread");
            try {
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
                    Object form = RT.var("clojure.core", "read-string").invoke(code);
                    Log.d(TAG, "Code parsed, starting evaluation");
                    Object result = RT.var("clojure.core", "eval").invoke(form);
                    Log.i(TAG, "Render result: " + result);
                } finally {
                    Log.d(TAG, "Cleaning up thread bindings");
                    Var.popThreadBindings();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error rendering code", e);
                Log.e(TAG, "Stack trace: ", e);
                runOnUiThread(() -> {
                    // Show error in UI
                    android.widget.TextView errorView = new android.widget.TextView(RenderActivity.this);
                    errorView.setText("Error: " + e.getMessage());
                    errorView.setTextColor(0xFFFF0000);  // Red color
                    contentLayout.addView(errorView);
                });
            }
        }).start();
    }

    private void showError(String message) {
        Log.e(TAG, "Showing error: " + message);
        runOnUiThread(() -> {
            android.widget.TextView errorView = new android.widget.TextView(this);
            errorView.setText(message);
            errorView.setTextColor(0xFFFF0000);  // Red color
            contentLayout.addView(errorView);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Var.popThreadBindings();
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up bindings", e);
        }
    }
} 