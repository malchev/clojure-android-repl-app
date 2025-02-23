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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private EditText replInput;
    private TextView replOutput;
    private StringBuilder outputHistory;
    private LinearLayout rootLayout;
    private LinearLayout contentLayout;
    private Var activityVar;
    private Var rootLayoutVar;
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
        
        // Create a vertical LinearLayout as the root view
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        
        // Create the REPL layout
        LinearLayout replLayout = (LinearLayout) getLayoutInflater().inflate(R.layout.activity_main, null);
        replLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        // Add REPL layout at the top
        rootLayout.addView(replLayout, 0);
        
        // Create a container for dynamic content
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        
        // Add content layout below REPL
        rootLayout.addView(contentLayout, 1);
        
        setContentView(rootLayout);

        replInput = findViewById(R.id.replInput);
        replOutput = findViewById(R.id.replOutput);
        Button evalButton = findViewById(R.id.evalButton);
        outputHistory = new StringBuilder();

        try {
            Log.i(TAG, "Starting Clojure initialization");
            
            // Set up class loading
            setupClojureClassLoader();
            
            // Initialize Clojure runtime
            RT.init();
            
            // Initialize vars
            setupClojureVars();
            
            // Initialize namespaces and specs
            initializeClojureEnvironment();
            
            // Bind the content layout instead of root layout
            rootLayoutVar.bindRoot(contentLayout);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure", e);
            e.printStackTrace();
            outputHistory.append("Error initializing Clojure: ").append(e.getMessage()).append("\n\n");
            replOutput.setText(outputHistory.toString());
        }

        evalButton.setOnClickListener(v -> evaluateClojureCode());
        handleIntent(getIntent());
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
            DynamicClassLoader.setAndroidDelegate(delegate);
            
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
            activityVar = RT.var("clojure.core", "*activity*");
            rootLayoutVar = RT.var("clojure.core", "*root-layout*");
            
            activityVar.setDynamic(true);
            rootLayoutVar.setDynamic(true);
            
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
            
            // Create log handler
            ClojureLogHandler logHandler = new ClojureLogHandler();
            
            // Set up bindings
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                activityVar, this,
                rootLayoutVar, rootLayout,
                RT.var("clojure.core", "*out*"), new java.io.PrintWriter(new java.io.StringWriter()) {
                    @Override
                    public void println(String x) {
                        logHandler.log("OUT", x);
                    }
                },
                RT.var("clojure.core", "*err*"), new java.io.PrintWriter(new java.io.StringWriter()) {
                    @Override
                    public void println(String x) {
                        logHandler.log("ERR", x);
                    }
                }
            ));
            
            try {
                // Load and initialize spec
                RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.spec.alpha"));
                
                // Make spec checking optional but available
                RT.var("clojure.spec.alpha", "check-asserts").invoke(false);
                
                // Require core into user namespace
                RT.var("clojure.core", "refer").invoke(Symbol.intern("clojure.core"));
                
                // Make activity and root layout permanently available
                activityVar.bindRoot(this);
                
            } finally {
                Var.popThreadBindings();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure environment", e);
            throw new RuntimeException(e);
        }
    }

    private void evaluateClojureCode() {
        String code = replInput.getText().toString();
        Log.i(TAG, "Attempting to evaluate: " + code);
        try {
            // Get the user namespace
            Object userNS = RT.var("clojure.core", "find-ns").invoke(Symbol.intern("user"));
            
            // Push thread bindings with contentLayout
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                activityVar, this,
                rootLayoutVar, contentLayout
            ));
            
            try {
                // Clear previous content before evaluation
                runOnUiThread(() -> contentLayout.removeAllViews());
                
                // Read and eval with the custom class loader
                Thread.currentThread().setContextClassLoader(clojureClassLoader);
                Object form = RT.var("clojure.core", "read-string").invoke(code);
                Object result = RT.var("clojure.core", "eval").invoke(form);
                String resultStr = String.valueOf(result);
                
                Log.i(TAG, "Evaluation result: " + resultStr);
                outputHistory.append("> ").append(code).append("\n");
                outputHistory.append(resultStr).append("\n\n");
                replOutput.setText(outputHistory.toString());
                replInput.setText("");
                
            } finally {
                Var.popThreadBindings();
            }
        } catch (Exception e) {
            handleEvaluationError(e, code);
        }
    }

    private void handleEvaluationError(Exception e, String code) {
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append(e.getMessage()).append("\n");
        
        // Get the full stack trace
        for (StackTraceElement element : e.getStackTrace()) {
            errorMsg.append("  at ").append(element.toString()).append("\n");
        }
        
        // Include cause if present
        Throwable cause = e.getCause();
        while (cause != null) {
            errorMsg.append("Caused by: ").append(cause.getMessage()).append("\n");
            for (StackTraceElement element : cause.getStackTrace()) {
                errorMsg.append("  at ").append(element.toString()).append("\n");
            }
            cause = cause.getCause();
        }
        
        Log.e(TAG, "Evaluation error: " + errorMsg);
        outputHistory.append("> ").append(code).append("\n");
        outputHistory.append("Error: ").append(errorMsg).append("\n\n");
        replOutput.setText(outputHistory.toString());
    }

    // Create a custom LogHandler to capture logs
    private class ClojureLogHandler {
        public void log(String level, String message) {
            runOnUiThread(() -> {
                outputHistory.append("[").append(level).append("] ").append(message).append("\n");
                replOutput.setText(outputHistory.toString());
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    
    private void handleIntent(Intent intent) {
        if (intent != null) {
            String code = null;
            if (intent.hasExtra("clojure-code-file")) {
                code = readCodeFromFile(intent.getStringExtra("clojure-code-file"));
            } else if (intent.hasExtra("clojure-code")) {
                code = intent.getStringExtra("clojure-code");
            }
            
            if (code != null) {
                replInput.setText(code);
                evaluateClojureCode();
            }
        }
    }

    private String readCodeFromFile(String filePath) {
        try {
            Process process = Runtime.getRuntime().exec("cat " + filePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder base64Content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                base64Content.append(line);
            }
            String code = new String(Base64.decode(base64Content.toString(), Base64.DEFAULT));
            Log.i(TAG, "Decoded code from file: " + code);
            return code;
        } catch (Exception e) {
            Log.e(TAG, "Error reading code file", e);
            return null;
        }
    }
} 