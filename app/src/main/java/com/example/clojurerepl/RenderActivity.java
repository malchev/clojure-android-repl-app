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
import java.io.PushbackReader;
import java.io.StringReader;
import clojure.lang.LispReader;
import clojure.lang.Compiler;
import java.util.HashMap;
import java.util.Map;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import clojure.lang.LineNumberingPushbackReader;

public class RenderActivity extends AppCompatActivity {
    private static final String TAG = "ClojureRender";
    private static final boolean DEBUG_CACHE = true;
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
    private BytecodeCache bytecodeCache;
    public static Set<String> allCompiledClassNames = new HashSet<>();
    private String currentCode; // Store current code as a field
    private IFn readerEval;

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
        try {
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
                
                bytecodeCache = BytecodeCache.getInstance(this);
                
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
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in RenderActivity onCreate", t);
            // Show error to user
            Toast.makeText(this, "Fatal error: " + t.getMessage(), Toast.LENGTH_LONG).show();
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
            // Initialize Clojure API functions
            nsVar = RT.var("clojure.core", "*ns*");
            contextVar = RT.var("clojure.core", "*context*");
            contentLayoutVar = RT.var("clojure.core", "*content-layout*");
            
            // Initialize the readerEval function
            // This will use clojure.core/load-string which reads and evaluates code from a string
            readerEval = Clojure.var("clojure.core", "load-string");
            
            contextVar.setDynamic(true);
            contentLayoutVar.setDynamic(true);
            
            // Bind these vars permanently, using the UI-safe wrapper for contentLayout
            contextVar.bindRoot(this);
            contentLayoutVar.bindRoot(new UiSafeViewGroup(contentLayout));
            
            Log.d(TAG, "Clojure vars initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up Clojure vars", e);
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

    private boolean containsMainFunction(String code) {
        // Simple check for defn -main in the code
        return code != null && 
               (code.contains("(defn -main") || 
                code.contains("(defn\n-main") || 
                code.contains("(defn ^:export -main"));
    }

    private void renderCode(String code) {
        this.currentCode = code;
        Log.d(TAG, "Starting renderCode with code length: " + code.length());
        
        // Check if code contains a -main function
        boolean hasMainFunction = containsMainFunction(code);
        Log.d(TAG, "Code contains -main function: " + hasMainFunction);
        
        // Generate hash for the code
        String codeHash = bytecodeCache.getCodeHash(code);
        
        // Only use cache if the code has a -main function
        if (hasMainFunction) {
            // Check if we have a complete cached version 
            boolean hasCompleteCache = bytecodeCache.hasCompleteCache(codeHash);
            Log.d(TAG, "Code hash: " + codeHash + ", hasCompleteCache: " + hasCompleteCache);
            
            if (hasCompleteCache) {
                // Execute from cache (existing code for using the cache)
                ByteBuffer[] dexBuffers = bytecodeCache.loadDexCaches(codeHash);
                if (dexBuffers == null || dexBuffers.length == 0) {
                    Log.e(TAG, "Failed to load DEX from cache");
                    compileAndExecute(code, codeHash, hasMainFunction);
                    return;
                }
                
                // Get the entry point class name
                String entryClassName = bytecodeCache.loadEntryPointClass(codeHash);
                if (entryClassName == null) {
                    Log.e(TAG, "Failed to load entry point class name");
                    compileAndExecute(code, codeHash, hasMainFunction);
                    return;
                }
                
                Log.d(TAG, "About to execute DEX with entry point class: " + entryClassName);
                
                // IMPORTANT: Create runner with UI-safe content layout
                UiSafeViewGroup safeLayout = new UiSafeViewGroup(contentLayout);
                CompiledDexRunner runner = new CompiledDexRunner(this, safeLayout);
                
                // Execute on the main thread to avoid threading issues with UI
                runOnUiThread(() -> {
                    try {
                        long startTime = System.currentTimeMillis();
                        Object result = runner.execute(dexBuffers, entryClassName, codeHash);
                        long executionTime = System.currentTimeMillis() - startTime;
                        updateTimings("Direct execution", executionTime);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in direct execution", e);
                        showError("Direct execution error: " + e.getMessage());
                        // Fall back to compilation instead of running on a separate thread
                        new Thread(() -> compileAndExecute(code, codeHash, hasMainFunction)).start();
                    }
                });
                return; // Skip the compileAndExecute below since we're handling everything
            }
        } else {
            // No -main function, clear any existing cache for this code
            if (bytecodeCache.hasCompleteCache(codeHash)) {
                Log.d(TAG, "Clearing cache for code without -main function");
                bytecodeCache.clearCacheForHash(codeHash);
            }
        }
        
        // Either no -main function or no cache available, compile and execute
        compileAndExecute(code, codeHash, hasMainFunction);
    }

    /**
     * Compile and execute Clojure code, and save the compiled program for future use
     */
    private void compileAndExecute(String code, String codeHash, boolean hasMainFunction) {
        // Clear previous compiled classes
        allCompiledClassNames.clear();
        
        // Start capturing DEX
        AndroidClassLoaderDelegate.startCapturingDex();
        
        // Create caching class loader
        CachingClassLoader cachingLoader = new CachingClassLoader(
            clojureClassLoader, 
            new HashMap<>(),  // Empty cache since we're compiling
            true  // Collect all classes
        );
        
        // Start evaluation in a separate thread
        evaluationThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Starting compilation thread");
                    
                    // Set context class loader
                    Thread.currentThread().setContextClassLoader(cachingLoader);
                    Log.d(TAG, "ClassLoader set for compilation thread");
                    
                    // Setup thread bindings for Clojure
                    Var.pushThreadBindings(RT.map(
                        Var.intern(RT.CLOJURE_NS, Symbol.intern("*context*")), RenderActivity.this,
                        Var.intern(RT.CLOJURE_NS, Symbol.intern("*content-layout*")), 
                        new UiSafeViewGroup((LinearLayout)findViewById(R.id.content_layout))
                    ));
                    Log.d(TAG, "Thread bindings established");
                    
                    // Tracking for function capturing
                    IFn lastFunction = null;
                    String lastClassName = null;
                    
                    // Read and evaluate code
                    long startTime = System.currentTimeMillis();
                    LineNumberingPushbackReader pushbackReader = new LineNumberingPushbackReader(new StringReader(code));
                    Object lastResult = null;
                    
                    try {
                        while (!isDestroyed) {
                            Object form = LispReader.read(pushbackReader, false, EOF, false);
                            if (form == EOF) {
                                break;
                            }
                            
                            if (lastResult == null) {
                                Log.d(TAG, "Starting evaluation");
                            }
                            
                            lastResult = Compiler.eval(form);
                        }
                        
                        // If code has a -main function, try to call it directly
                        if (hasMainFunction) {
                            try {
                                Log.d(TAG, "Code has -main function, trying to invoke it directly");
                                // Try to get the -main function
                                Object mainFn = RT.var("clojure.core", "-main").deref();
                                if (mainFn instanceof IFn) {
                                    Log.d(TAG, "Found -main function, invoking it");
                                    // Call the -main function with no args
                                    lastResult = ((IFn)mainFn).invoke();
                                    Log.d(TAG, "Successfully called -main function, result: " + lastResult);
                                } else {
                                    Log.d(TAG, "-main var exists but is not a function");
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Error invoking -main function directly: " + e.getMessage());
                                // Continue with the existing evaluation result
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Clojure compilation", e);
                        lastResult = "Error: " + e.getMessage();
                        
                        final String errorMessage = e.getMessage();
                        runOnUiThread(() -> showError(errorMessage));
                    }
                    
                    // Get the result and show it
                    final Object result = lastResult;
                    Log.i(TAG, "Compilation result: " + result);
                    
                    long executionTime = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Code compiled and executed in " + executionTime + "ms");
                    
                    runOnUiThread(() -> updateTimings("Code compilation", executionTime));
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in compilation thread", e);
                    final String errorMessage = e.getMessage();
                    runOnUiThread(() -> showError("Thread error: " + errorMessage));
                } finally {
                    Log.d(TAG, "Cleaning up thread bindings");
                    Var.popThreadBindings();
                    
                    // Save DEX file for class loading, but only if code has a -main function
                    if (hasMainFunction) {
                        List<byte[]> dexFiles = AndroidClassLoaderDelegate.getAllCapturedDex();
                        if (dexFiles != null && !dexFiles.isEmpty()) {
                            Log.d(TAG, "Saving " + dexFiles.size() + " captured DEX files to cache");
                            bytecodeCache.saveMultipleDexCaches(codeHash, dexFiles);
                            
                            // Try to find -main function
                            try {
                                // Find the actual program entry point function
                                Object mainFn = RT.var("clojure.core", "-main").deref();
                                if (mainFn instanceof IFn) {
                                    String className = mainFn.getClass().getName();
                                    
                                    // Save the entry point class name
                                    bytecodeCache.saveEntryPointClass(codeHash, className);
                                    
                                    // Now that we have the entry point class name, also save dependency info
                                    Set<String> allClasses = new HashSet<>(allCompiledClassNames);
                                    bytecodeCache.saveClassDependencies(codeHash, className, allClasses);
                                    
                                    Log.d(TAG, "Saved DEX and entry point class: " + className);
                                } else {
                                    // Fall back to generic entry point detection
                                    bytecodeCache.saveEntryPointClass(codeHash, findLastGeneratedClassName());
                                    Log.w(TAG, "-main var exists but is not a function");
                                }
                            } catch (Exception e) {
                                // If we can't find -main, use fallback entry point detection
                                String entryPointClassName = findLastGeneratedClassName();
                                if (entryPointClassName != null) {
                                    bytecodeCache.saveEntryPointClass(codeHash, entryPointClassName);
                                    Log.d(TAG, "Saved DEX and fallback entry point class: " + entryPointClassName);
                                } else {
                                    Log.w(TAG, "No suitable entry point class found");
                                }
                            }
                        } else {
                            Log.e(TAG, "No DEX files captured. Caching will not work for this code.");
                        }
                    } else {
                        // If no -main function, don't save the cache
                        AndroidClassLoaderDelegate.cleanup();
                        Log.d(TAG, "Not caching DEX for code without -main function");
                    }
                }
            }
        });
        
        evaluationThread.start();
    }

