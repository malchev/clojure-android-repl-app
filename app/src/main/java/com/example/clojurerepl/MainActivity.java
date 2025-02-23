package com.example.clojurerepl;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private EditText replInput;
    private TextView replOutput;
    private StringBuilder outputHistory;
    private LinearLayout rootLayout;
    private Var activityVar;
    private Var rootLayoutVar;
    private Var nsVar;

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
        rootLayout.addView(replLayout);
        
        setContentView(rootLayout);

        replInput = findViewById(R.id.replInput);
        replOutput = findViewById(R.id.replOutput);
        Button evalButton = findViewById(R.id.evalButton);
        outputHistory = new StringBuilder();

        try {
            Log.i(TAG, "Starting Clojure initialization");
            
            // Disable all spec checking
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.skip", "true");
            
            // Create and set up the Android delegate
            AndroidClassLoaderDelegate delegate = new AndroidClassLoaderDelegate(
                getApplicationContext(),
                getClass().getClassLoader()
            );
            DynamicClassLoader.setAndroidDelegate(delegate);
            
            // Initialize Clojure
            RT.init();
            
            // Get important vars
            nsVar = RT.var("clojure.core", "*ns*");
            activityVar = RT.var("clojure.core", "*activity*");
            rootLayoutVar = RT.var("clojure.core", "*root-layout*");
            
            // Set up dynamic vars
            activityVar.setDynamic(true);
            rootLayoutVar.setDynamic(true);
            
            // Initialize the user namespace
            Symbol userSym = Symbol.intern("user");
            Object userNS = RT.var("clojure.core", "create-ns").invoke(userSym);
            
            // Set up initial bindings
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                activityVar, this,
                rootLayoutVar, rootLayout
            ));
            
            // Require core into user namespace
            RT.var("clojure.core", "refer").invoke(Symbol.intern("clojure.core"));
            
            // Make the bindings permanent
            activityVar.bindRoot(this);
            rootLayoutVar.bindRoot(rootLayout);
            
            // Pop the temporary bindings
            Var.popThreadBindings();
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure", e);
            e.printStackTrace();
        }

        evalButton.setOnClickListener(v -> evaluateClojureCode());
        
        // Handle intent if app was started with one
        handleIntent(getIntent());
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
                String filePath = intent.getStringExtra("clojure-code-file");
                try {
                    // Read the base64 encoded content from the file
                    Process process = Runtime.getRuntime().exec("cat " + filePath);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    StringBuilder base64Content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        base64Content.append(line);
                    }
                    code = new String(Base64.decode(base64Content.toString(), Base64.DEFAULT));
                    Log.i(TAG, "Decoded code from file: " + code);
                } catch (Exception e) {
                    Log.e(TAG, "Error reading code file", e);
                }
            } else if (intent.hasExtra("clojure-code")) {
                code = intent.getStringExtra("clojure-code");
            }
            
            if (code != null) {
                // Show the code in the input
                replInput.setText(code);
                
                try {
                    // Get the user namespace
                    Object userNS = RT.var("clojure.core", "find-ns").invoke(Symbol.intern("user"));
                    
                    // Push thread bindings
                    Var.pushThreadBindings(RT.map(
                        nsVar, userNS,
                        activityVar, this,
                        rootLayoutVar, rootLayout
                    ));
                    
                    try {
                        // Read and eval directly
                        Object form = RT.var("clojure.core", "read-string").invoke(code);
                        Object result = RT.var("clojure.core", "eval").invoke(form);
                        String resultStr = String.valueOf(result);
                        
                        Log.i(TAG, "Evaluation result: " + resultStr);
                        outputHistory.append("> ").append(code).append("\n");
                        outputHistory.append(resultStr).append("\n\n");
                        replOutput.setText(outputHistory.toString());
                        
                    } finally {
                        Var.popThreadBindings();
                    }
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    Throwable cause = e.getCause();
                    while (cause != null) {
                        errorMsg += "\nCaused by: " + cause.getMessage();
                        cause = cause.getCause();
                    }
                    Log.e(TAG, "Error evaluating code: " + errorMsg, e);
                    outputHistory.append("Error: ").append(errorMsg).append("\n\n");
                    replOutput.setText(outputHistory.toString());
                }
            }
        }
    }

    private void evaluateClojureCode() {
        String code = replInput.getText().toString();
        Log.i(TAG, "Attempting to evaluate: " + code);
        try {
            // Get the user namespace
            Object userNS = RT.var("clojure.core", "find-ns").invoke(Symbol.intern("user"));
            
            // Push thread bindings
            Var.pushThreadBindings(RT.map(
                nsVar, userNS,
                activityVar, this,
                rootLayoutVar, rootLayout
            ));
            
            try {
                // Read and eval directly
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
            String errorMsg = e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                errorMsg += "\nCaused by: " + cause.getMessage();
                cause = cause.getCause();
            }
            
            Log.e(TAG, "Evaluation error: " + errorMsg, e);
            outputHistory.append("> ").append(code).append("\n");
            outputHistory.append("Error: ").append(errorMsg).append("\n\n");
            replOutput.setText(outputHistory.toString());
            e.printStackTrace();
        }
    }
} 