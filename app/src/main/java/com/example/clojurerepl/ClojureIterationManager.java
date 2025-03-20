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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClojureIterationManager {
    private static final String TAG = "ClojureIterationManager";

    private final Context context;
    private final LLMClient llmClient;
    private final LogcatMonitor logcatMonitor;
    private final ScreenshotManager screenshotManager;

    private CompletableFuture<IterationResult> currentIterationFuture;
    private BroadcastReceiver renderCompletionReceiver;
    private ExecutorService executor;
    private CompletableFuture<IterationResult> renderFuture;
    private CompletableFuture<String> generationFuture;
    private IterationResult lastResult;

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

        // Initialize executor for background tasks
        this.executor = Executors.newCachedThreadPool();

        // Register BroadcastReceiver to handle render activity completion
        renderCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Handle render completion
                Log.d(TAG, "Received render completion broadcast");

                if (renderFuture != null && !renderFuture.isDone()) {
                    boolean success = intent.getBooleanExtra(RenderActivity.EXTRA_SUCCESS, false);
                    String error = intent.getStringExtra(RenderActivity.EXTRA_ERROR);

                    if (success) {
                        Log.d(TAG, "Render completed successfully");
                        // TODO: Get screenshot and logcat
                        renderFuture.complete(new IterationResult(
                                "Success", "Logcat", null, true, ""));
                    } else {
                        Log.e(TAG, "Render failed: " + error);
                        renderFuture.completeExceptionally(new RuntimeException(error));
                    }
                }
            }
        };

        // Register receiver with appropriate flags based on Android version
        IntentFilter filter = new IntentFilter(RenderActivity.ACTION_RENDER_COMPLETE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit receiver flag
            context.registerReceiver(renderCompletionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Older Android versions
            context.registerReceiver(renderCompletionReceiver, filter);
        }
    }

    /**
     * Extracts clean Clojure code from text that may contain markdown code block
     * markers
     * Helper method to ensure consistent code extraction across the app
     */
    public String extractClojureCode(String input) {
        if (input == null || input.isEmpty()) {
            Log.d(TAG, "Input is null or empty, returning empty string");
            return "";
        }

        // Check if the input contains markdown code block markers
        if (input.contains("```")) {
            try {
                // Look for a code block
                int startMarkerPos = input.indexOf("```");
                if (startMarkerPos != -1) {
                    // Find the end of the start marker line
                    int lineEnd = input.indexOf('\n', startMarkerPos);
                    if (lineEnd != -1) {
                        // Skip past the entire marker line
                        int codeStart = lineEnd + 1;

                        // Find the closing code block marker
                        int endMarkerPos = input.indexOf("```", codeStart);
                        if (endMarkerPos != -1) {
                            // Extract just the code between the markers
                            String extractedCode = input.substring(codeStart, endMarkerPos).trim();
                            Log.d(TAG, "Successfully extracted code. Length: " + extractedCode.length());
                            return extractedCode;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting code", e);
            }
        }

        // If we couldn't extract a code block or there are no markers, return the
        // original input
        Log.d(TAG, "No code block markers found, using original input");
        return input;
    }

    public CompletableFuture<String> generateCode(String description) {
        Log.d(TAG, "Starting code generation with description: " + description);

        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        // Create a new future for this generation
        generationFuture = new CompletableFuture<>();

        // Run in background thread
        executor.execute(() -> {
            try {
                // Call the LLM client which returns a CompletableFuture
                CompletableFuture<String> llmFuture = llmClient.generateInitialCode(description);

                // When the LLM response is ready, complete our future
                llmFuture.thenAccept(code -> {
                    Log.d(TAG, "LLM response first 100 chars: " +
                            (code != null && code.length() > 100 ? code.substring(0, 100) : code));

                    // Extract clean code from markdown blocks if present
                    String cleanCode = extractClojureCode(code);
                    Log.d(TAG, "Extracted clean code. Length: " +
                            (cleanCode != null ? cleanCode.length() : "null"));

                    generationFuture.complete(cleanCode);
                }).exceptionally(ex -> {
                    generationFuture.completeExceptionally(ex);
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Error generating code", e);
                generationFuture.completeExceptionally(e);
            }
        });

        return generationFuture;
    }

    public CompletableFuture<IterationResult> runIteration(String code) {
        // Cancel any previous running tasks to avoid resource conflicts
        if (renderFuture != null && !renderFuture.isDone()) {
            renderFuture.cancel(true);
        }

        // Create a new future for this iteration
        renderFuture = new CompletableFuture<>();

        // Run in background thread
        executor.execute(() -> {
            try {
                Log.d(TAG, "Running iteration with code length: " + code.length());

                // Create render intent
                Intent renderIntent = new Intent(context, RenderActivity.class);
                renderIntent.putExtra("code", code);

                // Add the launching activity name - the context is from
                // ClojureAppDesignActivity
                renderIntent.putExtra("launching_activity", ClojureAppDesignActivity.class.getName());

                renderIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(renderIntent);

                // The result will be handled via the broadcast receiver
            } catch (Exception e) {
                Log.e(TAG, "Error running iteration", e);
                renderFuture.completeExceptionally(e);
            }
        });

        return renderFuture.exceptionally(ex -> {
            // Handle cancellations gracefully by returning a valid result
            if (ex instanceof CancellationException) {
                Log.w(TAG, "Iteration was cancelled, creating fallback result");
                return new IterationResult(
                        code,
                        "Iteration was cancelled by a new request",
                        null,
                        false,
                        "Previous operation was cancelled");
            }
            throw new CompletionException(ex);
        });
    }

    public CompletableFuture<String> generateNextIteration(String description, String feedback,
            IterationResult lastResult) {
        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        Log.d(TAG, "Starting new code generation with description: " + description + ", feedback: " + feedback);

        // Create a new future for this generation
        generationFuture = new CompletableFuture<>();

        // Store the result for future reference
        this.lastResult = lastResult;

        // Run in background thread
        executor.execute(() -> {
            try {
                // Call the LLM client which returns a CompletableFuture - PASS DESCRIPTION HERE
                CompletableFuture<String> llmFuture = llmClient.generateNextIteration(
                        description, // Pass the original description now
                        lastResult.code,
                        lastResult.logcat,
                        lastResult.screenshot,
                        feedback);

                // When the LLM response is ready, complete our future
                llmFuture.thenAccept(code -> {
                    Log.d(TAG, "Received code response from LLM, length: " +
                            (code != null ? code.length() : "null"));
                    Log.d(TAG, "LLM response first 100 chars: " +
                            (code != null && code.length() > 100 ? code.substring(0, 100) : code));

                    // Extract clean code from markdown blocks if present
                    String cleanCode = extractClojureCode(code);
                    Log.d(TAG, "Extracted clean code. Length: " +
                            (cleanCode != null ? cleanCode.length() : "null"));

                    generationFuture.complete(cleanCode);
                }).exceptionally(ex -> {
                    generationFuture.completeExceptionally(ex);
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Error generating next iteration", e);
                generationFuture.completeExceptionally(e);
            }
        });

        return generationFuture;
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

    public void shutdown() {
        // Unregister receiver
        try {
            context.unregisterReceiver(renderCompletionReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }

        // Shutdown executor
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Proceed with other cleanup
        logcatMonitor.shutdown();
        screenshotManager.clearScreenshots();
    }

    public LLMClient getLLMClient() {
        return llmClient;
    }
}
