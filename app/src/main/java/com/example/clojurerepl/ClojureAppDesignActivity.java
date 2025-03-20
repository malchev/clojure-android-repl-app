package com.example.clojurerepl;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import com.example.clojurerepl.auth.ApiKeyManager;
import android.view.Menu;
import android.view.MenuItem;
import android.app.ProgressDialog;
import android.os.Handler;
import android.content.ComponentName;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.widget.ListView;

public class ClojureAppDesignActivity extends AppCompatActivity {
    private static final String TAG = "ClojureAppDesign";

    private EditText appDescriptionInput;
    private Button generateButton;
    private TextView currentCodeView;
    private EditText feedbackInput;
    private ImageView screenshotView;
    private TextView logcatOutput;
    private LinearLayout feedbackButtonsContainer;
    private Button thumbsUpButton;
    private Button thumbsDownButton;
    private Button runButton;

    // Legacy buttons
    private Button submitFeedbackButton;
    private Button confirmSuccessButton;

    // Add a field for the clear chat history button
    private Button clearChatHistoryButton;

    private ClojureIterationManager iterationManager;
    private String currentDescription;
    private String currentCode;
    private int iterationCount = 0;

    // Add a field for the screenshots container
    private LinearLayout screenshotsContainer;

    // Add field to store current screenshots
    private List<File> currentScreenshots = new ArrayList<>();

    // Add a field to store the process logcat
    private String processLogcat = "";

    // Add LLM type spinner
    private Spinner llmTypeSpinner;
    private Spinner geminiModelSpinner;
    private Button clearApiKeyButton;
    private LLMClientFactory.LLMType currentLLMType = LLMClientFactory.LLMType.GEMINI;

    // Add this field at the top of the class
    private AlertDialog apiKeyDialog;

