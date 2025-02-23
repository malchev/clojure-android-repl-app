package com.example.clojurerepl;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import androidx.multidex.MultiDex;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import android.util.Log;
import android.util.Base64;
import clojure.lang.Symbol;
import clojure.lang.Var;
import clojure.lang.Compiler;
import java.lang.reflect.Field;
import clojure.lang.DynamicClassLoader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import dalvik.system.DexFile;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import android.content.Context;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private EditText replInput;
    private TextView replOutput;
    private LinearLayout contentLayout;
    private Var contextVar;
    private Var contentLayoutVar;
    private Var nsVar;
    private DynamicClassLoader clojureClassLoader;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        contentLayout = findViewById(R.id.content_layout);
        replInput = findViewById(R.id.repl_input);
        replOutput = findViewById(R.id.repl_output);
        
        try {
            // Disable spec checking before any Clojure initialization
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            
            setupClojureClassLoader();
            RT.init();
            setupClojureVars();
            initializeClojureEnvironment();
            
            // Handle intent that started the activity
            handleIntent(getIntent());
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure", e);
            replOutput.setText("Error initializing Clojure: " + e.getMessage());
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
            
            // Bind these vars permanently
            contextVar.bindRoot(this);
            contentLayoutVar.bindRoot(contentLayout);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up vars", e);
            throw new RuntimeException(e);
        }
    }

    private void initializeClojureEnvironment() {
        try {
            // Create and switch to user namespace
            Symbol userSym = Symbol.intern("user");
            Object userNS = RT.var("clojure.core", "create-ns").invoke(userSym);
            
            // Set up bindings
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                contextVar, this,
                contentLayoutVar, contentLayout
            ));
            
            try {
                // Require core into user namespace
                RT.var("clojure.core", "refer").invoke(Symbol.intern("clojure.core"));
                
                // Don't require spec at all
                // RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.spec.alpha"));
                // RT.var("clojure.spec.alpha", "check-asserts").invoke(false);
                
            } finally {
                Var.popThreadBindings();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure environment", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        // Clear UI state immediately
        runOnUiThread(() -> {
            replInput.setText("");
            replOutput.setText("");
            contentLayout.removeAllViews();
        });

        String code = null;
        String encoding = intent.getStringExtra("encoding");

        if ("base64".equals(encoding)) {
            String base64Code = intent.getStringExtra("code");
            if (base64Code != null) {
                try {
                    code = new String(Base64.decode(base64Code, Base64.DEFAULT), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Failed to decode base64 content", e);
                    return;
                }
            }
        } else {
            code = intent.getStringExtra("code");
        }

        if (code == null || code.trim().isEmpty()) {
            Log.w(TAG, "No code provided in intent");
            return;
        }

        // Show the code in the input field
        final String finalCode = code;
        runOnUiThread(() -> replInput.setText(finalCode));

        try {
            // Get the user namespace
            Object userNS = RT.var("clojure.core", "find-ns").invoke(Symbol.intern("user"));
            
            // Push thread bindings
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                contextVar, this,
                contentLayoutVar, contentLayout
            ));
            
            try {
                // Set the context class loader before reading and evaluating
                Thread.currentThread().setContextClassLoader(clojureClassLoader);
                
                // Evaluate the code
                Log.d(TAG, "Evaluating code: " + code);
                Object form = RT.var("clojure.core", "read-string").invoke(code);
                
                // Ensure classloader is still set before eval
                Thread.currentThread().setContextClassLoader(clojureClassLoader);
                Object result = RT.var("clojure.core", "eval").invoke(form);
                
                // Show the result
                String resultStr = String.valueOf(result);
                Log.i(TAG, "Evaluation result: " + resultStr);
                runOnUiThread(() -> replOutput.setText(resultStr));
                
            } finally {
                Var.popThreadBindings();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating Clojure code", e);
            final String errorMsg = "Error: " + e.getMessage();
            runOnUiThread(() -> replOutput.setText(errorMsg));
            e.printStackTrace();
        }
    }
} 