package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public abstract class LLMClient {
    private static final String TAG = "LLMClient";
    private static final String PROMPT_TEMPLATE_PATH = "examples/prompt.txt";

    protected final Context context;
    private String promptTemplate;

    public LLMClient(Context context) {
        this.context = context.getApplicationContext();
        loadPromptTemplate();
    }

    private void loadPromptTemplate() {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new FileReader(new File(context.getFilesDir(), PROMPT_TEMPLATE_PATH)));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            promptTemplate = sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Error loading prompt template", e);
            promptTemplate = "";
        }
    }

    protected String formatInitialPrompt(String description) {
        return promptTemplate + "\n\nDesired app description:\n" + description;
    }

    protected String formatIterationPrompt(String description,
                                         String currentCode,
                                         String logcat,
                                         File screenshot,
                                         String feedback) {
        return String.format(
            "%s\n\nCurrent implementation:\n```clojure\n%s\n```\n\n" +
            "Logcat output:\n```\n%s\n```\n\n" +
            "User feedback:\n%s\n\n" +
            "Please provide an improved version addressing the feedback.",
            promptTemplate, currentCode, logcat, feedback);
    }

    public abstract CompletableFuture<String> generateInitialCode(String description);

    public abstract CompletableFuture<String> generateNextIteration(
        String description,
        String currentCode,
        String logcat,
        File screenshot,
        String feedback);
}
