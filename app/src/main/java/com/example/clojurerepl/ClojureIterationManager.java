package com.example.clojurerepl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ClojureIterationManager {
    private static final String TAG = "ClojureIterationManager";

    private final Context context;
    private final LLMClient llmClient;
    private final LogcatMonitor logcatMonitor;
    private final ScreenshotManager screenshotManager;

    private CompletableFuture<IterationResult> currentIterationFuture;
    private BroadcastReceiver renderCompletionReceiver;

    public static class IterationResult {
        public final String code;
        public final String logcat;
        public final File screenshot;
        public final boolean success;
        public final String feedback;

        public IterationResult(String code, String logcat, File screenshot,
                             boolean success, String feedback) {
            this.code = code;
            this.logcat = logcat;
            this.screenshot = screenshot;
            this.success = success;
            this.feedback = feedback;
        }
    }

    private final List<IterationResult> iterationHistory = new ArrayList<>();

    public ClojureIterationManager(Context context, LLMClient llmClient) {
        this.context = context.getApplicationContext();
        this.llmClient = llmClient;
        this.logcatMonitor = new LogcatMonitor();
        this.screenshotManager = new ScreenshotManager(context);
    }

    public CompletableFuture<String> generateCode(String description) {
        return llmClient.generateInitialCode(description);
    }

    public CompletableFuture<IterationResult> runIteration(String code) {
        if (currentIterationFuture != null && !currentIterationFuture.isDone()) {
            currentIterationFuture.cancel(true);
        }

        currentIterationFuture = new CompletableFuture<>();

        // Clear previous logs
        logcatMonitor.clearLogs();
        logcatMonitor.startMonitoring();

        // Register for completion broadcast
        if (renderCompletionReceiver != null) {
            context.unregisterReceiver(renderCompletionReceiver);
        }

        renderCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (RenderActivity.ACTION_RENDER_COMPLETE.equals(intent.getAction())) {
                    boolean success = intent.getBooleanExtra(RenderActivity.EXTRA_SUCCESS, false);
                    String error = intent.getStringExtra(RenderActivity.EXTRA_ERROR);

                    // Stop monitoring logs
                    logcatMonitor.stopMonitoring();

                    // Get the logs
                    String logs = logcatMonitor.getCollectedLogs();

                    // Create result
                    IterationResult result = new IterationResult(
                        code,
                        logs,
                        null, // Screenshot will be added by activity
                        success,
                        error
                    );

                    // Complete the future
                    currentIterationFuture.complete(result);

                    // Clean up receiver
                    context.unregisterReceiver(this);
                    renderCompletionReceiver = null;
                }
            }
        };

        context.registerReceiver(renderCompletionReceiver,
            new IntentFilter(RenderActivity.ACTION_RENDER_COMPLETE));

        // Launch RenderActivity
        Intent intent = new Intent(context, RenderActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("code", code);
        context.startActivity(intent);

        return currentIterationFuture;
    }

    public CompletableFuture<String> generateNextIteration(String feedback,
                                                         IterationResult lastResult) {
        return llmClient.generateNextIteration(
            "", // We'll need to store the original description
            lastResult.code,
            lastResult.logcat,
            lastResult.screenshot,
            feedback
        );
    }

    public IterationResult getLastResult() {
        if (iterationHistory.isEmpty()) {
            return null;
        }
        return iterationHistory.get(iterationHistory.size() - 1);
    }

    public void addIterationResult(IterationResult result) {
        iterationHistory.add(result);
    }

    public void cleanup() {
        if (renderCompletionReceiver != null) {
            try {
                context.unregisterReceiver(renderCompletionReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered
            }
            renderCompletionReceiver = null;
        }

        if (currentIterationFuture != null && !currentIterationFuture.isDone()) {
            currentIterationFuture.cancel(true);
        }

        logcatMonitor.shutdown();
        screenshotManager.clearScreenshots();
    }
}
