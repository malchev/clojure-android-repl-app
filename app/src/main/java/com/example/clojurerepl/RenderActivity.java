package com.example.clojurerepl;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import clojure.lang.DynamicClassLoader;
import android.util.Log;
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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.PixelCopy;
import android.opengl.GLSurfaceView;
import android.view.View;
import android.view.ViewParent;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.ArrayList;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import android.os.Build;

public class RenderActivity extends AppCompatActivity {
    private static final String TAG = "ClojureRender";
    private static final String CLOJURE_APP_CACHE_DIR = "clojure_app_cache";
    // Define EOF object for detecting end of input
    private static final Object EOF = new Object();

    // inputs
    public static final String EXTRA_LAUNCHING_ACTIVITY = "launching_activity";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_MESSAGE_INDEX = "message_index";
    public static final String EXTRA_ITERATION = "iteration";
    public static final String EXTRA_ENABLE_SCREENSHOTS = "enable_screenshots";
    public static final String EXTRA_PID_FILE = "pid_file";
    public static final String EXTRA_AUTO_RETURN_ON_ERROR = "auto_return_on_error";

    // results
    public static final String EXTRA_RESULT_SCREENSHOT_PATHS = "result_screenshot_paths";
    public static final String EXTRA_RESULT_ERROR = "result_error";
    public static final String EXTRA_RESULT_TIMINGS = "result_timings";
    public static final String EXTRA_RESULT_AUTO_RETURN_ON_ERROR = "result_return_on_error";
    // these are copies of EXTRA_SESSION_ID, EXTRA_MESSAGE_INDEX,
    // EXTRA_ITERATION that we pass back to the caller upon return
    public static final String EXTRA_RESULT_SESSION_ID  = "result_session_id";
    public static final String EXTRA_RESULT_MESSAGE_INDEX = "result_message_index";
    public static final String EXTRA_RESULT_ITERATION = "result_iteration";

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

    // Add fields for session ID and iteration count
    private String sessionId;
    private int messageIndex;
    private int iteration;

    // Add flag for controlling screenshots
    private boolean screenshotsEnabled = false;

    // Add flag for controlling error handling behavior
    private boolean returnOnError = false;

    // Add a field to track screenshots
    private List<File> capturedScreenshots = new ArrayList<>();
    // Add a field to track result of Clojure compilation and execution
    private String clojureStatus = null;

    // Add a flag to track when back is pressed
    private boolean isBackPressed = false;

    // Add a field to track the last screenshot time
    private long lastScreenshotTime = 0;
    private static final long MIN_SCREENSHOT_INTERVAL_MS = 500; // Minimum time between screenshots

    // Add this field to track the launching activity
    private String parentActivity;

    private Class<?> getParentActivityClass() {
        assert parentActivity != null;
        Log.d(TAG, "RenderActivity launched by: " + parentActivity);
        Class<?> parentActivityClass = null;
        try {
            parentActivityClass = Class.forName(parentActivity);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Could not find parent activity class: " + parentActivity);
            assert false;
        }
        return parentActivityClass;
    }

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

    /**
     * Launches RenderActivity and gets its PID
     */

    public interface ExitCallback {
        public void onExit(String logcat);
    };

    public static boolean launch(Context context, Class<?> launchingActivity,
            ExitCallback cb,
            String code, String sessionId, int messageIndex, int iteration,
            boolean enableScreenshots, boolean returnOnError) {
        try {
            LogcatMonitor logcatMonitor = new LogcatMonitor();

            BroadcastReceiver pidReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int pid = intent.getIntExtra("pid", -1);
                    Log.d(TAG, "Received PID from RenderActivity: " + pid + ", starting LogcatMonitor.");
                    logcatMonitor.startMonitoring(pid);
                }
            };

            context.registerReceiver(pidReceiver,
                    new IntentFilter("com.example.clojurerepl.ACTION_REMOTE_PID"),
                    Context.RECEIVER_NOT_EXPORTED);

