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
import android.graphics.Typeface;
import android.view.Gravity;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private EditText replInput;
    private TextView replOutput;
    private TextView statsView;
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
        
        // Create and add stats view
        statsView = new TextView(this);
        statsView.setTextSize(14);
        statsView.setPadding(16, 16, 16, 16);
        statsView.setBackgroundColor(Color.parseColor("#F5F5F5"));
        statsView.setTextColor(Color.parseColor("#263238"));  // Dark gray text
        statsView.setTypeface(Typeface.MONOSPACE);
        
        LinearLayout root = findViewById(R.id.root_layout);
        root.addView(statsView, 0);  // Add at the top
        
        // Set up launch button
        Button launchButton = findViewById(R.id.launch_button);
        launchButton.setOnClickListener(v -> launchRenderActivity());
        
        updateStats("Initializing...", null, null);
        
        try {
            // Disable spec checking before any Clojure initialization
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            
            long startTime = System.currentTimeMillis();
            setupClojureClassLoader();
            RT.init();
            long initTime = System.currentTimeMillis() - startTime;
            
            updateStats("Ready", 0, initTime);
            
            // Only handle intent if it's not the initial launch
            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("code")) {
                handleIntent(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure", e);
            replOutput.setText("Error initializing Clojure: " + e.getMessage());
            updateStats("Error", null, null);
        }
    }

    private void updateStats(String status, Integer codeLines, Long timeMs) {
        runOnUiThread(() -> {
            StringBuilder stats = new StringBuilder();
            stats.append("Status: ").append(status).append("\n");
            
            if (codeLines != null) {
                stats.append("Code size: ").append(codeLines).append(" lines\n");
            }
            
            if (timeMs != null) {
                stats.append("Time: ");
                if (timeMs < 1000) {
                    stats.append(timeMs).append("ms");
                } else {
                    stats.append(String.format("%.1fs", timeMs / 1000.0));
                }
            }
            
            SpannableString spannableStats = new SpannableString(stats.toString());
            
            // Style the labels
            String text = stats.toString();
            styleLabel(spannableStats, text, "Status:");
            styleLabel(spannableStats, text, "Code size:");
            styleLabel(spannableStats, text, "Time:");
            
            statsView.setText(spannableStats);
        });
    }
    
    private void styleLabel(SpannableString spannableString, String fullText, String label) {
        int start = fullText.indexOf(label);
        if (start >= 0) {
            spannableString.setSpan(new StyleSpan(Typeface.BOLD), start, start + label.length(), 0);
            spannableString.setSpan(new ForegroundColorSpan(Color.parseColor("#1976D2")), start, start + label.length(), 0);  // Darker blue
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

    private void launchRenderActivity() {
        String code = replInput.getText().toString();
        if (code.trim().isEmpty()) {
            replOutput.setText("Please enter some code first");
            updateStats("No code", 0, 0L);
            return;
        }

        final int lineCount = code.split("\n").length;
        final long startTime = System.currentTimeMillis();
        
        updateStats("Compiling...", lineCount, null);

        try {
            Intent renderIntent = new Intent(MainActivity.this, RenderActivity.class);
            Log.d(TAG, "Creating intent for RenderActivity with code length: " + code.length());
            renderIntent.putExtra("code", code);
            Log.d(TAG, "Starting RenderActivity...");
            startActivity(renderIntent);
            Log.d(TAG, "RenderActivity started");
            replOutput.setText("Launching render activity...");
            
            // Update stats with final timing
            long totalTime = System.currentTimeMillis() - startTime;
            updateStats("Compiled successfully", lineCount, totalTime);
        } catch (Exception e) {
            Log.e(TAG, "Error launching RenderActivity", e);
            replOutput.setText("Error: " + e.getMessage());
            updateStats("Compilation error", lineCount, null);
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
            updateStats("No code provided", 0, 0L);
            return;
        }

        String code = null;
        String encoding = intent.getStringExtra("encoding");

        if ("base64".equals(encoding)) {
            String base64Code = intent.getStringExtra("code");
            if (base64Code != null) {
                try {
                    code = new String(Base64.decode(base64Code, Base64.DEFAULT), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Failed to decode base64 content", e);
                    updateStats("Error decoding", null, null);
                    return;
                }
            }
        } else {
            code = intent.getStringExtra("code");
        }

        if (code == null || code.trim().isEmpty()) {
            Log.w(TAG, "Empty code provided in intent");
            updateStats("Empty code", 0, 0L);
            return;
        }

        // Show the code in the input field
        final String finalCode = code;
        runOnUiThread(() -> replInput.setText(finalCode));
    }
} 