    // Add a class field to store the prefilled feedback
    private String lastErrorFeedback = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clojure_design);

        // Initialize views
        appDescriptionInput = findViewById(R.id.app_description_input);
        generateButton = findViewById(R.id.generate_button);
        currentCodeView = findViewById(R.id.current_code_view);
        feedbackInput = findViewById(R.id.feedback_input);
        screenshotView = findViewById(R.id.screenshot_view);
        logcatOutput = findViewById(R.id.logcat_output);

        // New feedback UI
        feedbackButtonsContainer = findViewById(R.id.feedback_buttons_container);
        thumbsUpButton = findViewById(R.id.thumbs_up_button);
        thumbsDownButton = findViewById(R.id.thumbs_down_button);
        runButton = findViewById(R.id.run_button);

        // Legacy buttons to maintain compatibility
        submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        confirmSuccessButton = findViewById(R.id.confirm_success_button);

        // Add LLM type spinner
        llmTypeSpinner = findViewById(R.id.llm_type_spinner);
        ArrayAdapter<LLMClientFactory.LLMType> llmAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                LLMClientFactory.LLMType.values());
        llmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        llmTypeSpinner.setAdapter(llmAdapter);

        // Initialize clear API key button
        clearApiKeyButton = findViewById(R.id.clear_api_key_button);
        clearApiKeyButton.setOnClickListener(v -> clearCurrentApiKey());

        // Initialize clear chat history button
        clearChatHistoryButton = findViewById(R.id.clear_chat_history_button);
        clearChatHistoryButton.setOnClickListener(v -> clearChatHistory());

        // Set default selection to GEMINI
        llmTypeSpinner.setSelection(
                Arrays.asList(LLMClientFactory.LLMType.values()).indexOf(LLMClientFactory.LLMType.GEMINI));

        llmTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LLMClientFactory.LLMType selectedType = (LLMClientFactory.LLMType) parent.getItemAtPosition(position);
                ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(ClojureAppDesignActivity.this);

                if (selectedType == LLMClientFactory.LLMType.GEMINI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.GEMINI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.GEMINI);
                    } else {
                        updateModelSpinner(LLMClientFactory.LLMType.GEMINI);
                    }
                } else if (selectedType == LLMClientFactory.LLMType.OPENAI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.OPENAI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.OPENAI);
                    } else {
                        updateModelSpinner(LLMClientFactory.LLMType.OPENAI);
                    }
                } else {
                    geminiModelSpinner.setVisibility(View.GONE);
                    iterationManager = new ClojureIterationManager(ClojureAppDesignActivity.this,
                            LLMClientFactory.createClient(ClojureAppDesignActivity.this, selectedType));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Add Gemini model spinner
        geminiModelSpinner = findViewById(R.id.gemini_model_spinner);
        setupGeminiModelSpinnerListener();

        // Initialize manager with initial LLM type (now GEMINI by default)
        // Don't create the manager yet - wait for models to be loaded
        updateModelSpinner(LLMClientFactory.LLMType.GEMINI);

        // Set up click listeners
        generateButton.setOnClickListener(v -> startNewDesign());
        thumbsUpButton.setOnClickListener(v -> acceptApp());
        thumbsDownButton.setOnClickListener(v -> {
            // Make sure we can always show the feedback dialog
            showFeedbackDialog();
        });
        runButton.setOnClickListener(v -> runCurrentCode());

        // Legacy button listeners
        submitFeedbackButton.setOnClickListener(v -> submitFeedback());
        confirmSuccessButton.setOnClickListener(v -> acceptApp());

        // Hide feedback buttons initially - they should only be visible after
        // generation
        feedbackButtonsContainer.setVisibility(View.GONE);

        // Get the screenshots container
        screenshotsContainer = findViewById(R.id.screenshots_container);

        appDescriptionInput.setText(
                "Create an app that implements Conway's Game of Life. It's in the form of a 20x20 grid. Each square of the grid is tappable, and when tapped, it switches colors between white (dead) and black (alive). There are three buttons beneath the grid: play, stop, and step. Play runs the game with a delay of half a second between steps until the grid turns all white. Stop stops a play run. Step does a single iteration of the grid state.");
    }

    private void startNewDesign() {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEW APP DESIGN           ║\n" +
                "╚═══════════════════════════════════════════╝");

        String description = appDescriptionInput.getText().toString();
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
            return;
        }

        generateButton.setEnabled(false);
        currentDescription = description;

        // Get the current LLM type and model
        LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
        String selectedModel = (String) geminiModelSpinner.getSelectedItem();

        // Create LLM client using factory with the selected model
        LLMClient llmClient = LLMClientFactory.createClient(this, currentType, selectedModel);
        iterationManager = new ClojureIterationManager(this, llmClient);

        // Get the LLM to generate the code first
        iterationManager.getLLMClient().generateInitialCode(description)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        currentCode = code;
                        currentCodeView.setText(code);

                        // Show the feedback buttons
                        feedbackButtonsContainer.setVisibility(View.VISIBLE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        thumbsDownButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);

                        // Only launch RenderActivity after we have the code
                        if (currentCode != null && !currentCode.isEmpty()) {
                            Intent intent = new Intent(this, RenderActivity.class);
                            intent.putExtra(RenderActivity.EXTRA_CODE, currentCode);
                            intent.putExtra(RenderActivity.EXTRA_LAUNCHING_ACTIVITY,
                                    ClojureAppDesignActivity.class.getName());
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }

                        generateButton.setEnabled(true);
                        iterationCount = 1;
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error generating code", throwable);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error generating code: " + throwable.getMessage(),
                                Toast.LENGTH_LONG).show();
                        generateButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void handleIterationResult(ClojureIterationManager.IterationResult result) {
        if (result == null)
            return;

        currentCode = result.code;
        currentCodeView.setText(currentCode);

        // Show and enable all feedback buttons
        feedbackButtonsContainer.setVisibility(View.VISIBLE);
        thumbsUpButton.setVisibility(View.VISIBLE);
        thumbsDownButton.setVisibility(View.VISIBLE);
        runButton.setVisibility(View.VISIBLE);
        submitFeedbackButton.setVisibility(View.GONE); // Hide this until feedback is entered
        confirmSuccessButton.setVisibility(View.GONE); // Hide this until needed

        // Enable all buttons
        thumbsUpButton.setEnabled(true);
        thumbsDownButton.setEnabled(true);
        runButton.setEnabled(true);
        generateButton.setEnabled(true);

        // Update logcat output if available
        if (result.logcat != null) {
            logcatOutput.setText(result.logcat);
        }

        // Handle screenshot if available
        if (result.screenshot != null) {
            displayScreenshot(result.screenshot);
        }

        // Increment iteration count
        iterationCount++;
    }

    private void showFeedbackDialog() {
        showFeedbackDialog(null);
    }

    private void submitFeedbackWithText(String feedback) {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEXT ITERATION           ║\n" +
                "╚═══════════════════════════════════════════╝");

        // Clear stored error feedback since we're starting a new iteration
        lastErrorFeedback = null;

        // Make sure buttons are enabled
        thumbsUpButton.setEnabled(true);
        thumbsDownButton.setEnabled(true);
        runButton.setEnabled(true);

        // Get the current screenshot if any
        File currentScreenshot = null;
        if (!currentScreenshots.isEmpty()) {
            currentScreenshot = currentScreenshots.get(currentScreenshots.size() - 1);
        }

        // Get the current logcat output
        String logcatText = logcatOutput.getText().toString();

        // Generate next iteration
        iterationManager.getLLMClient().generateNextIteration(
                currentDescription,
                currentCode,
                logcatText,
                currentScreenshot,
                feedback)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        currentCode = code;
                        currentCodeView.setText(code);

                        // Make sure buttons are enabled after response
                        thumbsUpButton.setEnabled(true);
                        thumbsDownButton.setEnabled(true);
                        runButton.setEnabled(true);

                        // Launch RenderActivity with new code
                        Intent intent = new Intent(this, RenderActivity.class);
                        intent.putExtra(RenderActivity.EXTRA_CODE, code);
                        intent.putExtra(RenderActivity.EXTRA_LAUNCHING_ACTIVITY,
                                ClojureAppDesignActivity.class.getName());
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error generating next iteration", throwable);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error generating next iteration: " + throwable.getMessage(),
                                Toast.LENGTH_LONG).show();
                        // Make sure buttons are enabled on error
                        thumbsUpButton.setEnabled(true);
                        thumbsDownButton.setEnabled(true);
                        runButton.setEnabled(true);
                    });
                    return null;
                });
    }

    // Legacy method, now calls the new implementation
    private void submitFeedback() {
        if (iterationManager == null || currentDescription == null || currentCode == null) {
            Toast.makeText(this, "No active design session", Toast.LENGTH_SHORT).show();
            return;
        }

        String feedback = feedbackInput.getText().toString();
        if (feedback.isEmpty()) {
            feedbackInput.setError("Please enter feedback");
            return;
        }

        // Increment iteration counter
        iterationCount++;

        Toast.makeText(this, "Generating next iteration...", Toast.LENGTH_SHORT).show();

        // Create result from current state
        ClojureIterationManager.IterationResult result = new ClojureIterationManager.IterationResult(
                currentCode,
                processLogcat,
                currentScreenshots.isEmpty() ? null : currentScreenshots.get(0),
                true,
                feedback);

        // Generate next iteration
        iterationManager.generateNextIteration(currentDescription, feedback, result)
                .thenAccept(cleanCode -> {
                    Log.d(TAG, "Generated next iteration code: "
                            + (cleanCode != null ? "length=" + cleanCode.length() : "null"));

                    runOnUiThread(() -> {
                        if (cleanCode != null && !cleanCode.isEmpty()) {
                            // Update UI with new code
                            currentCode = cleanCode;
                            currentCodeView.setText(cleanCode);
                            feedbackInput.setText("");

                            // Auto-save the new iteration
                            saveCodeToFile();

                            // Run the new code
                            runCurrentCode();
                        } else {
                            Toast.makeText(ClojureAppDesignActivity.this,
                                    "Failed to generate next iteration", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .exceptionally(e -> {
                    Log.e(TAG, "Error generating next iteration", e);
                    runOnUiThread(() -> {
                        Toast.makeText(ClojureAppDesignActivity.this,
                                "Error generating next iteration: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                    return null;
                });
    }

    private void acceptApp() {
        // Re-enable buttons after accepting
        thumbsUpButton.setEnabled(true);
        thumbsDownButton.setEnabled(true);
        runButton.setEnabled(true);

        // Here we encode the current code using base64 before sending
        if (currentCode.isEmpty()) {
            Toast.makeText(this, "No code to accept", Toast.LENGTH_SHORT).show();
            return;
        }

        // Base64 encode the code
        String encodedCode;
        try {
            byte[] bytes = currentCode.getBytes("UTF-8");
            encodedCode = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Error encoding code to base64", e);
            Toast.makeText(this, "Error encoding code: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        // Send to REPL activity
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(RenderActivity.EXTRA_CODE, encodedCode);
        intent.putExtra(RenderActivity.EXTRA_ENCODING, "base64");
        intent.putExtra(RenderActivity.EXTRA_DESCRIPTION, currentDescription);
        startActivity(intent);

        // Or broadcast for background use
        Intent broadcastIntent = new Intent(ClojureCodeReceiver.ACTION_EVAL_CODE);
        broadcastIntent.setComponent(new ComponentName(
                "com.example.clojurerepl",
                "com.example.clojurerepl.ClojureCodeReceiver"));
        broadcastIntent.putExtra(RenderActivity.EXTRA_CODE, encodedCode);
        broadcastIntent.putExtra(RenderActivity.EXTRA_ENCODING, "base64");
        broadcastIntent.putExtra(RenderActivity.EXTRA_DESCRIPTION, currentDescription);
        sendBroadcast(broadcastIntent);

        // Save this final version
        saveCodeToFile();

        Toast.makeText(this, "Code accepted and sent to REPL", Toast.LENGTH_LONG).show();

        // Optional: Clear state or continue
        // finish(); // Close this activity
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (iterationManager != null) {
            iterationManager.shutdown();
        }

        if (apiKeyDialog != null && apiKeyDialog.isShowing()) {
            apiKeyDialog.dismiss();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);

        // First check for screenshot data (existing functionality)
        if (intent.hasExtra(RenderActivity.EXTRA_SCREENSHOT_PATHS)) {
            String[] screenshotPaths = intent.getStringArrayExtra(RenderActivity.EXTRA_SCREENSHOT_PATHS);
            Log.d(TAG, "Received " + screenshotPaths.length + " screenshots in onNewIntent");

            // Save the screenshots for future reference
            currentScreenshots.clear();
            for (String path : screenshotPaths) {
                currentScreenshots.add(new File(path));
            }

            // Display all screenshots
            displayScreenshots(screenshotPaths);

            // Save the current code when returning from RenderActivity
            if (currentCode != null && !currentCode.isEmpty()) {
                saveCodeToFile();
            }
        }

        // Check if we have process logcat data
        if (intent.hasExtra(RenderActivity.EXTRA_PROCESS_LOGCAT)) {
            processLogcat = intent.getStringExtra(RenderActivity.EXTRA_PROCESS_LOGCAT);
            Log.d(TAG, "Received process logcat of length: " + processLogcat.length());

            // Update the logcat output view if it exists
            if (logcatOutput != null) {
                logcatOutput.setText(processLogcat);
            }
        }

        // Check for error feedback from RenderActivity
        if (intent.hasExtra(RenderActivity.EXTRA_ERROR)) {
            String errorFeedback = intent.getStringExtra(RenderActivity.EXTRA_ERROR);

            // Pre-fill the feedback input
            if (feedbackInput != null) {
                feedbackInput.setText(errorFeedback);
            }

            // Show the feedback dialog
            showFeedbackDialog(errorFeedback);
        }
    }

    // Move screenshot display logic to a separate method
    private void displayScreenshot(File screenshotFile) {
        if (screenshotFile == null) {
            Log.d(TAG, "Screenshot file is null");
            return;
        }

        Log.d(TAG, "Displaying screenshot: " + screenshotFile.getAbsolutePath() +
                " exists: " + screenshotFile.exists());

        try {
            if (screenshotFile.exists()) {
                // Load bitmap directly
                Bitmap bitmap = BitmapFactory.decodeFile(screenshotFile.getAbsolutePath());

                if (bitmap != null) {
                    screenshotView.setImageBitmap(bitmap);
                    screenshotView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Successfully loaded screenshot bitmap, dimensions: "
                            + bitmap.getWidth() + "x" + bitmap.getHeight());
                } else {
                    Log.e(TAG, "Failed to decode bitmap from: " + screenshotFile.getAbsolutePath());
                }
            } else {
                Log.e(TAG, "Screenshot file doesn't exist: " + screenshotFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying screenshot", e);
        }
    }

    // New method to display multiple screenshots
    private void displayScreenshots(String[] screenshotPaths) {
        // Clear existing screenshots
        screenshotsContainer.removeAllViews();

        if (screenshotPaths == null || screenshotPaths.length == 0) {
            Log.d(TAG, "No screenshots to display");
            return;
        }

        Log.d(TAG, "Displaying " + screenshotPaths.length + " screenshots");

        // Add each screenshot to the container
        for (String path : screenshotPaths) {
            File screenshotFile = new File(path);
            if (screenshotFile.exists()) {
                try {
                    // Create an ImageView for each screenshot
                    ImageView imageView = new ImageView(this);
                    imageView.setLayoutParams(new LinearLayout.LayoutParams(
                            dpToPx(180), // Fixed width per screenshot
                            LinearLayout.LayoutParams.MATCH_PARENT));
                    imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    imageView.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));

                    // Load bitmap directly
                    Bitmap bitmap = BitmapFactory.decodeFile(screenshotFile.getAbsolutePath());
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                        screenshotsContainer.addView(imageView);

                        Log.d(TAG, "Added screenshot " + screenshotFile.getName() +
                                " to reel, dimensions: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error displaying screenshot " + path, e);
                }
            } else {
                Log.e(TAG, "Screenshot file doesn't exist: " + path);
            }
        }
    }

    // Helper method to convert dp to pixels
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void updateModelSpinner(LLMClientFactory.LLMType type) {
        // Show a progress indicator
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Fetching available models...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Wait a moment to ensure the models are fetched
        new Handler().postDelayed(() -> {
            CompletableFuture.supplyAsync(() -> {
                Log.d(TAG, "Fetching available models from factory for type: " + type);
                ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(this);
                if (!apiKeyManager.hasApiKey(type)) {
                    Log.w(TAG, "No API key available for type: " + type);
                    return new ArrayList<String>();
                }
                return LLMClientFactory.getAvailableModels(this, type);
            }).thenAccept(models -> runOnUiThread(() -> {
                progressDialog.dismiss();

                if (models.isEmpty()) {
                    Log.w(TAG, "No models available for type: " + type);
                    geminiModelSpinner.setVisibility(View.GONE);
                    return;
                }

                ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, models);
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                geminiModelSpinner.setAdapter(modelAdapter);
                geminiModelSpinner.setVisibility(View.VISIBLE);

                // Create new iteration manager with the first available model
                String selectedModel = models.get(0);
                Log.d(TAG, "Selecting model: " + selectedModel);
                iterationManager = new ClojureIterationManager(this,
                        LLMClientFactory.createClient(this, type, selectedModel));
            })).exceptionally(e -> {
                progressDialog.dismiss();
                Log.e(TAG, "Error updating model spinner", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to fetch models: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    geminiModelSpinner.setVisibility(View.GONE);
                });
                return null;
            });
        }, 500); // Short delay to ensure API key is saved first
    }

    // Update the model spinner listener to use factory
    private void setupGeminiModelSpinnerListener() {
        geminiModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedModel = (String) parent.getItemAtPosition(position);
                Log.d(TAG, "User selected model: " + selectedModel);
                LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
                iterationManager = new ClojureIterationManager(
                        ClojureAppDesignActivity.this,
                        LLMClientFactory.createClient(ClojureAppDesignActivity.this, currentType, selectedModel));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.d(TAG, "No model selected");
            }
        });
    }

    private void showApiKeyDialog(LLMClientFactory.LLMType type) {
        Log.d(TAG, "Showing API key dialog for type: " + type);
        String serviceUrl = type == LLMClientFactory.LLMType.GEMINI
                ? "https://makersuite.google.com/app/apikey"
                : "https://platform.openai.com/api-keys";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter " + type.name() + " API Key");

        // Message with clickable link to get API key
        TextView messageView = new TextView(this);
        messageView.setText("Get your API key from: " + serviceUrl);
        messageView.setMovementMethod(LinkMovementMethod.getInstance());
        messageView.setPadding(50, 20, 50, 50);

        // Input field for API key
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Paste your API key here");

        // Add views to layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(messageView);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String apiKey = input.getText().toString().trim();
            if (!apiKey.isEmpty()) {
                ApiKeyManager.getInstance(this).saveApiKey(apiKey, type);
                Toast.makeText(this, type.name() + " API key saved", Toast.LENGTH_SHORT).show();

                // Show progress while fetching models
                ProgressDialog progressDialog = new ProgressDialog(this);
                progressDialog.setMessage("Fetching available models...");
                progressDialog.setCancelable(false);
                progressDialog.show();

                // Use a background thread to fetch models
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return LLMClientFactory.getAvailableModels(this, type);
                    } catch (Exception e) {
                        Log.e(TAG, "Error fetching models", e);
                        return new ArrayList<String>();
                    }
                }).thenAccept(models -> {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();

                        if (models.isEmpty()) {
                            Toast.makeText(this, "Failed to fetch models. Using fallback models.", Toast.LENGTH_SHORT)
                                    .show();
                        }

                        // Update model spinner with fetched models
                        updateModelSpinner(type);
                    });
                }).exceptionally(e -> {
                    Log.e(TAG, "Error in model fetching", e);
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(this, "Failed to fetch models: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        // Still try to update spinner with fallback models
                        updateModelSpinner(type);
                    });
                    return null;
                });
            } else {
                Toast.makeText(this, "API key cannot be empty", Toast.LENGTH_SHORT).show();
                showApiKeyDialog(type); // Show dialog again if empty
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            llmTypeSpinner.setSelection(0); // Reset to first option
        });

        builder.setCancelable(false); // Prevent dismiss by back button
        builder.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        menu.add(0, R.id.action_clear_session, 0, "Clear Chat Session")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.action_clear_api_key, 0, "Clear API Key")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_api_key) {
            LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
            showApiKeyDialog(currentType);
            return true;
        } else if (id == R.id.action_save_code) {
            saveCodeToFile();
            return true;
        } else if (id == R.id.action_clear_api_key) {
            clearCurrentApiKey();
            return true;
        } else if (id == R.id.action_clear_session) {
            clearChatHistory();
            return true;
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveCodeToFile() {
        if (currentCode == null || currentCode.isEmpty()) {
            Log.d(TAG, "No code to save");
            return;
        }

        try {
            File codeDir = new File(getExternalFilesDir(null), "code");
            if (!codeDir.exists()) {
                codeDir.mkdirs();
            }

            // Create a timestamped filename with iteration number
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = "design_" + timestamp + "_iter" + iterationCount + ".clj";
            File codeFile = new File(codeDir, filename);

            java.io.FileWriter writer = new java.io.FileWriter(codeFile);
            writer.write(currentCode);
            writer.close();

            // Also always write to a fixed location for easy scripts
            File latestFile = new File(codeDir, "latest_design.clj");
            writer = new java.io.FileWriter(latestFile);
            writer.write(currentCode);
            writer.close();

            Log.d(TAG, "Code saved to: " + codeFile.getAbsolutePath() + " (Iteration " + iterationCount + ")");
            // Only show toast for manual saves, not automatic ones
        } catch (Exception e) {
            Log.e(TAG, "Error saving code to file", e);
        }
    }

    /**
     * Runs the current code without accepting it
     */
    private void runCurrentCode() {
        if (currentCode == null || currentCode.isEmpty()) {
            Toast.makeText(this, "No code to run", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create intent for RenderActivity
        Intent intent = new Intent(this, RenderActivity.class);
        intent.putExtra(RenderActivity.EXTRA_CODE, currentCode);
        intent.putExtra(RenderActivity.EXTRA_LAUNCHING_ACTIVITY, ClojureAppDesignActivity.class.getName());

        // Add flags to ensure new process
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Start the activity
        startActivity(intent);
    }

    private void clearChatSession() {
        if (iterationManager != null) {
            LLMClient llmClient = iterationManager.getLLMClient();
            if (llmClient != null) {
                llmClient.getOrCreateSession(currentDescription).reset();
            }
        }
    }

    public String getSelectedModel() {
        if (geminiModelSpinner != null) {
            return (String) geminiModelSpinner.getSelectedItem();
        }
        return null;
    }

    /**
     * Clears the API key for the currently selected LLM type
     */
    private void clearCurrentApiKey() {
        if (iterationManager == null || iterationManager.getLLMClient() == null) {
            Toast.makeText(this, "No active LLM client", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the current LLM type for the message
        LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();

        // Show confirmation dialog
        new AlertDialog.Builder(this)
                .setTitle("Clear API Key")
                .setMessage("Are you sure you want to clear the API key for " + currentType.name() + "?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Clear the key using the current client
                    boolean success = iterationManager.getLLMClient().clearApiKey();

                    if (success) {
                        Toast.makeText(this, currentType.name() + " API key cleared", Toast.LENGTH_SHORT).show();

                        // Reset the model spinner
                        geminiModelSpinner.setVisibility(View.GONE);

                        // Show the API key dialog to set a new key
                        showApiKeyDialog(currentType);
                    } else {
                        Toast.makeText(this, "Failed to clear API key", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    /**
     * Shows the feedback dialog, optionally with pre-filled error text
     */
    private void showFeedbackDialog(String prefilledFeedback) {
        // Store in a final variable for lambda usage
        final String finalFeedback;

        if (prefilledFeedback != null && !prefilledFeedback.isEmpty()) {
            // Store prefilled feedback for future dialog displays
            lastErrorFeedback = prefilledFeedback;
            finalFeedback = prefilledFeedback;
        } else if (lastErrorFeedback != null) {
            // Use stored feedback if available
            finalFeedback = lastErrorFeedback;
        } else {
            finalFeedback = null;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Provide Feedback");

        // Create array of feedback options
        final String[] options = {
                "There is a Clojure compilation error. Check attached logcat",
                "There are mismatched parentheses or square brackets. Check attached logcat",
                "App compiles but fails with a runtime error. Check attached logcat",
                "Nothing shows up on the screen. Check attached logcat",
                "Custom feedback...", // Always keep as second-to-last option
                "Use the error feedback below..." // Always keep as last option when error exists
        };

        // Determine if we have prefilled feedback to display
        boolean hasFeedback = finalFeedback != null && !finalFeedback.isEmpty();

        // Create list adapter for dialog
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, options);

        // Build dialog
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_feedback, null);
        ListView listView = dialogView.findViewById(R.id.feedback_options_list);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // Add error text view if we have feedback
        if (hasFeedback) {
            TextView errorTextView = dialogView.findViewById(R.id.error_text);
            errorTextView.setText(finalFeedback);
            errorTextView.setVisibility(View.VISIBLE);
            errorTextView.setTextIsSelectable(true);
        } else {
            dialogView.findViewById(R.id.error_container).setVisibility(View.GONE);
        }

        // Set up dialog
        builder.setView(dialogView);
        builder.setPositiveButton("Submit", null); // We'll override this below
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        // Create and show dialog
        final AlertDialog dialog = builder.create();
        dialog.show();

        // Set up item selection behavior
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // Enable Submit button once an item is selected
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        });

        // Override the positive button to handle submission
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selectedPosition = listView.getCheckedItemPosition();
            Log.d(TAG, "Submit clicked with selection: " + selectedPosition);

            if (selectedPosition == ListView.INVALID_POSITION) {
                Toast.makeText(this, "Please select an option", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedPosition == options.length - 1 && hasFeedback) {
                // "Use the error feedback below..." option (only available when there's
                // feedback)
                submitFeedbackWithText(finalFeedback);
                dialog.dismiss();
            } else if (selectedPosition == options.length - 2) {
                // "Custom feedback..." option
                dialog.dismiss();
                showCustomFeedbackDialog(hasFeedback ? finalFeedback : null);
            } else {
                // Standard options
                submitFeedbackWithText(options[selectedPosition]);
                dialog.dismiss();
            }
        });

        // Disable submit button until an option is selected
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    /**
     * Shows a dialog for entering custom feedback, optionally with pre-filled text
     */
    private void showCustomFeedbackDialog(String prefilledFeedback) {
        AlertDialog.Builder customBuilder = new AlertDialog.Builder(this);
        customBuilder.setTitle("Enter Feedback");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(5);

        // Pre-fill the input if text is provided
        if (prefilledFeedback != null && !prefilledFeedback.isEmpty()) {
            input.setText(prefilledFeedback);
        }

        customBuilder.setView(input);

        customBuilder.setPositiveButton("Submit", (dialogInterface, i) -> {
            String feedback = input.getText().toString();
            if (!feedback.isEmpty()) {
                submitFeedbackWithText(feedback);
            }
        });
        customBuilder.setNegativeButton("Cancel", (dialogInterface, i) -> dialogInterface.cancel());

        customBuilder.show();
    }

    /**
     * Clears the chat history for the current LLM client session and reinitializes
     * with system prompt
     */
    private void clearChatHistory() {
        if (iterationManager == null || iterationManager.getLLMClient() == null) {
            Toast.makeText(this, "No active LLM client", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show confirmation dialog
        new AlertDialog.Builder(this)
                .setTitle("Clear Chat History")
                .setMessage(
                        "Are you sure you want to clear the chat history? This will reset the conversation with the AI but keep your current code.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Check if we have description to reinitialize
                    if (currentDescription == null || currentDescription.isEmpty()) {
                        Toast.makeText(this, "No active description to reinitialize with", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Get the LLM client and reset its session
                    LLMClient llmClient = iterationManager.getLLMClient();
                    if (llmClient != null) {
                        // Reinitialize the session with the current description
                        // This will automatically add the system prompt
                        Log.d(TAG, "Clearing chat history and reinitializing with system prompt");
                        llmClient.preparePromptForInitialCode(currentDescription);

                        Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed to clear chat history", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }
}
