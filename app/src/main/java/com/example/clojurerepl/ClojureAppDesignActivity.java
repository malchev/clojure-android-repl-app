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
import com.example.clojurerepl.session.DesignSession;
import com.example.clojurerepl.session.SessionManager;

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

    // Add session support
    private SessionManager sessionManager;
    private DesignSession currentSession;
    private String sessionId;

    // Add a flag to track when the models are loaded
    private boolean modelsLoaded = false;
    private boolean pendingModelSelection = false;
    private String pendingModelName = null;
    // Add a flag to block spinner events during initial loading
    private boolean blockSpinnerEvents = false;
    // Add a field to store the initial model for session restore
    private String sessionRestoreModel = null;
    // Add a flag to lock the client once set up from session
    private boolean lockClient = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clojure_design);

        // Set spinner event blocker during initialization
        blockSpinnerEvents = true;

        // Initialize session manager
        sessionManager = SessionManager.getInstance(this);

        // Check if we're opening an existing session
        sessionId = getIntent().getStringExtra("session_id");
        if (sessionId != null) {
            // Load existing session
            currentSession = sessionManager.getSessionById(sessionId);
            if (currentSession != null) {
                Log.d(TAG, "Loaded existing session: " + currentSession.getId());
            } else {
                Log.e(TAG, "Session not found: " + sessionId);
                Toast.makeText(this, "Session not found", Toast.LENGTH_SHORT).show();
                // Create a new session instead
                currentSession = new DesignSession();
                sessionId = currentSession.getId();
            }
        } else {
            // Create a new session
            currentSession = new DesignSession();
            sessionId = currentSession.getId();
            Log.d(TAG, "Created new session: " + sessionId);
        }

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

                // Ignore selection events during initialization when blockSpinnerEvents is true
                if (blockSpinnerEvents) {
                    Log.d(TAG, "Ignoring LLM type selection event during initialization");
                    return;
                }

                if (selectedType == LLMClientFactory.LLMType.GEMINI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.GEMINI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.GEMINI);
                    } else {
                        // Skip update if client is locked due to session restore
                        if (lockClient) {
                            Log.d(TAG, "Client locked - skipping model spinner update for GEMINI");
                            return;
                        }
                        updateModelSpinner(LLMClientFactory.LLMType.GEMINI);
                    }
                } else if (selectedType == LLMClientFactory.LLMType.OPENAI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.OPENAI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.OPENAI);
                    } else {
                        // Skip update if client is locked due to session restore
                        if (lockClient) {
                            Log.d(TAG, "Client locked - skipping model spinner update for OPENAI");
                            return;
                        }
                        updateModelSpinner(LLMClientFactory.LLMType.OPENAI);
                    }
                } else {
                    // Skip update if client is locked due to session restore
                    if (lockClient) {
                        Log.d(TAG, "Client locked - skipping client creation for other LLM type");
                        return;
                    }
                    geminiModelSpinner.setVisibility(View.GONE);
                    iterationManager = new ClojureIterationManager(ClojureAppDesignActivity.this,
                            LLMClientFactory.createClient(ClojureAppDesignActivity.this, selectedType), sessionId);
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

        // Make sure screenshots container is initially visible
        if (screenshotsContainer != null) {
            screenshotsContainer.setVisibility(View.VISIBLE);
        }

        // Make sure screenshot view is initially visible
        if (screenshotView != null) {
            screenshotView.setVisibility(View.VISIBLE);
        }

        appDescriptionInput.setText(
                "Create an app that implements Conway's Game of Life. It's in the form of a 20x20 grid. Each square of the grid is tappable, and when tapped, it switches colors between white (dead) and black (alive). There are three buttons beneath the grid: play, stop, and step. Play runs the game with a delay of half a second between steps until the grid turns all white. Stop stops a play run. Step does a single iteration of the grid state.");

        // If we loaded an existing session, update the UI
        if (currentSession != null && currentSession.getDescription() != null) {
            appDescriptionInput.setText(currentSession.getDescription());
            currentDescription = currentSession.getDescription();

            if (currentSession.getCurrentCode() != null) {
                currentCode = currentSession.getCurrentCode();
                currentCodeView.setText(currentCode);

                // Show feedback buttons
                feedbackButtonsContainer.setVisibility(View.VISIBLE);
                thumbsUpButton.setVisibility(View.VISIBLE);
                thumbsDownButton.setVisibility(View.VISIBLE);
                runButton.setVisibility(View.VISIBLE);
            }

            // Set the iteration count
            iterationCount = currentSession.getIterationCount();

            // Set the LLM type and model in the UI
            if (currentSession.getLlmType() != null) {
                int position = Arrays.asList(LLMClientFactory.LLMType.values())
                        .indexOf(currentSession.getLlmType());
                if (position >= 0) {
                    llmTypeSpinner.setSelection(position);
                }

                // Set the model if available
                if (currentSession.getLlmModel() != null) {
                    // Save session restore model
                    sessionRestoreModel = currentSession.getLlmModel();

                    // Create the client directly with the saved model
                    Log.d(TAG, "Session restore: Creating LLM client with model " + sessionRestoreModel);
                    LLMClient llmClient = LLMClientFactory.createClient(
                            this,
                            currentSession.getLlmType(),
                            sessionRestoreModel);
                    assert iterationManager == null : "iterationManager should be null before creating new instance";
                    iterationManager = new ClojureIterationManager(this, llmClient, sessionId);

                    // LOCK the client so it cannot be changed by any subsequent operation
                    lockClient = true;

                    // We'll wait until llmTypeSpinner's selection listener fires to trigger model
                    // enumeration
                } else {
                    sessionRestoreModel = null;
                    lockClient = false;
                }
            }

            // Restore logcat output if available
            if (currentSession.getLastLogcat() != null && !currentSession.getLastLogcat().isEmpty()) {
                processLogcat = currentSession.getLastLogcat();
                logcatOutput.setText(processLogcat);
                Log.d(TAG, "Restored logcat output from session");
            }

            // Restore screenshots if available
            if (currentSession.getScreenshotPaths() != null && !currentSession.getScreenshotPaths().isEmpty()) {
                List<String> paths = currentSession.getScreenshotPaths();
                Log.d(TAG, "Restoring " + paths.size() + " screenshots from session");

                // Convert paths to File objects for currentScreenshots
                currentScreenshots.clear();
                List<String> validPaths = new ArrayList<>();

                for (String path : paths) {
                    File screenshotFile = new File(path);
                    if (screenshotFile.exists()) {
                        currentScreenshots.add(screenshotFile);
                        validPaths.add(path);
                        Log.d(TAG, "Found valid screenshot: " + path);
                    } else {
                        Log.w(TAG, "Screenshot file no longer exists: " + path);
                    }
                }

                // Only display screenshots if we have valid ones
                if (!currentScreenshots.isEmpty()) {
                    // Convert to array for display function
                    String[] pathsArray = validPaths.toArray(new String[0]);

                    // Display both in the main view and in the container
                    if (currentScreenshots.size() > 0) {
                        displayScreenshot(currentScreenshots.get(0));
                        Log.d(TAG, "Displayed first screenshot in main view: " + validPaths.get(0));
                    }

                    displayScreenshots(pathsArray);
                    Log.d(TAG, "Displayed " + pathsArray.length + " screenshots in container");
                } else {
                    Log.w(TAG, "No valid screenshots to display");
                }
            }

            // Restore error feedback if available
            if (currentSession.hasError() && currentSession.getLastErrorFeedback() != null) {
                lastErrorFeedback = currentSession.getLastErrorFeedback();
                feedbackInput.setText(lastErrorFeedback);
                Log.d(TAG, "Restored error feedback from session");
            }
        }

        // Reset blockSpinnerEvents after initialization
        blockSpinnerEvents = false;

        // Now that initialization is complete, restore chat history if available
        if (currentSession != null && currentSession.getChatHistory() != null
                && !currentSession.getChatHistory().isEmpty()
                && iterationManager != null) {

            // Get chat session and restore messages
            LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                    .getOrCreateSession(currentSession.getDescription());

            // Reset the session to clear any default messages
            chatSession.reset();

            // Then add all messages from the saved session
            List<LLMClient.Message> savedMessages = currentSession.getChatHistory();
            Log.d(TAG, "Restoring " + savedMessages.size() + " messages to chat session");

            for (LLMClient.Message message : savedMessages) {
                if ("system".equals(message.role)) {
                    chatSession.queueSystemPrompt(message.content);
                } else if ("user".equals(message.role)) {
                    chatSession.queueUserMessage(message.content);
                } else if ("assistant".equals(message.role)) {
                    chatSession.queueAssistantResponse(message.content);
                }
            }

            Log.d(TAG, "Chat history restored");
        }
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
        assert iterationManager == null : "iterationManager should be null before creating new instance";
        iterationManager = new ClojureIterationManager(this, llmClient, sessionId);

        // Update or create session
        if (currentSession == null) {
            currentSession = new DesignSession();
            sessionId = currentSession.getId();
        }

        // Update session data
        currentSession.setDescription(description);
        currentSession.setLlmType(currentType);
        currentSession.setLlmModel(selectedModel);

        // Save session
        sessionManager.addSession(currentSession);

        // Get the LLM to generate the code first - using IterationManager now
        iterationManager.generateInitialCode(description)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        currentCode = code;
                        currentCodeView.setText(code);

                        // Update session with code
                        currentSession.setCurrentCode(code);
                        currentSession.incrementIterationCount();

                        // Save chat history to session
                        LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                                .getOrCreateSession(description);
                        currentSession.setChatHistory(chatSession.getMessages());

                        sessionManager.updateSession(currentSession);

                        // Show the feedback buttons
                        feedbackButtonsContainer.setVisibility(View.VISIBLE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        thumbsDownButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);

                        // Only launch RenderActivity after we have the code
                        if (currentCode != null && !currentCode.isEmpty()) {
                            runCurrentCode();
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

        // Check if models are still loading
        if (pendingModelSelection || !modelsLoaded) {
            Toast.makeText(this, "Models are still loading, please wait a moment and try again", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        // Make sure buttons are enabled
        thumbsUpButton.setEnabled(true);
        thumbsDownButton.setEnabled(true);
        runButton.setEnabled(true);

        // Ensure we have a valid description
        if (currentDescription == null || currentDescription.isEmpty()) {
            currentDescription = appDescriptionInput.getText().toString();
            if (currentDescription.isEmpty()) {
                Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Ensure we have a valid LLM client
        if (iterationManager == null) {
            // Get the current LLM type and model
            LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
            String selectedModel = (String) geminiModelSpinner.getSelectedItem();

            // If no model is selected yet, show error and return
            if (selectedModel == null) {
                Toast.makeText(this, "No LLM model selected. Please wait for models to load.", Toast.LENGTH_SHORT)
                        .show();
                return;
            }

            // Create LLM client using factory with the selected model
            LLMClient llmClient = LLMClientFactory.createClient(this, currentType, selectedModel);
            assert iterationManager == null : "iterationManager should be null before creating new instance";
            iterationManager = new ClojureIterationManager(this, llmClient, sessionId);
        }

        // Ensure we have current code
        if (currentCode == null || currentCode.isEmpty()) {
            Toast.makeText(this, "No code to improve. Please generate initial code first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the current screenshot if any
        File currentScreenshot = null;
        if (!currentScreenshots.isEmpty()) {
            currentScreenshot = currentScreenshots.get(currentScreenshots.size() - 1);
        }

        // Get the current logcat output
        String logcatText = logcatOutput.getText().toString();

        // Disable buttons during generation
        thumbsUpButton.setEnabled(false);
        thumbsDownButton.setEnabled(false);
        runButton.setEnabled(false);

        // Show a progress dialog
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Generating next iteration...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Create an IterationResult with the current state
        ClojureIterationManager.IterationResult result = new ClojureIterationManager.IterationResult(
                currentCode,
                logcatText,
                currentScreenshot,
                true,
                feedback);

        // Generate next iteration using the iteration manager's method
        iterationManager.generateNextIteration(
                currentDescription,
                feedback,
                result)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

                        currentCode = code;
                        currentCodeView.setText(code);

                        // Update session with new code
                        if (currentSession != null) {
                            currentSession.setCurrentCode(code);
                            currentSession.incrementIterationCount();

                            // Save chat history to session
                            LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                                    .getOrCreateSession(currentDescription);
                            currentSession.setChatHistory(chatSession.getMessages());

                            sessionManager.updateSession(currentSession);
                        }

                        // Make sure buttons are enabled after response
                        thumbsUpButton.setEnabled(true);
                        thumbsDownButton.setEnabled(true);
                        runButton.setEnabled(true);

                        runCurrentCode();
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error generating next iteration", throwable);
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

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

        // Generate next iteration using the iteration manager's method
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

                            // Update session with new code
                            if (currentSession != null) {
                                currentSession.setCurrentCode(cleanCode);
                                currentSession.incrementIterationCount();

                                // Save chat history to session
                                LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                                        .getOrCreateSession(currentDescription);
                                currentSession.setChatHistory(chatSession.getMessages());

                                sessionManager.updateSession(currentSession);
                            }

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

        // Make sure view containers are visible
        screenshotView.setVisibility(View.VISIBLE);
        screenshotsContainer.setVisibility(View.VISIBLE);

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

            // Save screenshots to session
            if (currentSession != null && screenshotPaths.length > 0) {
                List<String> paths = new ArrayList<>(Arrays.asList(screenshotPaths));
                currentSession.setScreenshotPaths(paths);
                sessionManager.updateSession(currentSession);
                Log.d(TAG, "Saved " + paths.size() + " screenshot paths to session");
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

            // Save logcat to session
            if (currentSession != null && processLogcat != null && !processLogcat.isEmpty()) {
                currentSession.setLastLogcat(processLogcat);
                sessionManager.updateSession(currentSession);
                Log.d(TAG, "Saved logcat to session");
            }
        }

        // Check for error feedback from RenderActivity
        if (intent.hasExtra(RenderActivity.EXTRA_ERROR)) {
            String errorFeedback = intent.getStringExtra(RenderActivity.EXTRA_ERROR);

            // Pre-fill the feedback input
            if (feedbackInput != null) {
                feedbackInput.setText(errorFeedback);
            }

            // Save error info to session
            if (currentSession != null) {
                currentSession.setLastErrorFeedback(errorFeedback);
                currentSession.setHasError(true);
                sessionManager.updateSession(currentSession);
                Log.d(TAG, "Saved error feedback to session");
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
                    // Make sure the view is visible
                    screenshotView.setVisibility(View.VISIBLE);

                    // Set the bitmap
                    screenshotView.setImageBitmap(bitmap);
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

        // Make the container visible
        screenshotsContainer.setVisibility(View.VISIBLE);

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
        updateModelSpinner(type, false);
    }

    private void updateModelSpinner(LLMClientFactory.LLMType type, boolean restoring) {
        // Show a progress indicator
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Fetching available models...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Store if we need to select a model after loading
        pendingModelSelection = (currentSession != null && currentSession.getLlmModel() != null);
        if (pendingModelSelection) {
            pendingModelName = currentSession.getLlmModel();
        }

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
                    modelsLoaded = false;
                    return;
                }

                ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, models);
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

                // Temporarily disable spinner listener while updating UI
                AdapterView.OnItemSelectedListener existingListener = geminiModelSpinner.getOnItemSelectedListener();
                geminiModelSpinner.setOnItemSelectedListener(null);

                geminiModelSpinner.setAdapter(modelAdapter);
                geminiModelSpinner.setVisibility(View.VISIBLE);

                // If we have a saved model from a session, select it
                if (pendingModelSelection && pendingModelName != null) {
                    for (int i = 0; i < models.size(); i++) {
                        if (models.get(i).equals(pendingModelName)) {
                            geminiModelSpinner.setSelection(i);
                            Log.d(TAG, "Setting spinner to saved model: " + pendingModelName);
                            break;
                        }
                    }
                    pendingModelSelection = false;
                }

                // Restore the listener
                geminiModelSpinner.setOnItemSelectedListener(existingListener);

                // Create new iteration manager with the selected model but only if:
                // 1. Not already created during session restore
                // 2. Not locked due to session restore
                // 3. No session restore model exists
                if (!restoring && !lockClient && sessionRestoreModel == null) {
                    String selectedModel = (String) geminiModelSpinner.getSelectedItem();
                    Log.d(TAG, "Creating new LLM client with model: " + selectedModel);

                    assert iterationManager == null : "iterationManager should be null before creating new instance";
                    iterationManager = new ClojureIterationManager(this,
                            LLMClientFactory.createClient(this, type, selectedModel), sessionId);
                } else {
                    if (lockClient) {
                        Log.d(TAG, "Skipping LLM client creation - client is locked due to session restore");
                    } else {
                        Log.d(TAG, "Skipping LLM client creation - already created or using sessionRestoreModel");
                    }
                }

                // Mark models as loaded
                modelsLoaded = true;
            })).exceptionally(e -> {
                progressDialog.dismiss();
                Log.e(TAG, "Error updating model spinner", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to fetch models: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    geminiModelSpinner.setVisibility(View.GONE);
                    modelsLoaded = false;
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
                // Ignore selection events during initialization to prevent race conditions
                if (blockSpinnerEvents) {
                    Log.d(TAG, "Ignoring model selection event during initialization");
                    return;
                }

                String selectedModel = (String) parent.getItemAtPosition(position);
                Log.d(TAG, "User selected model: " + selectedModel);
                LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();

                // Check if client is locked (session restore)
                if (lockClient) {
                    Log.d(TAG, "Client is locked due to session restore. Ignoring selection change.");
                    return;
                }

                // Only recreate the iteration manager if models are loaded (avoids initial
                // selection issues)
                // AND if this is not the session model we already created a client for
                if (modelsLoaded && (sessionRestoreModel == null || !selectedModel.equals(sessionRestoreModel))) {
                    Log.d(TAG, "Creating new LLM client for user-selected model: " + selectedModel);
                    assert iterationManager == null : "iterationManager should be null before creating new instance";
                    iterationManager = new ClojureIterationManager(
                            ClojureAppDesignActivity.this,
                            LLMClientFactory.createClient(ClojureAppDesignActivity.this, currentType, selectedModel),
                            sessionId);

                    // Update session with new model
                    if (currentSession != null) {
                        currentSession.setLlmModel(selectedModel);
                        sessionManager.updateSession(currentSession);
                    }
                } else if (sessionRestoreModel != null && selectedModel.equals(sessionRestoreModel)) {
                    Log.d(TAG, "Skipping client creation - already using session model: " + sessionRestoreModel);
                }
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

            // Update session with current code
            if (currentSession != null) {
                currentSession.setCurrentCode(currentCode);
                sessionManager.updateSession(currentSession);
            }

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