    private void showError(String message) {
        Log.e(TAG, "Showing error: " + message);
        runOnUiThread(() -> {
            LinearLayout errorContainer = new LinearLayout(this);
            errorContainer.setOrientation(LinearLayout.VERTICAL);
            errorContainer.setBackgroundColor(Color.parseColor("#FFEBEE")); // Light Red
            errorContainer.setPadding(16, 16, 16, 16);

            // Create error header
            TextView errorHeaderView = new TextView(this);
            errorHeaderView.setText("Error:");
            errorHeaderView.setTextColor(Color.parseColor("#D32F2F")); // Material Red
            errorHeaderView.setTypeface(null, Typeface.BOLD);
            errorHeaderView.setTextSize(16);
            errorContainer.addView(errorHeaderView);

            // Create main error message
            TextView errorMsgView = new TextView(this);

            // Parse the message to separate main message, location, and source code
            String mainMessage = message;
            String locationInfo = "";
            String sourceCode = "";

            // Extract location information
            int atIndex = message.indexOf(" at (");
            if (atIndex > 0) {
                mainMessage = message.substring(0, atIndex);
                int endLocIndex = message.indexOf(")", atIndex);
                if (endLocIndex > 0) {
                    locationInfo = message.substring(atIndex, endLocIndex + 1);

                    // Check if there's source code info after the location
                    int sourceIndex = message.indexOf("\nProblematic code:", endLocIndex);
                    if (sourceIndex > 0) {
                        sourceCode = message.substring(sourceIndex + 1);
                    }
                }
            }

            // Set main message
            errorMsgView.setText(mainMessage);
            errorMsgView.setTextColor(Color.parseColor("#D32F2F")); // Material Red
            errorMsgView.setPadding(0, 8, 0, 8);
            errorContainer.addView(errorMsgView);

            // Add location information if present
            if (!locationInfo.isEmpty()) {
                TextView locationView = new TextView(this);
                locationView.setText(locationInfo);
                locationView.setTypeface(Typeface.MONOSPACE);
                locationView.setTextColor(Color.parseColor("#1976D2")); // Material Blue
                locationView.setPadding(8, 4, 0, 4);
                errorContainer.addView(locationView);
            }

            // Add source code if present
            if (!sourceCode.isEmpty()) {
                TextView sourceView = new TextView(this);
                sourceView.setText(sourceCode);
                sourceView.setTypeface(Typeface.MONOSPACE);
                sourceView.setTextSize(14);
                sourceView.setBackgroundColor(Color.parseColor("#F5F5F5")); // Light Gray
                sourceView.setPadding(16, 8, 16, 8);
                errorContainer.addView(sourceView);
            }

            contentLayout.addView(errorContainer);
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

    // Define EOF object for detecting end of input
    private static final Object EOF = new Object();

    // Add a custom ClassLoader that can use our cached bytecode
    public static class CachingClassLoader extends ClassLoader {
        private final DynamicClassLoader parent;
        private final Map<String, byte[]> cachedClasses;
        
        public CachingClassLoader(DynamicClassLoader parent, Map<String, byte[]> cachedClasses, boolean unused) {
            super(parent);
            this.parent = parent;
            this.cachedClasses = cachedClasses;
        }
        
        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // First check our cache
            byte[] cachedBytes = cachedClasses.get(name);
            if (cachedBytes != null) {
                try {
                    Log.d(TAG, "Loading class from cache: " + name);
                    Class<?> cls = defineClass(name, cachedBytes, 0, cachedBytes.length);
                    if (resolve) {
                        resolveClass(cls);
                    }
                    return cls;
                } catch (Exception e) {
                    Log.e(TAG, "Error loading class from cache: " + name, e);
                    // Fall through to normal loading
                }
            }
            
            // Normal class loading
            return super.loadClass(name, resolve);
        }
        
        // Simplified findClass to avoid circular call
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            throw new ClassNotFoundException(name);
        }
        
        public static void recordClassName(String className) {
            if (className.startsWith("clojure.core$")) {
                Log.d(TAG, "Recording class name: " + className);
                // Add to our static collection of class names
                allCompiledClassNames.add(className);
            }
        }
    }

    private String findLastGeneratedClassName() {
        // First priority: Find a class with "-main" in the name
        for (String className : allCompiledClassNames) {
            if (className.contains("-main")) {
                Log.d(TAG, "Found -main entry point class: " + className);
                return className;
            }
        }
        
        // Second priority: Look for classes with the pattern clojure.core$eval###
        String evalClassName = null;
        int highestEvalNumber = -1;
        
        for (String className : allCompiledClassNames) {
            // Look for eval classes
            if (className.startsWith("clojure.core$eval")) {
                try {
                    // Extract the number part
                    String numberPart = className.substring("clojure.core$eval".length());
                    int evalNumber = Integer.parseInt(numberPart);
                    
                    // Keep track of the highest numbered eval class
                    if (evalNumber > highestEvalNumber) {
                        highestEvalNumber = evalNumber;
                        evalClassName = className;
                    }
                } catch (NumberFormatException e) {
                    // If the suffix isn't a clean number, just check if this is the last one
                    if (evalClassName == null) {
                        evalClassName = className;
                    }
                }
            }
        }
        
        // If we found an eval class, use it
        if (evalClassName != null) {
            Log.d(TAG, "Found entry point class: " + evalClassName);
            return evalClassName;
        }
        
        // If still not found, just use the last compiled class as a fallback
        if (!allCompiledClassNames.isEmpty()) {
            // Get the last entry in the map
            String lastClass = null;
            for (String className : allCompiledClassNames) {
                lastClass = className;
            }
            Log.d(TAG, "Using last compiled class as entry point: " + lastClass);
            return lastClass;
        }
        
        Log.w(TAG, "No suitable entry point class found");
        return null;
    }

    /**
     * Format an exception with line and column information
     * @param exception The exception to format
     * @param sourceCode The source code being evaluated
     * @param reader The reader used for compilation (may contain line/column info)
     * @return A formatted error message
     */
    private String formatErrorWithLineInfo(Throwable exception, String sourceCode, LineNumberingPushbackReader reader) {
        try {
            // Get the root cause of the exception
            Throwable rootCause = getRootCause(exception);
            String message = rootCause.getMessage();

            // Try to extract line and column from compiler exception first
            int[] lineAndColumn = extractLineColumnFromException(rootCause);
            int line = (lineAndColumn != null && lineAndColumn[0] > 0) ? lineAndColumn[0] : reader.getLineNumber();
            int column = (lineAndColumn != null && lineAndColumn[1] > 0) ? lineAndColumn[1] : reader.getColumnNumber();

            // Check if the message already has line/column info
            if (message != null && message.contains("at (")) {
                // Try to extract the actual line/column from the message if it has zeros
                if (message.contains("at (0:0)")) {
                    String updatedMessage = message.replace("at (0:0)", "at (" + line + ":" + column + ")");
                    Log.d(TAG, "Updated error message with line/column: " + updatedMessage);

                    // Try to include the problematic line of code
                    if (line > 0 && sourceCode != null) {
                        String[] lines = sourceCode.split("\n");
                        if (line <= lines.length) {
                            String sourceLine = lines[line - 1].trim();
                            updatedMessage += "\nProblematic code: " + sourceLine;
                        }
                    }

                    return updatedMessage;
                }
                return message; // Already has non-zero line/column info
            }

            // No line/column info at all, add it
            StringBuilder enhancedMessage = new StringBuilder(message != null ? message : rootCause.toString());
            enhancedMessage.append(" at (").append(line).append(":").append(column).append(")");

            // Try to include the problematic line of code
            if (line > 0 && sourceCode != null) {
                String[] lines = sourceCode.split("\n");
                if (line <= lines.length) {
                    String sourceLine = lines[line - 1].trim();
                    enhancedMessage.append("\nProblematic code: ").append(sourceLine);
                }
            }

            return enhancedMessage.toString();
        } catch (Exception e) {
            // If anything goes wrong in our error formatting, fall back to the original message
            Log.e(TAG, "Error formatting exception", e);
            return exception.getMessage();
        }
    }

    /**
     * Get the root cause of an exception
     */
    private Throwable getRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Extract line and column information from a Clojure compiler exception
     * @param ex The exception to extract information from
     * @return int array with [line, column] or null if not found
     */
    private int[] extractLineColumnFromException(Throwable ex) {
        try {
            // Check if it's a CompilerException which might have line info
            if (ex.getClass().getName().equals("clojure.lang.Compiler$CompilerException")) {
                // Try to access line and column fields using reflection
                try {
                    // Get the line field
                    java.lang.reflect.Field lineField = ex.getClass().getDeclaredField("line");
                    lineField.setAccessible(true);
                    int line = (Integer) lineField.get(ex);

                    // Get the column field
                    java.lang.reflect.Field columnField = ex.getClass().getDeclaredField("column");
                    columnField.setAccessible(true);
                    int column = (Integer) columnField.get(ex);

                    Log.d(TAG, "Extracted from CompilerException - line: " + line + ", column: " + column);
                    return new int[] {line, column};
                } catch (Exception e) {
                    Log.w(TAG, "Failed to extract line/column via reflection", e);
                }
            }

            // Check for "at (line:column)" pattern in the message
            String message = ex.getMessage();
            if (message != null) {
                // Look for patterns like "at (123:45)" or "at line 123, column 45"
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("at \\((\\d+):(\\d+)\\)");
                java.util.regex.Matcher matcher = pattern.matcher(message);

                if (matcher.find()) {
                    int line = Integer.parseInt(matcher.group(1));
                    int column = Integer.parseInt(matcher.group(2));
                    Log.d(TAG, "Extracted from message pattern - line: " + line + ", column: " + column);
                    return new int[] {line, column};
                }

                // Try alternative pattern
                pattern = java.util.regex.Pattern.compile("at line (\\d+), column (\\d+)");
                matcher = pattern.matcher(message);

                if (matcher.find()) {
                    int line = Integer.parseInt(matcher.group(1));
                    int column = Integer.parseInt(matcher.group(2));
                    Log.d(TAG, "Extracted from alt message pattern - line: " + line + ", column: " + column);
                    return new int[] {line, column};
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting line/column info", e);
            return null;
        }
    }
}