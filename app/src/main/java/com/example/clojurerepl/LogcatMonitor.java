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

    public void startMonitoring(int processId) {
        if (isMonitoring.get()) {
            return;
        }

        try {
            String [] command = new String[] { "logcat", "--pid=" + processId,
                        "'*:I'", "'*:E'", "'*:W'", "'*:D'",
                        "-v", "time",
                        "-s", "AndroidRuntime", "-s", "ClojureApp", };
            // Monitor by process ID - include Debug level for application logs
            logcatProcess = Runtime.getRuntime().exec(command);

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
                } catch (java.io.InterruptedIOException e) {
                    // This is expected when we kill the logcat process
                    Log.d(TAG, "Logcat process terminated.");
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
            // Kill the logcat process first to unblock the reader
            if (logcatProcess != null) {
                logcatProcess.destroy();
                // Give it a moment to terminate gracefully
                try {
                    logcatProcess.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Ignore timeout
                }
                // Force kill if still running
                if (logcatProcess.isAlive()) {
                    logcatProcess.destroyForcibly();
                }
                logcatProcess = null;
            }

            // Give the thread a chance to see the flag change and exit gracefully
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
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
