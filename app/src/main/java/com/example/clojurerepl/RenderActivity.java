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
import android.app.ActivityManager;
import android.content.Context;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.File;

public class RenderActivity extends AppCompatActivity {
    private static final String TAG = "ClojureRender";
    private static final String CLOJURE_APP_CACHE_DIR = "clojure_app_cache";
    // Define EOF object for detecting end of input
    private static final Object EOF = new Object();

    private LinearLayout contentLayout;
    private Var contextVar;
    private Var contentLayoutVar;
    private Var cacheDirVar;
    private Var nsVar;
    private DynamicClassLoader clojureClassLoader;
    private long activityStartTime;
    private TextView timingView;
    private StringBuilder timingData = new StringBuilder();
    private volatile boolean isDestroyed = false;
    private File appCacheDir;
    private String code;
    private String codeHash;
    private boolean shouldKillOnDestroy = false;

    private class UiSafeViewGroup extends LinearLayout {
        private final LinearLayout layoutDelegate;

        public UiSafeViewGroup(LinearLayout layoutDelegate) {
            super(layoutDelegate.getContext());
            this.layoutDelegate = layoutDelegate;
        }

        @Override
        public void addView(android.view.View child) {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                layoutDelegate.addView(child);
            } else {
                runOnUiThread(() -> layoutDelegate.addView(child));
            }
        }

