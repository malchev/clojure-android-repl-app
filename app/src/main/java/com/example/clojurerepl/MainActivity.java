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
        
        replInput = findViewById(R.id.repl_input);
        replOutput = findViewById(R.id.repl_output);
        
        try {
            // Disable spec checking before any Clojure initialization
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            
            setupClojureClassLoader();
            RT.init();
            
            // Only handle intent if it's not the initial launch
            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("code")) {
                handleIntent(intent);
            }
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("code")) {
            Log.w(TAG, "No code provided in intent");
            return;
        }

        // Clear UI state immediately
        runOnUiThread(() -> {
            replInput.setText("");
            replOutput.setText("");
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
            Log.w(TAG, "Empty code provided in intent");
            return;
        }

        // Show the code in the input field and launch RenderActivity in a single UI operation
        final String finalCode = code;
        runOnUiThread(() -> {
            try {
                replInput.setText(finalCode);
                Intent renderIntent = new Intent(MainActivity.this, RenderActivity.class);
                Log.d(TAG, "Creating intent for RenderActivity with code length: " + finalCode.length());
                renderIntent.putExtra("code", finalCode);
                Log.d(TAG, "Starting RenderActivity...");
                startActivity(renderIntent);
                Log.d(TAG, "RenderActivity started");
                replOutput.setText("Launching render activity...");
            } catch (Exception e) {
                Log.e(TAG, "Error launching RenderActivity", e);
                replOutput.setText("Error: " + e.getMessage());
            }
        });
    }
} 