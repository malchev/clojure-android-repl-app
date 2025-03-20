package com.example.clojurerepl;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.net.Uri;
import android.util.Log;

public class ClojureAppDesignActivity extends AppCompatActivity {
    private static final String TAG = "ClojureAppDesign";

    private EditText appDescriptionInput;
    private Button generateButton;
    private TextView currentCodeView;
    private EditText feedbackInput;
    private Button submitFeedbackButton;
    private ImageView screenshotView;
    private TextView logcatOutput;
    private Button confirmSuccessButton;

    private ClojureIterationManager iterationManager;
    private String currentDescription;
    private String currentCode;
    private int iterationCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clojure_design);

        // Initialize views
        appDescriptionInput = findViewById(R.id.app_description_input);
        generateButton = findViewById(R.id.generate_button);
        currentCodeView = findViewById(R.id.current_code_view);
        feedbackInput = findViewById(R.id.feedback_input);
        submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        screenshotView = findViewById(R.id.screenshot_view);
        logcatOutput = findViewById(R.id.logcat_output);
        confirmSuccessButton = findViewById(R.id.confirm_success_button);

        // Initialize manager with a concrete LLM implementation
        iterationManager = new ClojureIterationManager(this, createLLMClient());

        // Set up click listeners
        generateButton.setOnClickListener(v -> startNewDesign());
        submitFeedbackButton.setOnClickListener(v -> submitFeedback());
        confirmSuccessButton.setOnClickListener(v -> confirmSuccess());
    }

    private LLMClient createLLMClient() {
        return new StubLLMClient(this);
    }

    private void startNewDesign() {
        String description = appDescriptionInput.getText().toString();
        if (description.isEmpty()) {
            appDescriptionInput.setError("Please enter a description");
            return;
        }

        currentDescription = description;
        iterationCount = 0;

        // Show loading state
        generateButton.setEnabled(false);

        iterationManager.generateCode(description)
            .thenCompose(code -> {
                currentCode = code;
                runOnUiThread(() -> currentCodeView.setText(code));
                return iterationManager.runIteration(code);
            })
            .thenAccept(this::handleIterationResult)
            .exceptionally(throwable -> {
                Log.e(TAG, "Error in design flow", throwable);
                runOnUiThread(() -> {
                    generateButton.setEnabled(true);
                    // Show error to user
                });
                return null;
            });
    }

    private void handleIterationResult(ClojureIterationManager.IterationResult result) {
        runOnUiThread(() -> {
            // Update UI with results
            logcatOutput.setText(result.logcat);
            if (result.screenshot != null && result.screenshot.exists()) {
                screenshotView.setImageURI(Uri.fromFile(result.screenshot));
            }

            // Show feedback UI
            feedbackInput.setVisibility(View.VISIBLE);
            submitFeedbackButton.setVisibility(View.VISIBLE);
            confirmSuccessButton.setVisibility(View.VISIBLE);

            generateButton.setEnabled(true);
        });
    }

    private void submitFeedback() {
        String feedback = feedbackInput.getText().toString();
        if (feedback.isEmpty()) {
            feedbackInput.setError("Please enter feedback");
            return;
        }

        iterationCount++;
        submitFeedbackButton.setEnabled(false);
        confirmSuccessButton.setEnabled(false);

        ClojureIterationManager.IterationResult lastResult =
            iterationManager.getLastResult();

        iterationManager.generateNextIteration(feedback, lastResult)
            .thenCompose(code -> {
                currentCode = code;
                runOnUiThread(() -> currentCodeView.setText(code));
                return iterationManager.runIteration(code);
            })
            .thenAccept(this::handleIterationResult)
            .exceptionally(throwable -> {
                Log.e(TAG, "Error processing feedback", throwable);
                runOnUiThread(() -> {
                    submitFeedbackButton.setEnabled(true);
                    confirmSuccessButton.setEnabled(true);
                    // Show error to user
                });
                return null;
            });
    }

    private void confirmSuccess() {
        // Handle successful completion
        // Maybe save the final code somewhere?
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (iterationManager != null) {
            iterationManager.cleanup();
        }
    }
}