        @Override
        public void addView(android.view.View child, android.view.ViewGroup.LayoutParams params) {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                layoutDelegate.addView(child, params);
            } else {
                runOnUiThread(() -> layoutDelegate.addView(child, params));
            }
        }

        @Override
        public void removeAllViews() {
            if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
                layoutDelegate.removeAllViews();
            } else {
                runOnUiThread(() -> layoutDelegate.removeAllViews());
            }
        }
    }

    public static String getCodeHash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error generating hash", e);
            return String.valueOf(code.hashCode());
        }
    }

    public static File getAppCacheDir(Context context, String codeHash) {
        File baseDir = new File(context.getCacheDir(), CLOJURE_APP_CACHE_DIR);
        File appCacheDir = new File(baseDir, codeHash);
        if (!appCacheDir.exists()) {
            if (!appCacheDir.mkdirs()) {
                Log.e(TAG, "Failed to create app cache directory");
                throw new RuntimeException("Failed to create app cache directory");
            }
        }
        Log.d(TAG, "App cache directory created: " + appCacheDir.getAbsolutePath());
        return appCacheDir;
    }

    public static void clearProgramData(Context context, String codeHash) {
        try {
            File cacheDir = getAppCacheDir(context, codeHash);

            // Delete the directory and its contents
            if (cacheDir.exists()) {
                deleteRecursive(cacheDir);
                Log.d(TAG, "Program data cleared for hash: " + codeHash);
            } else {
                Log.d(TAG, "No data to clear for hash: " + codeHash);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing program data", e);
            throw new RuntimeException("Error clearing program data", e);
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    public File getAppCacheDir() {
        if (appCacheDir == null) {
            appCacheDir = getAppCacheDir(this, codeHash);
        }
        return appCacheDir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            activityStartTime = System.currentTimeMillis();
            Log.d(TAG, "RenderActivity onCreate started in process: " + android.os.Process.myPid());
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
                // Get the code from the intent
                Intent intent = getIntent();
                if (intent == null) {
                    Log.e(TAG, "No intent provided");
                    showError("No intent provided");
                    return;
                }

                // Store code in class member instead of local variable
                code = intent.getStringExtra("code");
                Log.d(TAG, "Received intent with code: " + (code != null ? "length=" + code.length() : "null"));

                if (code != null && !code.trim().isEmpty()) {
                    // Calculate and store codeHash as class member
                    codeHash = getCodeHash(code);

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

                    Log.d(TAG, "About to render code");
                    renderCode();
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

        // Ensure final timing data is sent back
        Intent resultIntent = new Intent();
        resultIntent.putExtra("timings", timingData.toString());
        setResult(RESULT_OK, resultIntent);

        // Instead of killing immediately, we'll set a flag and let the normal lifecycle
        // progress
        shouldKillOnDestroy = true;

        // Call super to continue normal back button behavior
        super.onBackPressed();
    }

    private void setupClojureClassLoader() {
        try {
            // Create a custom class loader that can handle dynamic classes
            clojureClassLoader = new DynamicClassLoader(getClass().getClassLoader());

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
            cacheDirVar = RT.var("clojure.core", "*cache-dir*");

            contextVar.setDynamic(true);
            contentLayoutVar.setDynamic(true);
            cacheDirVar.setDynamic(true);

            // Bind these vars permanently, using the UI-safe wrapper for contentLayout
            contextVar.bindRoot(this);
            contentLayoutVar.bindRoot(new UiSafeViewGroup(contentLayout));
            cacheDirVar.bindRoot(getAppCacheDir().getAbsolutePath());

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
                        this);
                RT.var("clojure.core", "intern").invoke(
                        userNS,
                        Symbol.intern("*content-layout*"),
                        new UiSafeViewGroup(contentLayout));
                RT.var("clojure.core", "intern").invoke(
                        userNS,
                        Symbol.intern("*cache-dir*"),
                        getAppCacheDir().getAbsolutePath());

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

    private void renderCode() {
        Log.d(TAG, "Starting renderCode with code length: " + code.length());

        BytecodeCache bytecodeCache = BytecodeCache.getInstance(this, codeHash);

        ClassLoader classLoader = clojureClassLoader;

        boolean hasCompleteCache = bytecodeCache.hasDexCache(codeHash);
        Log.d(TAG, "Code hash: " + codeHash + " hasCompleteCache: " + hasCompleteCache);
        if (hasCompleteCache) {
            classLoader = bytecodeCache.createClassLoaderFromCache(codeHash, clojureClassLoader);
            // Set the context class loader
            Thread.currentThread().setContextClassLoader(classLoader);
        }

        try {
            // Set up the Android delegate
            AndroidClassLoaderDelegate delegate = new AndroidClassLoaderDelegate(
                    getApplicationContext(),
                    classLoader,
                    bytecodeCache,
                    hasCompleteCache,
                    codeHash);

            // Set the delegate via reflection since we're using our own implementation
            // See patches/0002-Patch-DynamicClassLoader.patch for where this is used.
            Field delegateField = DynamicClassLoader.class.getDeclaredField("androidDelegate");
            delegateField.setAccessible(true);
            delegateField.set(null, delegate);

            compileAndExecute(delegate, bytecodeCache, hasCompleteCache);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up class loader", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Compile and execute Clojure code, and save the compiled program for future
     * use
     */
    private void compileAndExecute(AndroidClassLoaderDelegate delegate, BytecodeCache bytecodeCache,
            boolean hasCompleteCache) {
        try {
            Log.d(TAG, "Starting compilation in process: " + android.os.Process.myPid());

            // Setup thread bindings for Clojure
            Var.pushThreadBindings(RT.map(
                    Var.intern(RT.CLOJURE_NS, Symbol.intern("*context*")), RenderActivity.this,
                    Var.intern(RT.CLOJURE_NS, Symbol.intern("*content-layout*")),
                    new UiSafeViewGroup((LinearLayout) findViewById(R.id.content_layout))));

            Log.d(TAG, "Thread bindings established");

            // Read and evaluate code
            long startTime = System.currentTimeMillis();
            LineNumberingPushbackReader pushbackReader = new LineNumberingPushbackReader(new StringReader(code));
            Object lastResult = null;

            try {
                Log.d(TAG, "Starting evaluation");
                while (!isDestroyed) {
                    Object form = LispReader.read(pushbackReader, false, EOF, false);
                    if (form == EOF) {
                        break;
                    }
                    Log.d(TAG, "Evaluating form: " + form);
                    lastResult = Compiler.eval(form);
                    if (lastResult != null) {
                        Log.d(TAG, "Last result class: " + lastResult.getClass().getName());
                    } else {
                        Log.d(TAG, "Last result is null");
                    }
                }
                Log.d(TAG, "Done with evaluation");

                // Check for -main function
                boolean hasMainFunction = RT.var("clojure.core", "-main").deref() instanceof IFn;
                Log.d(TAG, "Code contains -main function: " + hasMainFunction);

                if (hasMainFunction) {
                    try {
                        Log.d(TAG, "Code has -main function, trying to invoke it directly");
                        Object mainFn = RT.var("clojure.core", "-main").deref();
                        if (mainFn instanceof IFn) {
                            Log.d(TAG, "Found -main function, invoking it");
                            lastResult = ((IFn) mainFn).invoke();
                            Log.d(TAG, "Successfully called -main function, result: " + lastResult);
                        } else {
                            Log.d(TAG, "-main var exists but is not a function");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error invoking -main function directly: " + e.getMessage());
                    }
                }

                // Get the result and show it
                final Object result = lastResult;
                Log.i(TAG, "Compilation result: " + result);

                long executionTime = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Code compiled and executed in " + executionTime + "ms");

                updateTimings("Eval", executionTime);

                // Save DEX file for class loading, but only if we are generating the cache now.
                // The cache should exist on subsequent invocations of this activity.
                if (!hasCompleteCache) {
                    List<String> generatedClasses = delegate.getGeneratedClasses();
                    bytecodeCache.generateManifest(codeHash, generatedClasses);
                    Log.d(TAG,
                            "Generated manifest for " + generatedClasses.size() + " classes for hash: " + codeHash);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in Clojure compilation", e);
                lastResult = "Error: " + e.getMessage();
                final String errorMessage = e.getMessage();
                runOnUiThread(() -> showError(errorMessage));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in compilation", e);
            final String errorMessage = e.getMessage();
            runOnUiThread(() -> showError("Error: " + errorMessage));
        } finally {
            Log.d(TAG, "Cleaning up bindings");
            Var.popThreadBindings();
        }
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

        // First let the standard onDestroy complete
        super.onDestroy();

        Log.d(TAG, "RenderActivity destroyed");

        // Only kill if we're coming from back button press
        if (shouldKillOnDestroy && isInRenderProcess()) {
            Log.d(TAG, "Killing render process: " + android.os.Process.myPid());
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * Format an exception with line and column information
     *
     * @param exception  The exception to format
     * @param sourceCode The source code being evaluated
     * @param reader     The reader used for compilation (may contain line/column
     *                   info)
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
            // If anything goes wrong in our error formatting, fall back to the original
            // message
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
     *
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
                    return new int[] { line, column };
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
                    return new int[] { line, column };
                }

                // Try alternative pattern
                pattern = java.util.regex.Pattern.compile("at line (\\d+), column (\\d+)");
                matcher = pattern.matcher(message);

                if (matcher.find()) {
                    int line = Integer.parseInt(matcher.group(1));
                    int column = Integer.parseInt(matcher.group(2));
                    Log.d(TAG, "Extracted from alt message pattern - line: " + line + ", column: " + column);
                    return new int[] { line, column };
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting line/column info", e);
            return null;
        }
    }

    private boolean isInRenderProcess() {
        int pid = android.os.Process.myPid();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : am.getRunningAppProcesses()) {
                if (processInfo.pid == pid) {
                    return processInfo.processName.contains(":render");
                }
            }
        }
        return false;
    }
}
