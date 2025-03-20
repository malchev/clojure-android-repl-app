package com.example.clojurerepl;

import android.content.Context;
import java.io.File;
import java.util.concurrent.CompletableFuture;

public class StubLLMClient extends LLMClient {
    public StubLLMClient(Context context) {
        super(context);
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description) {
        // Return a simple test program for now
        String testProgram = "; Simple test program\n" +
                "(let [text-view (doto (new android.widget.TextView *context*)\n" +
                "                  (.setText \"Hello from test program!\")\n" +
                "                  (.setTextSize 24.0))]\n" +
                "  (.addView *content-layout* text-view))";
        return CompletableFuture.completedFuture(testProgram);
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        // Just return the current code with a comment about the feedback
        String nextIteration = "; Feedback received: " + feedback + "\n" + currentCode;
        return CompletableFuture.completedFuture(nextIteration);
    }
}
