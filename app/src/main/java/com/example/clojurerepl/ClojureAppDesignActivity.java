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
    private LLMClientFactory.LLMType currentLLMType = LLMClientFactory.LLMType.GEMINI;

    // Add this field at the top of the class
    private AlertDialog apiKeyDialog;

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
                        showApiKeyDialog();
                    } else {
                        updateModelSpinner(LLMClientFactory.LLMType.GEMINI);
                    }
                } else if (selectedType == LLMClientFactory.LLMType.OPENAI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.OPENAI)) {
                        showApiKeyDialog();
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
                "Create a game of life app. It's in the form of a 50x50 grid. Each square of the grid is tappable, and when tapped, it switches colors between white (dead) and black (alive). There are three buttons beneath the grid: play, stop, and step. Play runs the game with a delay of half a second between steps until the grid turns all white. Stop stops a play run. Step does a single iteration of the grid state.");
    }

    private void startNewDesign() {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEW APP DESIGN           ║\n" +
                "║            ITERATION   1                  ║\n" +
                "╚═══════════════════════════════════════════╝");

        String description = appDescriptionInput.getText().toString();
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
            return;
        }

        generateButton.setEnabled(false);
        currentDescription = description;

        // Create LLM client using factory
        LLMClient llmClient = LLMClientFactory.createClient(this, LLMClientFactory.LLMType.GEMINI);
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
                            intent.putExtra("code", currentCode);
                            intent.putExtra("launching_activity", ClojureAppDesignActivity.class.getName());
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Needs Work");

        // Create array of feedback options
        final String[] options = {
                "There is a Clojure compilation error. Check attached logcat",
                "There are mismatched parentheses or square brackets. Check attached logcat",
                "App compiles but fails with a runtime error. Check attached logcat",
                "Nothing shows up on the screen. Check attached logcat",
                "Custom feedback..." // Keep the custom feedback option
        };

        builder.setItems(options, (dialog, which) -> {
            if (which == options.length - 1) {
                // Last option - show custom feedback dialog
                AlertDialog.Builder customBuilder = new AlertDialog.Builder(this);
                customBuilder.setTitle("Enter Feedback");

                final EditText input = new EditText(this);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setMinLines(3);
                input.setMaxLines(5);
                customBuilder.setView(input);

                customBuilder.setPositiveButton("Submit", (dialogInterface, i) -> {
                    String feedback = input.getText().toString();
                    if (!feedback.isEmpty()) {
                        submitFeedbackWithText(feedback);
                    }
                });
                customBuilder.setNegativeButton("Cancel", (dialogInterface, i) -> dialogInterface.cancel());

                customBuilder.show();
            } else {
                // Selected a predefined option
                submitFeedbackWithText(options[which]);
            }
        });

        builder.show();
    }

    private void submitFeedbackWithText(String feedback) {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEXT ITERATION           ║\n" +
                "║            ITERATION " + String.format("%3d", (iterationCount + 1)) + "                ║\n" +
                "╚═══════════════════════════════════════════╝");

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
                        intent.putExtra("code", code);
                        intent.putExtra("launching_activity", ClojureAppDesignActivity.class.getName());
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
                .thenAccept(code -> {
                    Log.d(TAG, "Generated next iteration code: " + (code != null ? "length=" + code.length() : "null"));

                    // Extract clean code from the LLM response
                    String cleanCode = extractClojureCode(code);
                    Log.d(TAG,
                            "Extracted clean code: " + (cleanCode != null ? "length=" + cleanCode.length() : "null"));

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
        intent.putExtra("code", encodedCode);
        intent.putExtra("encoding", "base64");
        intent.putExtra("description", currentDescription);
        startActivity(intent);

        // Or broadcast for background use
        Intent broadcastIntent = new Intent("com.example.clojurerepl.EVAL_CODE");
        broadcastIntent.setComponent(new ComponentName(
                "com.example.clojurerepl",
                "com.example.clojurerepl.ClojureCodeReceiver"));
        broadcastIntent.putExtra("code", encodedCode);
        broadcastIntent.putExtra("encoding", "base64");
        broadcastIntent.putExtra("description", currentDescription);
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

        // First check for screenshot data (existing functionality)
        if (intent.hasExtra("screenshot_paths")) {
            String[] screenshotPaths = intent.getStringArrayExtra("screenshot_paths");
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
        // Keep existing single screenshot path handling for compatibility
        else if (intent.hasExtra("screenshot_path")) {
            String screenshotPath = intent.getStringExtra("screenshot_path");
            Log.d(TAG, "Received screenshot path in onNewIntent: " + screenshotPath);

            // Save the screenshot
            currentScreenshots.clear();
            currentScreenshots.add(new File(screenshotPath));

            // Display the screenshot
            displayScreenshot(new File(screenshotPath));

            // Save the current code when returning from RenderActivity
            if (currentCode != null && !currentCode.isEmpty()) {
                saveCodeToFile();
            }
        }

        // Check if we have process logcat data
        if (intent.hasExtra("process_logcat")) {
            processLogcat = intent.getStringExtra("process_logcat");
            Log.d(TAG, "Received process logcat of length: " + processLogcat.length());

            // Update the logcat output view if it exists
            if (logcatOutput != null) {
                logcatOutput.setText(processLogcat);
            }
        }

        // Now check for design code
        if (intent.hasExtra("design_code")) {
            String encodedCode = intent.getStringExtra("design_code");
            String encoding = intent.getStringExtra("encoding");

            // Use the new method to handle design code
            handleDesignCodeIntent(encodedCode, encoding);
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
                return LLMClientFactory.getAvailableModels(this, type);
            }).thenAccept(models -> runOnUiThread(() -> {
                progressDialog.dismiss();

                if (models.isEmpty()) {
                    Log.e(TAG, "No models available after filtering");
                    throw new RuntimeException("No models available after filtering");
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
                        LLMClientFactory.createClient(this, type));
            })).exceptionally(e -> {
                progressDialog.dismiss();
                Log.e(TAG, "Error updating model spinner", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to fetch models: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    throw new RuntimeException("Failed to fetch models from API", e);
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
                        LLMClientFactory.createClient(ClojureAppDesignActivity.this, currentType));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.d(TAG, "No model selected");
            }
        });
    }

    private void showApiKeyDialog() {
        ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(this);
        LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();

        // Check if dialog is already showing
        if (apiKeyDialog != null && apiKeyDialog.isShowing()) {
            // Already showing, no need to create a new one
            return;
        }

        // Create EditText for input
        final EditText input = new EditText(this);
        input.setHint("Enter your " + currentType + " API key");

        // Prefill with existing key if available
        String existingKey = apiKeyManager.getApiKey(currentType);
        if (existingKey != null) {
            input.setText(existingKey);
        }

        // Set layout parameters
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(params);

        // Create alert dialog
        apiKeyDialog = new AlertDialog.Builder(this)
                .setTitle(currentType + " API Key")
                .setMessage(
                        "Please enter your " + currentType + " API key. You can get one from " +
                                (currentType == LLMClientFactory.LLMType.GEMINI
                                        ? "https://makersuite.google.com/app/apikey"
                                        : "https://platform.openai.com/api-keys"))
                .setView(input)
                .setPositiveButton("Save", null) // We'll set the listener after showing the dialog
                .setNegativeButton("Cancel", null) // We'll set the listener after showing the dialog
                .setCancelable(false) // Prevent dismissal by tapping outside or back button
                .create();

        // Show the dialog first
        apiKeyDialog.show();

        // Set button listeners after showing to prevent automatic dismissal
        apiKeyDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String apiKey = input.getText().toString().trim();
            if (!apiKey.isEmpty()) {
                apiKeyManager.saveApiKey(apiKey, currentType);
                Toast.makeText(ClojureAppDesignActivity.this, "API key saved", Toast.LENGTH_SHORT).show();

                // Explicitly dismiss the dialog
                if (apiKeyDialog != null) {
                    apiKeyDialog.dismiss();
                    apiKeyDialog = null;
                }

                // Now update the spinner
                if (currentType == LLMClientFactory.LLMType.GEMINI) {
                    updateModelSpinner(LLMClientFactory.LLMType.GEMINI);
                }
            } else {
                Toast.makeText(ClojureAppDesignActivity.this, "API key cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });

        apiKeyDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            // Switch to stub client
            llmTypeSpinner.setSelection(Arrays.asList(LLMClientFactory.LLMType.values())
                    .indexOf(LLMClientFactory.LLMType.STUB));

            // Explicitly dismiss the dialog
            if (apiKeyDialog != null) {
                apiKeyDialog.dismiss();
                apiKeyDialog = null;
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        menu.add(0, R.id.action_clear_session, 0, "Clear Chat Session")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_api_key) {
            showApiKeyDialog();
            return true;
        } else if (id == R.id.action_save_code) {
            saveCodeToFile();
            return true;
        } else if (id == R.id.action_clear_session) {
            clearChatSession();
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
        intent.putExtra("code", currentCode);
        intent.putExtra("launching_activity", ClojureAppDesignActivity.class.getName());

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

    /**
     * Updates the current code with code loaded from design_code intent
     * Properly extracts Clojure code from markdown code blocks if present
     */
    private void handleDesignCodeIntent(String encodedCode, String encoding) {
        Log.d(TAG, "handleDesignCodeIntent: " + encodedCode + " " + encoding);
        // Create temporary LLM client using factory
        LLMClient tempClient = LLMClientFactory.createClient(this, LLMClientFactory.LLMType.GEMINI);

        // Decode if base64 encoded
        if ("base64".equals(encoding) && encodedCode != null) {
            try {
                byte[] decodedBytes = android.util.Base64.decode(encodedCode, android.util.Base64.DEFAULT);
                String decodedCode = new String(decodedBytes, "UTF-8");
                Log.d(TAG, "Received design code via intent, length: " + decodedCode.length());

                // Extract clean Clojure code from potential markdown code blocks
                String cleanCode = extractClojureCode(decodedCode);

                // Update the current code
                currentCode = cleanCode;
                currentCodeView.setText(cleanCode);

                // Auto-save the received code
                saveCodeToFile();

                Toast.makeText(this, "Design code loaded successfully", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode base64 design code", e);
                Toast.makeText(this, "Error loading design code: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            // Handle non-encoded content
            String cleanCode = extractClojureCode(encodedCode);
            currentCode = cleanCode;
            currentCodeView.setText(cleanCode);
            saveCodeToFile();
            Toast.makeText(this, "Design code loaded successfully", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Extracts clean Clojure code from text that may contain markdown code blocks
     * Properly removes ```clojure and ``` markers, returning ONLY the code between
     * them
     */
    private String extractClojureCode(String input) {
        Log.d(TAG, "Extracting Clojure code from input: " + input);
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

    public String getSelectedModel() {
        if (geminiModelSpinner != null) {
            return (String) geminiModelSpinner.getSelectedItem();
        }
        return null;
    }
}
