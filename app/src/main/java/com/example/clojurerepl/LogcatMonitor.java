package com.example.clojurerepl;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

public class LogcatMonitor {
    private static final String TAG = "LogcatMonitor";
    private static final String CLOJURE_APP_TAG = "ClojureApp";

    private final StringBuilder logBuffer = new StringBuilder();
    private Process logcatProcess;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);

    public interface LogCallback {
        void onNewLog(String log);
    }

    private LogCallback callback;

    public void setCallback(LogCallback callback) {
        this.callback = callback;
    }

    public void startMonitoring() {
        if (isMonitoring.get()) {
            return;
        }

        try {
            // Clear existing logcat buffer
            Runtime.getRuntime().exec("logcat -c");

            // Start logcat process for our specific tag
            logcatProcess = Runtime.getRuntime().exec(
                    new String[] { "logcat", CLOJURE_APP_TAG + ":D", "*:S" });

            isMonitoring.set(true);

            // Start reading the logcat output
            executor.execute(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(logcatProcess.getInputStream()));
                    String line;

                    while (isMonitoring.get() && (line = reader.readLine()) != null) {
                        synchronized (logBuffer) {
                            logBuffer.append(line).append("\n");
                        }

                        if (callback != null) {
                            callback.onNewLog(line);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading logcat", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error starting logcat monitor", e);
        }
    }

    public void stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            // Signal the thread to stop before destroying the process
            try {
                // Give the thread a chance to see the flag change
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            // Now close the process
            if (logcatProcess != null) {
                logcatProcess.destroy();
                logcatProcess = null;
            }
        }
    }

    public String getCollectedLogs() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }

    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.setLength(0);
        }
    }

    public void shutdown() {
        // Set the flag first
        isMonitoring.set(false);

        // Give any running thread time to exit its read loop
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // Ignore
        }

        // Then stop monitoring
        stopMonitoring();

        // Then shutdown the executor
        executor.shutdownNow();
        try {
            // Give executor time to shutdown
            executor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "Executor shutdown interrupted", e);
        }
    }
}
