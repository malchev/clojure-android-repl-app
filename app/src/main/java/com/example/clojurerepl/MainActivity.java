package com.example.clojurerepl;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import android.util.Log;
import clojure.lang.Symbol;
import clojure.lang.Var;
import clojure.lang.Compiler;
import java.lang.reflect.Field;
import clojure.lang.DynamicClassLoader;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private static final int REPL_PORT = 7888;
    private EditText replInput;
    private TextView replOutput;
    private StringBuilder outputHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        replInput = findViewById(R.id.replInput);
        replOutput = findViewById(R.id.replOutput);
        Button evalButton = findViewById(R.id.evalButton);
        outputHistory = new StringBuilder();

        try {
            Log.i("ClojureREPL", "Starting Clojure initialization");
            
            // Create and set up the Android delegate
            AndroidClassLoaderDelegate delegate = new AndroidClassLoaderDelegate(
                getApplicationContext(),
                getClass().getClassLoader()
            );
            DynamicClassLoader.setAndroidDelegate(delegate);
            
            // Initialize Clojure
            RT.init();
            Log.i("ClojureREPL", "RT initialized");

            // Push bindings
            Var.pushThreadBindings(RT.map());
            Log.i("ClojureREPL", "Thread bindings pushed");
            
            // Load and refer core
            RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.core"));
            Log.i("ClojureREPL", "Core referred");
            
            // Test evaluation
            Object result = RT.var("clojure.core", "eval").invoke(RT.readString("(+ 1 1)"));
            Log.i("ClojureREPL", "Test evaluation result: " + result);
            
        } catch (Exception e) {
            Log.e("ClojureREPL", "Error initializing Clojure", e);
            e.printStackTrace();
        }

        evalButton.setOnClickListener(v -> evaluateClojureCode());
    }

    private void evaluateClojureCode() {
        String code = replInput.getText().toString();
        Log.i(TAG, "Attempting to evaluate: " + code);
        try {
            // Push namespace binding
            Object userNs = RT.var("clojure.core", "find-ns").invoke(Symbol.intern("user"));
            Var.pushThreadBindings(RT.map(RT.var("clojure.core", "*ns*"), userNs));
            
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