            ServiceConnection remoteConnection = new ServiceConnection() {
                IBinder.DeathRecipient deathRecipient;

                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    deathRecipient = new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            logcatMonitor.stopMonitoring();
                            String logcatOutput = logcatMonitor.getCollectedLogs().trim();
                            logcatMonitor.shutdown();

                            // pidReceiver is already unregistered here when the
                            // activity crashes rather than exits gracefully.
                            try {
                                context.unregisterReceiver(pidReceiver);
                            } catch (java.lang.IllegalArgumentException e) {
                                // ignore
                            }

                            Log.d(TAG, "Received process logcat of length: " + logcatOutput.length());
                            cb.onExit(logcatOutput);
                        }
                    };

                    try {
                        service.linkToDeath(deathRecipient, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "ServiceConnection: failed to link to death.");
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // empty
                }
            };
            Intent serviceIntent = new Intent(context, RenderActivityWatchdogService.class);
            context.bindService(serviceIntent, remoteConnection, Context.BIND_AUTO_CREATE);

            Intent launchIntent = new Intent(context, RenderActivity.class);
            launchIntent.putExtra(RenderActivity.EXTRA_CODE, code);
            launchIntent.putExtra(RenderActivity.EXTRA_SESSION_ID, sessionId);
            launchIntent.putExtra(RenderActivity.EXTRA_MESSAGE_INDEX, messageIndex);
            launchIntent.putExtra(RenderActivity.EXTRA_ITERATION, iteration);
            launchIntent.putExtra(RenderActivity.EXTRA_ENABLE_SCREENSHOTS, enableScreenshots);
            launchIntent.putExtra(RenderActivity.EXTRA_AUTO_RETURN_ON_ERROR, returnOnError);
            launchIntent.putExtra(RenderActivity.EXTRA_LAUNCHING_ACTIVITY, launchingActivity.getName());
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching render activity and getting PID", e);
            return false;
        }

        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_render);

            // If the activity crashes (typically with an AndroidRuntime
            // exception), catch it here in the main thread and send an intent
            // to the parent activity that execution failed. Normally result
            // is communicated via onNewIntent().
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                Log.e(TAG, "RenderActivity crashed", throwable);
                Toast.makeText(this, "RenderActivity crashed", Toast.LENGTH_LONG).show();

                if (parentActivity != null) {
                    Class<?> parentActivityClass = getParentActivityClass();
                    if (parentActivityClass != null) {
                        Intent intent = new Intent(this, parentActivityClass);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        String errorMessage = "RenderActivity crashed: " + formatFullErrorMessage(throwable);
                        // Can't call showError() here since the activity has
                        // crashed. Instead, show a toast and return the error
                        // to the calling activity.
                        handleError(errorMessage, true); // force exit
                    } else {
                        Log.e(TAG, "Could not get class for " + parentActivity);
                    }
                } else {
                    Log.e(TAG, "ParentActivity is null");
                }

                Log.d(TAG, "Invoking finish()");
                finish();

                Log.d(TAG, "Killing render process in unchaught-exception handler: " + android.os.Process.myPid());
                // Since onDestroy will not be invoked, kill the process
                android.os.Process.killProcess(android.os.Process.myPid());
            });

            activityStartTime = System.currentTimeMillis();
            int pid = android.os.Process.myPid();
            Log.d(TAG, "RenderActivity onCreate started in process: " + pid);
            Intent pidIntent = new Intent("com.example.clojurerepl.ACTION_REMOTE_PID");
            pidIntent.putExtra("pid", pid);
            pidIntent.setPackage(getPackageName());
            sendBroadcast(pidIntent);

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
                Intent intent = getIntent();
                assert intent != null;

                // Determine the correct parent activity to return to
                parentActivity = intent.getStringExtra(EXTRA_LAUNCHING_ACTIVITY);
                assert parentActivity != null;
                Class<?> parentActivityClass = getParentActivityClass();

                // Extract session ID, message index, and iteration count from intent
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
                assert sessionId != null;
                messageIndex = intent.getIntExtra(EXTRA_MESSAGE_INDEX, 0);
                iteration = intent.getIntExtra(EXTRA_ITERATION, 0);
                Log.d(TAG, "Session ID: " + sessionId + ", Message index: " +
                        messageIndex + ", Iteration: " + iteration);

                // Extract screenshot flag from intent
                screenshotsEnabled = intent.getBooleanExtra(EXTRA_ENABLE_SCREENSHOTS, false);
                Log.d(TAG, "Screenshots enabled: " + screenshotsEnabled);

                // Extract return on error flag from intent
                returnOnError = intent.getBooleanExtra(EXTRA_AUTO_RETURN_ON_ERROR, false);
                Log.d(TAG, "Return on error enabled: " + returnOnError);

                // Store code in class member instead of local variable
                code = intent.getStringExtra(EXTRA_CODE);
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
                    handleError("No code provided in intent", false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in RenderActivity", e);
                handleError("Error: " + e.getMessage(), false);
            }

            // Add touch interceptor to detect user interactions
            contentLayout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // Skip screenshot capture if back button is being pressed
                    if (event.getAction() == MotionEvent.ACTION_DOWN && !isBackPressed) {
                        // Take screenshot on touch down
                        new Handler().postDelayed(() -> {
                            File screenshot = takeScreenshot();
                            if (screenshot != null) {
                                capturedScreenshots.add(screenshot);
                                Log.d(TAG, "Touch DOWN screenshot captured: " + screenshot.getAbsolutePath());
                            }
                        }, 100); // Short delay on down event
                    }
                    return false; // Don't consume the event
                }
            });

            // After your current touch listener setup, add:
            setupScreenshotForClickableViews(contentLayout);
            observeViewHierarchyChanges(contentLayout);
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
        });
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "Back button pressed, marking activity as destroyed");

        // Set flag to prevent duplicate screenshot
        isBackPressed = true;

        // Delay sending screenshots to parent to allow touch events to complete
        new Handler().postDelayed(() -> {
            // Determine the correct parent activity to return to
            Class<?> parentActivityClass = getParentActivityClass();

            Intent parentIntent = new Intent(this, parentActivityClass);

            // Use FLAG_ACTIVITY_CLEAR_TOP to ensure we go back to the existing instance
            parentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            final boolean success = clojureStatus == null;
            if (success) {
                parentIntent.putExtra(EXTRA_RESULT_TIMINGS, timingData.toString());
            }

            // Add all screenshot paths to intent
            if (!capturedScreenshots.isEmpty()) {
                String[] screenshotPaths = new String[capturedScreenshots.size()];
                for (int i = 0; i < capturedScreenshots.size(); i++) {
                    screenshotPaths[i] = capturedScreenshots.get(i).getAbsolutePath();
                }
                parentIntent.putExtra(EXTRA_RESULT_SCREENSHOT_PATHS, screenshotPaths);
            }

            // Add the feedback to the intent
            if (clojureStatus != null) {
                Log.d(TAG, "Adding error to parent intent: " + clojureStatus);
                parentIntent.putExtra(EXTRA_RESULT_ERROR, clojureStatus);
                parentIntent.putExtra(EXTRA_RESULT_AUTO_RETURN_ON_ERROR, false);
            }

            parentIntent.putExtra(EXTRA_RESULT_SESSION_ID, sessionId);
            parentIntent.putExtra(EXTRA_RESULT_MESSAGE_INDEX, messageIndex);
            parentIntent.putExtra(EXTRA_RESULT_ITERATION, iteration);

            startActivity(parentIntent);

            // Continue with back press
            RenderActivity.super.onBackPressed();
        }, 200); // Short delay to ensure we capture any in-flight touch events
    }

    private File takeScreenshot() {
        // Skip screenshot if screenshots are disabled
        if (!screenshotsEnabled) {
            Log.d(TAG, "Screenshots are disabled, skipping capture");
            return null;
        }

        // Check if we need to throttle screenshot capture
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastScreenshotTime < MIN_SCREENSHOT_INTERVAL_MS) {
            Log.d(TAG, "Skipping screenshot - too soon after previous capture");
            return null;
        }

        try {
            // Get the root view
            View rootView = getWindow().getDecorView().getRootView();

            // Make sure the view has been drawn
            if (rootView.getWidth() == 0 || rootView.getHeight() == 0) {
                Log.e(TAG, "Cannot take screenshot, view has not been drawn");
                return null;
            }

            // Find all GLSurfaceView instances in the hierarchy
            List<GLSurfaceView> glSurfaceViews = findGLSurfaceViews(rootView);
            Log.d(TAG, "Found " + glSurfaceViews.size() + " GLSurfaceView(s) in hierarchy");

            // Create a bitmap with the layout (this won't capture GLSurfaceView content)
            rootView.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(rootView.getDrawingCache());
            rootView.setDrawingCacheEnabled(false);

            if (bitmap == null) {
                Log.e(TAG, "Failed to create bitmap from drawing cache");
                return null;
            }

            // If we have GLSurfaceView instances, capture them and composite onto the bitmap
            if (!glSurfaceViews.isEmpty()) {
                bitmap = compositeGLSurfaceViews(bitmap, rootView, glSurfaceViews);
            }

            // Use ScreenshotManager to save the bitmap
            ScreenshotManager screenshotManager = new ScreenshotManager(this);
            // New filename format: session_[id]_iter_[num]_[timestamp].png
            String fileName = "session_" +
                    (sessionId != null ? sessionId : "unknown") +
                    "_iter_" +
                    iteration +
                    "_" +
                    System.currentTimeMillis() +
                    ".png";
            File screenshot = screenshotManager.saveScreenshot(bitmap, fileName);

            // Verify the file was created
            if (screenshot != null && screenshot.exists()) {
                lastScreenshotTime = currentTime;
                Log.d(TAG, "Screenshot saved successfully: " + screenshot.getAbsolutePath() +
                        " size: " + screenshot.length() + " bytes");
            } else {
                Log.e(TAG, "Screenshot file was not created");
            }

            return screenshot;
        } catch (Exception e) {
            Log.e(TAG, "Error taking screenshot", e);
            return null;
        }
    }

    /**
     * Recursively find all GLSurfaceView instances in the view hierarchy
     */
    private List<GLSurfaceView> findGLSurfaceViews(View root) {
        List<GLSurfaceView> glSurfaceViews = new ArrayList<>();
        findGLSurfaceViewsRecursive(root, glSurfaceViews);
        return glSurfaceViews;
    }

    private void findGLSurfaceViewsRecursive(View view, List<GLSurfaceView> result) {
        if (view instanceof GLSurfaceView) {
            result.add((GLSurfaceView) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findGLSurfaceViewsRecursive(group.getChildAt(i), result);
            }
        }
    }

    /**
     * Calculate the position of a view relative to a root view by traversing the hierarchy
     */
    private int[] getViewPositionRelativeToRoot(View view, View rootView) {
        int x = 0;
        int y = 0;
        View current = view;

        while (current != null && current != rootView) {
            x += current.getLeft();
            y += current.getTop();
            ViewParent parent = current.getParent();
            if (parent instanceof View) {
                current = (View) parent;
            } else {
                break;
            }
        }

        return new int[]{x, y};
    }

    /**
     * Composite GLSurfaceView captures onto the base bitmap using PixelCopy
     */
    private Bitmap compositeGLSurfaceViews(Bitmap baseBitmap, View rootView, List<GLSurfaceView> glSurfaceViews) {
        // Create a mutable copy of the base bitmap for compositing
        Bitmap compositeBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(compositeBitmap);

        // Capture each GLSurfaceView and composite it onto the bitmap
        for (GLSurfaceView glView : glSurfaceViews) {
            try {
                // Get the view's position relative to the root view by traversing the hierarchy
                int[] relativePos = getViewPositionRelativeToRoot(glView, rootView);
                int x = relativePos[0];
                int y = relativePos[1];
                int width = glView.getWidth();
                int height = glView.getHeight();

                Log.d(TAG, "GLSurfaceView relative position: (" + x + ", " + y +
                    "), size: " + width + "x" + height +
                    ", bitmap size: " + compositeBitmap.getWidth() + "x" + compositeBitmap.getHeight());

                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "GLSurfaceView has invalid dimensions, skipping: " + width + "x" + height);
                    continue;
                }

                Log.d(TAG, "Capturing GLSurfaceView at (" + x + ", " + y + ") size: " + width + "x" + height);

                // Create a bitmap for the GLSurfaceView content
                Bitmap glBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                // Use PixelCopy API (available from API 26+) to capture GLSurfaceView directly
                // GLSurfaceView extends SurfaceView, so we can use PixelCopy.request() with it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Use a synchronized approach to capture the result
                    final Bitmap[] capturedBitmap = new Bitmap[1];
                    final Object lock = new Object();
                    final boolean[] completed = new boolean[1];
                    final int[] copyResult = new int[1];

                    // Create a background handler thread for the callback to avoid blocking main thread
                    HandlerThread handlerThread = new HandlerThread("PixelCopyHandler");
                    handlerThread.start();
                    Handler handler = new Handler(handlerThread.getLooper());

                    try {
                        // Request pixel copy from the GLSurfaceView
                        PixelCopy.request(glView, glBitmap,
                            new PixelCopy.OnPixelCopyFinishedListener() {
                                @Override
                                public void onPixelCopyFinished(int result) {
                                    synchronized (lock) {
                                        copyResult[0] = result;
                                        if (result == PixelCopy.SUCCESS) {
                                            capturedBitmap[0] = glBitmap;
                                            Log.d(TAG, "Successfully captured GLSurfaceView content");
                                        } else {
                                            Log.e(TAG, "PixelCopy failed with result: " + result);
                                        }
                                        completed[0] = true;
                                        lock.notifyAll();
                                    }
                                }
                            }, handler);

                        // Wait for the PixelCopy to complete (with timeout)
                        synchronized (lock) {
                            try {
                                if (!completed[0]) {
                                    lock.wait(2000); // Wait up to 2 seconds
                                }
                                if (!completed[0]) {
                                    Log.w(TAG, "PixelCopy timed out after 2 seconds");
                                }
                            } catch (InterruptedException e) {
                                Log.e(TAG, "Interrupted while waiting for PixelCopy", e);
                                Thread.currentThread().interrupt();
                            }
                        }

                        // Draw the captured GL content onto the composite bitmap
                        if (capturedBitmap[0] != null && x >= 0 && y >= 0 &&
                            x + width <= compositeBitmap.getWidth() &&
                            y + height <= compositeBitmap.getHeight()) {
                            canvas.drawBitmap(capturedBitmap[0], x, y, null);
                            Log.d(TAG, "Composited GLSurfaceView onto screenshot at (" + x + ", " + y + ")");
                        } else {
                            if (capturedBitmap[0] == null) {
                                Log.w(TAG, "Could not composite GLSurfaceView - capture failed (result: " + copyResult[0] + ")");
                            } else {
                                Log.w(TAG, "Could not composite GLSurfaceView - invalid position: (" + x + ", " + y +
                                    ") bitmap size: " + compositeBitmap.getWidth() + "x" + compositeBitmap.getHeight() +
                                    " GLView size: " + width + "x" + height);
                            }
                        }
                    } finally {
                        // Clean up handler thread
                        handlerThread.quitSafely();
                        try {
                            handlerThread.join(100); // Wait up to 100ms for thread to finish
                        } catch (InterruptedException e) {
                            Log.w(TAG, "Interrupted while waiting for handler thread to finish", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                } else {
                    Log.w(TAG, "PixelCopy not available on this API level");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error capturing GLSurfaceView", e);
            }
        }

        return compositeBitmap;
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

        // After rendering is complete, take an initial screenshot
        new Handler().postDelayed(() -> {
            File screenshot = takeScreenshot();
            if (screenshot != null) {
                capturedScreenshots.add(screenshot);
                Log.d(TAG, "Initial screenshot captured: " + screenshot.getAbsolutePath());
            }
        }, 500); // Slight delay to allow UI to render fully
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
                        // Capture the error and format it for propagation back to parent activity
                        String fullErrorMessage = "Error invoking -main function directly: "
                                + formatFullErrorMessage(e);
                        handleError(fullErrorMessage, false);
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
                Log.d(TAG, "Clojure compilation error (expected during iteration process)", e);
                lastResult = "Error: " + e.getMessage();

                // Capture the full error information including "Caused by" details
                String fullErrorMessage = formatFullErrorMessage(e);
                handleError(fullErrorMessage, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up Clojure environment", e);
            // Throw runtime exception for environment setup errors, as they indicate an app
            // bug
            // rather than a user code error
            throw new RuntimeException("Error setting up Clojure environment: " + e.getMessage(), e);
        } finally {
            Log.d(TAG, "Cleaning up bindings");
            Var.popThreadBindings();
        }
    }

    /**
     * Handle an error based on the returnOnError flag
     * 
     * @param errorMessage The error message to handle
     */
    private void handleError(String errorMessage, boolean forceExit) {
        clojureStatus = errorMessage;

        if (forceExit || returnOnError) {
            // Return to calling activity with error
            Class<?> parentActivityClass = getParentActivityClass();
            if (parentActivityClass != null) {
                Intent intent = new Intent(this, parentActivityClass);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(EXTRA_RESULT_ERROR, errorMessage);
                intent.putExtra(EXTRA_RESULT_AUTO_RETURN_ON_ERROR, returnOnError);
                intent.putExtra(EXTRA_RESULT_SESSION_ID, sessionId);
                intent.putExtra(EXTRA_RESULT_MESSAGE_INDEX, messageIndex);
                intent.putExtra(EXTRA_RESULT_ITERATION, iteration);
                startActivity(intent);
                finish();
            }
        } else {
            // Show error in UI
            showError(errorMessage);
        }
    }

    /**
     * Format a full error message including the "Caused by" information
     *
     * @param exception The exception to format
     * @return A formatted error message with full details
     */
    private String formatFullErrorMessage(Throwable exception) {
        try {
            // Start with the original exception's message
            StringBuilder fullMessage = new StringBuilder();
            String originalMessage = exception.getMessage();
            if (originalMessage != null) {
                fullMessage.append(originalMessage);
            } else {
                fullMessage.append(exception.toString());
            }

            // Get the root cause of the exception
            Throwable cause = getRootCause(exception);

            // Add "Caused by" information if it exists and is different from the original
            // message
            if (cause != null && cause != exception) {
                String causeMessage = cause.getMessage();
                if (causeMessage != null && !causeMessage.equals(originalMessage)) {
                    fullMessage.append("\n\nCaused by: ").append(causeMessage);
                }
            }

            return fullMessage.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error formatting full error message", e);
            // Fall back to the original message if formatting fails
            return exception.getMessage() != null ? exception.getMessage() : exception.toString();
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

            // Parse the message to separate main message, location, and additional details
            String mainMessage = message;
            String locationInfo = "";
            String sourceCode = "";
            String additionalDetails = "";

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

            // Extract additional details (Caused by, Root cause, etc.)
            int causedByIndex = message.indexOf("\n\nCaused by:");
            if (causedByIndex > 0) {
                additionalDetails = message.substring(causedByIndex + 2); // Skip the \n\n
                // Remove additional details from main message if they were included
                if (mainMessage.length() > causedByIndex) {
                    mainMessage = mainMessage.substring(0, causedByIndex);
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

            // Add additional details if present
            if (!additionalDetails.isEmpty()) {
                TextView detailsView = new TextView(this);
                detailsView.setText(additionalDetails);
                detailsView.setTypeface(Typeface.MONOSPACE);
                detailsView.setTextSize(12);
                detailsView.setTextColor(Color.parseColor("#FF6F00")); // Material Orange
                detailsView.setBackgroundColor(Color.parseColor("#FFF3E0")); // Light Orange
                detailsView.setPadding(16, 8, 16, 8);
                errorContainer.addView(detailsView);
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
        Log.d(TAG, "Killing render process: " + android.os.Process.myPid());
        android.os.Process.killProcess(android.os.Process.myPid());
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
     * Chain a new touch listener with an existing one, if present.
     * This preserves any touch listeners that Clojure code may have set.
     * Uses a WeakHashMap to track original listeners, avoiding brittle reflection.
     */
    private void chainTouchListener(View view, View.OnTouchListener screenshotListener) {
        // Check if we've already wrapped this view's listener
        View.OnTouchListener existingListener = null;

        // Try reflection as a fallback to detect listeners set before our code runs
        // This is a best-effort attempt - if it fails, we proceed without chaining
        try {
            Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo != null) {
                Field touchListenerField = listenerInfo.getClass().getDeclaredField("mOnTouchListener");
                touchListenerField.setAccessible(true);
                existingListener = (View.OnTouchListener) touchListenerField.get(listenerInfo);
            }
        } catch (Exception e) {
            // Reflection failed - likely no existing listener or Android version mismatch
            // This is OK, we'll just set our listener without chaining
            Log.d(TAG, "No existing touch listener detected (or reflection unavailable): " + e.getMessage());
        }

        // Create a chained listener that calls both
        final View.OnTouchListener existing = existingListener;
        view.setOnTouchListener((v, event) -> {
            // First, call our screenshot listener
            boolean screenshotConsumed = screenshotListener.onTouch(v, event);

            // Then call the existing listener if present
            if (existing != null) {
                boolean existingConsumed = existing.onTouch(v, event);
                // Return true if either listener consumed the event
                return screenshotConsumed || existingConsumed;
            }

            return screenshotConsumed;
        });
    }

    private void setupScreenshotForClickableViews(ViewGroup viewGroup) {
        // Process all child views
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);

            // If this is a clickable view that likely handles click events
            if (child.isClickable()) {
                // Instead of reflection, use an OnTouchListener that doesn't interfere with
                // clicks
                child.setOnTouchListener((v, event) -> {
                    // Skip screenshot capture if back button is being pressed
                    if (event.getAction() == MotionEvent.ACTION_DOWN && !isBackPressed) {
                        // Take screenshot on button press
                        new Handler().postDelayed(() -> {
                            File screenshot = takeScreenshot();
                            if (screenshot != null) {
                                capturedScreenshots.add(screenshot);
                                Log.d(TAG, "Button press screenshot: " + screenshot.getAbsolutePath());
                            }
                        }, 100);
                    }
                    // Return false to not consume the event and allow normal click processing
                    return false;
                });

                Log.d(TAG, "Added screenshot capture to clickable view: " + child.getClass().getSimpleName());
            }

            // Also handle GLSurfaceView instances (they consume touch events but aren't
            // clickable)
            if (child instanceof GLSurfaceView) {
                View.OnTouchListener screenshotListener = (v, event) -> {
                    // Skip screenshot capture if back button is being pressed
                    if (event.getAction() == MotionEvent.ACTION_DOWN && !isBackPressed) {
                        // Take screenshot on touch down
                        new Handler().postDelayed(() -> {
                            File screenshot = takeScreenshot();
                            if (screenshot != null) {
                                capturedScreenshots.add(screenshot);
                                Log.d(TAG, "GLSurfaceView touch screenshot: " + screenshot.getAbsolutePath());
                            }
                        }, 100);
                    }
                    // Return false to not consume the event and allow GLSurfaceView's own touch
                    // handling
                    return false;
                };

                chainTouchListener(child, screenshotListener);
                Log.d(TAG, "Added screenshot capture to GLSurfaceView (chained with existing listener if present)");
            }

            // Recursively process child view groups
            if (child instanceof ViewGroup) {
                setupScreenshotForClickableViews((ViewGroup) child);
            }
        }
    }

    // Also add this method to handle views added dynamically after render
    private void observeViewHierarchyChanges(ViewGroup viewGroup) {
        viewGroup.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            // Check for any new clickable views
            setupScreenshotForClickableViews(viewGroup);
        });
    }

    // Add this helper method to disable all touch listeners
    private void disableTouchListeners(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);

            // Clear any touch listeners
            child.setOnTouchListener(null);

            // Recurse on view groups
            if (child instanceof ViewGroup) {
                disableTouchListeners((ViewGroup) child);
            }
        }
    }
}
