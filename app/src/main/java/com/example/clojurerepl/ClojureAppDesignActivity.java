package com.example.clojurerepl;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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
import android.widget.ScrollView;
import java.util.UUID;

public class ClojureAppDesignActivity extends AppCompatActivity
        implements ClojureIterationManager.ExtractionErrorCallback {
    private static final String TAG = "ClojureAppDesign";

    private EditText appDescriptionInput;
    private Button generateButton;
    private TextView currentCodeView;
    private EditText feedbackInput;
    private ImageView screenshotView;
    private TextView logcatOutput;
    private LinearLayout feedbackButtonsContainer;
    private Button thumbsUpButton;
    private Button runButton;

    // Legacy buttons
    private Button submitFeedbackButton;
    private Button confirmSuccessButton;

    // Add a field for the clear chat history button
    private Button clearChatHistoryButton;

    private ClojureIterationManager iterationManager;

    // Add a field for the screenshots container
    private LinearLayout screenshotsContainer;

    // Add field to store current screenshots
    private List<File> currentScreenshots = new ArrayList<>();

    // Add a field to store the process logcat
    private String processLogcat = "";

    // Add LLM type spinner
    private Spinner llmTypeSpinner;
    private Spinner llmSpinner;
    private Button clearApiKeyButton;
    private LLMClientFactory.LLMType defaultLLMType = LLMClientFactory.LLMType.GEMINI;

    // Add this field at the top of the class
    private AlertDialog apiKeyDialog;

    // Add a class field to store the prefilled feedback
    private String lastErrorFeedback = null;

    // Add field to track selected screenshot for multimodal feedback
    private File selectedScreenshot = null;

    // Add session support
    private SessionManager sessionManager;
    private DesignSession currentSession;

    // Add a flag to track when the models are loaded
    private boolean modelsLoaded = false;

    // Add field to track whether line numbers are showing
    private boolean showingLineNumbers = true;

    private void createNewSession() {
        // Create a new session
        currentSession = new DesignSession();
        currentSession.setDescription(
                "Create an app that implements Conway's Game of Life. It's in the form of a 20x20 grid. Each square of the grid is tappable, and when tapped, it switches colors between white (dead) and black (alive). There are three buttons beneath the grid: play, stop, and step. Play runs the game with a delay of half a second between steps until the grid turns all white. Stop stops a play run. Step does a single iteration of the grid state.");
        currentSession.setLlmType(defaultLLMType);
        sessionManager.updateSession(currentSession);
        Log.d(TAG, "Created new session: " + currentSession.getId().toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clojure_design);

        // Initialize session manager
        sessionManager = SessionManager.getInstance(this);

        // Check if this is a request from MainActivity with initial code
        if (getIntent().getBooleanExtra("from_main_activity", false)) {
            handleCodeFromMainActivity();
        } else {
            // Regular session handling logic for normal startup
            // Check if we're opening an existing session
            String sessionId = getIntent().getStringExtra("session_id");
            if (sessionId != null) {
                // Load existing session
                try {
                    UUID sessionUUID = UUID.fromString(sessionId);
                    currentSession = sessionManager.getSessionById(sessionUUID);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid session ID format: " + sessionId, e);
                    createNewSession();
                    return;
                }
                if (currentSession != null) {
                    Log.d(TAG, "Loaded existing session: " + currentSession.getId().toString());
                } else {
                    Log.e(TAG, "Session not found: " + sessionId);
                    createNewSession();
                }
            } else {
                createNewSession();
            }
        }

        // Initialize views and continue with normal session setup
        initializeViews();
        setupSessionState();
    }

    /**
     * Handles incoming code from MainActivity, creating a new design session
     * and initializing it with the provided code.
     */
    private void handleCodeFromMainActivity() {
        // Get the initial code and description from the intent
        String initialCode = getIntent().getStringExtra("initial_code");
        String description = getIntent().getStringExtra("description");

        if (initialCode == null || initialCode.isEmpty()) {
            Toast.makeText(this, "No code provided from MainActivity", Toast.LENGTH_SHORT).show();
            finish(); // Return to previous activity if no code provided
            return;
        }

        if (description == null || description.isEmpty()) {
            description = "Improve this Clojure app"; // Default description
        }

        Log.d(TAG, "Received code from MainActivity. Length: " + initialCode.length());
        Log.d(TAG, "Description: " + description);

        // Create a new session for this code
        currentSession = new DesignSession();

        // Update session data
        currentSession.setDescription(description);
        currentSession.setCurrentCode(initialCode);
        currentSession.setLlmType(defaultLLMType);

        sessionManager.updateSession(currentSession);

        // generateButton.setText(R.string.improve_app);
        Toast.makeText(this,
                "You can run this code now or click 'Improve App' to enhance it with AI",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Initialize all UI views
     */
    private void initializeViews() {
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

        // Set LLM type from session, defaulting to GEMINI if not set
        assert currentSession != null : "currentSession should not be null";
        assert currentSession.getLlmType() != null : "currentSession.getLlmType() should not be null";
        LLMClientFactory.LLMType sessionType = currentSession.getLlmType();
        llmTypeSpinner.setSelection(
                Arrays.asList(LLMClientFactory.LLMType.values()).indexOf(sessionType));

        llmTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LLMClientFactory.LLMType selectedType = (LLMClientFactory.LLMType) parent.getItemAtPosition(position);
                ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(ClojureAppDesignActivity.this);

                if (selectedType == LLMClientFactory.LLMType.GEMINI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.GEMINI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.GEMINI);
                    } else {
                        updateLlmSpinner(LLMClientFactory.LLMType.GEMINI);
                    }
                } else if (selectedType == LLMClientFactory.LLMType.OPENAI) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.OPENAI)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.OPENAI);
                    } else {
                        updateLlmSpinner(LLMClientFactory.LLMType.OPENAI);
                    }
                } else if (selectedType == LLMClientFactory.LLMType.CLAUDE) {
                    if (!apiKeyManager.hasApiKey(LLMClientFactory.LLMType.CLAUDE)) {
                        showApiKeyDialog(LLMClientFactory.LLMType.CLAUDE);
                    } else {
                        updateLlmSpinner(LLMClientFactory.LLMType.CLAUDE);
                    }
                } else {
                    llmSpinner.setVisibility(View.GONE);
                    iterationManager = new ClojureIterationManager(ClojureAppDesignActivity.this,
                            LLMClientFactory.createClient(ClojureAppDesignActivity.this, selectedType),
                            currentSession.getId());
                    iterationManager.setExtractionErrorCallback(ClojureAppDesignActivity.this);
                }

                if (currentSession.getLlmType() != selectedType) {
                    currentSession.setLlmType(selectedType);
                    // We can't be allowed to change the model provider if we've already
                    // started generating code. In this case, the model provider dropdown menu is
                    // locked.
                    assert currentSession.getIterationCount() == 0;
                    // We're changing the model provider so clear the model name in the current
                    // session, but do this only if we haven't started generating code yet.
                    Log.d(TAG, "Clearing LLM model for new model provider");
                    currentSession.setLlmModel(null);
                    sessionManager.updateSession(currentSession);

                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }

        });

        // Add model spinner
        llmSpinner = findViewById(R.id.llm_spinner);
        setupLlmSpinnerListener();

        llmSpinner.setVisibility(View.VISIBLE);

        // Set up click listeners
        generateButton.setOnClickListener(v -> handleGenerateButtonClick());
        thumbsUpButton.setOnClickListener(v -> acceptApp());
        runButton.setOnClickListener(v -> runCurrentCode());

        // Legacy button listeners
        submitFeedbackButton.setOnClickListener(v -> {
            String feedback = feedbackInput.getText().toString();
            if (feedback.isEmpty()) {
                feedbackInput.setError("Please enter feedback");
                return;
            }
            submitFeedbackWithText(feedback);
        });
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

        appDescriptionInput.setText(currentSession.getDescription());
    }

    /**
     * Setup session state based on the current session
     */
    private void setupSessionState() {
        // If we loaded an existing session, update the UI
        if (currentSession != null && currentSession.getDescription() != null) {
            Log.d(TAG, "Setting up session state: " +
                    "id=" + currentSession.getId().toString() +
                    ", description=" + (currentSession.getDescription() != null ? "present" : "null") +
                    ", code=" + (currentSession.getCurrentCode() != null ? "present" : "null") +
                    ", llmType=" + (currentSession.getLlmType() != null ? currentSession.getLlmType() : "null") +
                    ", llmModel=" + (currentSession.getLlmModel() != null ? currentSession.getLlmModel() : "null"));

            appDescriptionInput.setText(currentSession.getDescription());

            if (currentSession.getCurrentCode() != null) {
                String currentCode = currentSession.getCurrentCode();

                displayCurrentCode();

                // Show feedback buttons
                feedbackButtonsContainer.setVisibility(View.VISIBLE);
                thumbsUpButton.setVisibility(View.VISIBLE);
                runButton.setVisibility(View.VISIBLE);

                // Update generate button text for iteration
                generateButton.setText(R.string.improve_app);

                // Mark that generation has started only if LLM type has been set
                // This will determine if we lock the model selectors
                boolean llmTypeIsSet = currentSession.getLlmType() != null &&
                        currentSession.getLlmModel() != null;

                // Only disable the spinners if LLM type and model are set
                llmTypeSpinner.setEnabled(!llmTypeIsSet);
                llmSpinner.setEnabled(!llmTypeIsSet);

                // Show toast message to inform the user if LLM is locked
                if (llmTypeIsSet && currentSession.getIterationCount() > 0) {
                    new Handler().postDelayed(() -> {
                        Toast.makeText(this,
                                "Model selection is locked for existing session. Start a new session to change models.",
                                Toast.LENGTH_LONG).show();
                    }, 1000); // Slight delay for better UX
                } else {
                    new Handler().postDelayed(() -> {
                        Toast.makeText(this,
                                "Please select an LLM model to continue with this session.",
                                Toast.LENGTH_LONG).show();
                    }, 1000); // Slight delay for better UX
                    Log.d(TAG, "Either LLM type is not set or generation has not started - enabling model selectors");
                    llmTypeSpinner.setEnabled(true);
                    llmSpinner.setEnabled(true);
                }
            }

            // Set the LLM type and model in the UI if they exist
            if (currentSession.getLlmType() != null) {
                int position = Arrays.asList(LLMClientFactory.LLMType.values())
                        .indexOf(currentSession.getLlmType());
                if (position >= 0) {
                    llmTypeSpinner.setSelection(position);
                }

                String sessionRestoreModel = currentSession.getLlmModel();
                // Set the model if available
                if (sessionRestoreModel != null) {
                    // Create the client directly with the saved model
                    Log.d(TAG, "Session restore: Creating LLM client with model " + sessionRestoreModel);
                    LLMClient llmClient = LLMClientFactory.createClient(
                            this,
                            currentSession.getLlmType(),
                            sessionRestoreModel);
                    assert iterationManager == null : "iterationManager should be null before creating new instance";
                    iterationManager = new ClojureIterationManager(this, llmClient, currentSession.getId());
                    iterationManager.setExtractionErrorCallback(this);

                    // We'll wait until llmTypeSpinner's selection listener fires to trigger model
                    // enumeration
                }
            } else {
                Log.d(TAG, "Session restore: LLM type not set - enabling model selectors");
                // If LLM type is not set, make sure model selectors are enabled
                llmTypeSpinner.setEnabled(true);
                llmSpinner.setEnabled(true);
            }

            // Restore logcat output if available
            if (currentSession.getLastLogcat() != null && !currentSession.getLastLogcat().isEmpty()) {
                processLogcat = currentSession.getLastLogcat();
                logcatOutput.setText(processLogcat);
                Log.d(TAG, "Restored logcat output from session");
            }

            // Restore latest screenshot set if available
            List<String> paths = currentSession.getLatestScreenshotSet();
            if (paths != null && !paths.isEmpty()) {
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
                    displayScreenshot(currentScreenshots.get(0));
                    Log.d(TAG, "Displayed first screenshot in main view: " + validPaths.get(0));

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

        // Now that initialization is complete, restore chat history if available
        if (currentSession != null && currentSession.getChatHistory() != null
                && !currentSession.getChatHistory().isEmpty()
                && iterationManager != null) {

            // Get chat session and restore messages
            LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                    .getOrCreateSession(currentSession.getId());

            // Reset the session to clear any default messages
            chatSession.reset();

            // Then add all messages from the saved session
            List<LLMClient.Message> savedMessages = currentSession.getChatHistory();
            Log.d(TAG, "Restoring " + savedMessages.size() + " messages to chat session");

            for (LLMClient.Message message : savedMessages) {
                if (LLMClient.MessageRole.SYSTEM.equals(message.role)) {
                    chatSession.queueSystemPrompt(message.content);
                } else if (LLMClient.MessageRole.USER.equals(message.role)) {
                    if (message.hasImage()) {
                        chatSession.queueUserMessageWithImage(message.content, message.imageFile);
                    } else {
                        chatSession.queueUserMessage(message.content);
                    }
                } else if (LLMClient.MessageRole.ASSISTANT.equals(message.role)) {
                    chatSession.queueAssistantResponse(message.content);
                }
            }

            Log.d(TAG, "Chat history restored");
        }
    }

    /**
     * Handles clicks on the Generate button
     * - Initial click: generates the first version
     * - Subsequent clicks: shows feedback dialog for iteration
     */
    private void handleGenerateButtonClick() {
        // Always get the latest description text from the input field
        String currentDescriptionText = appDescriptionInput.getText().toString().trim();
        String currentDescription = currentSession.getDescription();
        if (!currentDescriptionText.isEmpty() && !currentDescriptionText.equals(currentDescription)) {
            Log.d(TAG, "Description updated from: '" + currentDescription + "' to: '" + currentDescriptionText + "'");
            // Update the session if it exists
            if (currentSession != null) {
                currentSession.setDescription(currentDescriptionText);
                sessionManager.updateSession(currentSession);
            }
        }

        String currentCode = currentSession.getCurrentCode();

        // Log the current state for debugging
        Log.d(TAG, "handleGenerateButtonClick: currentCode=" +
                (currentCode != null ? "present (len=" + currentCode.length() + ")" : "null"));

        // If we already have code generated and generation has started, show the
        // feedback dialog
        if (currentCode != null && !currentCode.isEmpty() && currentSession.getIterationCount() > 0) {
            Log.d(TAG, "Showing feedback dialog for existing code");
            showFeedbackDialog(null);
            return;
        }

        // If we have initial code from MainActivity but haven't started generation yet,
        // use it as the base for improvement
        if (currentCode != null && !currentCode.isEmpty() && currentSession.getIterationCount() == 0) {
            Log.d(TAG, "Using initial code as base for improvement");
            // Get the updated description (user may have edited it)
            String description = appDescriptionInput.getText().toString();
            if (description.isEmpty()) {
                Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
                return;
            }

            // Update session with the possibly edited description
            currentSession.setDescription(description);
            // Save the session
            sessionManager.addSession(currentSession);
        }

        // Start a new design (potentially with initial code).
        startNewDesign();
    }

    private void startNewDesign() {
        String description = appDescriptionInput.getText().toString();
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the current LLM type and model
        LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
        String selectedModel = (String) llmSpinner.getSelectedItem();

        // Check if a model has been selected
        if (selectedModel == null) {
            Toast.makeText(this, "Please select a model before generating code", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if the selected model is the prompt item
        if (selectedModel.equals("-- Select a model --")) {
            Toast.makeText(this, "Please select a specific model from the dropdown", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEW APP DESIGN           ║\n" +
                "╚═══════════════════════════════════════════╝");

        generateButton.setEnabled(false);

        // Create LLM client using factory with the selected model
        LLMClient llmClient = LLMClientFactory.createClient(this, currentType, selectedModel);
        if (iterationManager != null) {
            Log.d(TAG, "Shutting down existing iterationManager before creating a new one");
            iterationManager.shutdown();
            iterationManager = null;
        }
        iterationManager = new ClojureIterationManager(this, llmClient, currentSession.getId());
        iterationManager.setExtractionErrorCallback(this);

        // Mark that generation has started - disable model selection
        Log.d(TAG, "startNewDesign: Generation started - disabling model selection");
        llmTypeSpinner.setEnabled(false);
        llmSpinner.setEnabled(false);

        // Show toast message to inform the user
        Toast.makeText(this,
                "Model selection is locked. Start a new session to change models.",
                Toast.LENGTH_LONG).show();

        assert currentSession != null;

        // Check if we have existing code to use as a starting point
        String currentCode = currentSession.getCurrentCode();
        if (currentCode != null && !currentCode.isEmpty()) {
            Log.d(TAG, "Using existing code as a starting point. Length: " + currentCode.length());
            Toast.makeText(this, "Using existing code as a starting point", Toast.LENGTH_SHORT).show();
        }

        // Show a progress dialog
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Generating initial code...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Get the LLM to generate the code first - using IterationManager now
        iterationManager.generateInitialCode(description, currentCode)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

                        // Update session with code
                        currentSession.setCurrentCode(code);
                        currentSession.incrementIterationCount();

                        displayCurrentCode();

                        // Save chat history to session
                        LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                                .getOrCreateSession(currentSession.getId());
                        currentSession.setChatHistory(chatSession.getMessages());

                        sessionManager.updateSession(currentSession);

                        // Show the feedback buttons
                        feedbackButtonsContainer.setVisibility(View.VISIBLE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);

                        // Change generate button text to "Improve App" for subsequent clicks
                        generateButton.setText(R.string.improve_app);

                        // Only launch RenderActivity after we have the code
                        if (code != null && !code.isEmpty()) {
                            runCurrentCode();
                        }

                        generateButton.setEnabled(true);
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error generating code", throwable);
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

                        showLLMErrorDialog("Code Generation Error",
                                "Error generating code: " + throwable.getMessage());
                        generateButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void submitFeedbackWithText(String feedback) {
        submitFeedbackWithText(feedback, selectedScreenshot);
    }

    private void submitFeedbackWithText(String feedback, File image) {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEXT ITERATION           ║\n" +
                "╚═══════════════════════════════════════════╝");

        Log.d(TAG, "submitFeedbackWithText: feedback=" +
                (feedback != null ? "present (len=" + feedback.length() + ")" : "null") +
                ", image=" + (image != null ? image.getPath() : "null") +
                ", iterationManager=" + (iterationManager != null ? "present" : "null") +
                ", LLM client="
                + (iterationManager != null && iterationManager.getLLMClient() != null ? "initialized" : "null"));

        // Clear stored error feedback since we're starting a new iteration
        lastErrorFeedback = null;
        selectedScreenshot = null; // Clear selected screenshot after use

        // Check if models are still loading
        if (!modelsLoaded) {
            Toast.makeText(this, "Models are still loading, please wait a moment and try again", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        // Make sure buttons are enabled
        thumbsUpButton.setEnabled(true);
        runButton.setEnabled(true);
        generateButton.setEnabled(true);

        // Ensure we have a valid description
        String currentDescription = currentSession.getDescription();
        if (currentDescription == null || currentDescription.isEmpty()) {
            currentDescription = appDescriptionInput.getText().toString();
            if (currentDescription.isEmpty()) {
                Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        currentSession.setDescription(currentDescription);
        sessionManager.updateSession(currentSession);

        assert iterationManager != null : "iterationManager should not be null";

        // Ensure we have current code
        String currentCode = currentSession.getCurrentCode();
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
        runButton.setEnabled(false);
        generateButton.setEnabled(false); // Also disable the generate button

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
                currentSession.getDescription(),
                feedback,
                result,
                image)
                .thenAccept(code -> {
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

                        assert currentSession != null;

                        currentSession.setCurrentCode(code);
                        currentSession.incrementIterationCount();

                        displayCurrentCode();

                        // Save chat history to session
                        LLMClient.ChatSession chatSession = iterationManager.getLLMClient()
                                .getOrCreateSession(currentSession.getId());
                        currentSession.setChatHistory(chatSession.getMessages());

                        sessionManager.updateSession(currentSession);

                        // Make sure buttons are enabled after response
                        thumbsUpButton.setEnabled(true);
                        runButton.setEnabled(true);
                        generateButton.setEnabled(true); // Re-enable the generate button

                        runCurrentCode();
                    });
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "Error generating next iteration", throwable);
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        progressDialog.dismiss();

                        showLLMErrorDialog("Iteration Error",
                                "Error generating next iteration: " + throwable.getMessage());
                        // Make sure buttons are enabled on error
                        thumbsUpButton.setEnabled(true);
                        runButton.setEnabled(true);
                        generateButton.setEnabled(true); // Re-enable the generate button
                    });
                    return null;
                });
    }

    private void acceptApp() {
        // Re-enable buttons after accepting
        thumbsUpButton.setEnabled(true);
        runButton.setEnabled(true);

        // Here we encode the current code using base64 before sending
        String currentCode = currentSession.getCurrentCode();
        if (currentCode.isEmpty()) {
            Toast.makeText(this, "No code to accept", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save any description updates before accepting the app
        String currentDescriptionText = appDescriptionInput.getText().toString().trim();
        String currentDescription = currentSession.getDescription();
        if (!currentDescriptionText.isEmpty() && !currentDescriptionText.equals(currentDescription)) {
            Log.d(TAG, "Description updated before accepting app: '" + currentDescriptionText + "'");
            currentDescription = currentDescriptionText;

            // Update the session if it exists
            if (currentSession != null) {
                currentSession.setDescription(currentDescription);
                sessionManager.updateSession(currentSession);
            }
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
        intent.putExtra(MainActivity.EXTRA_CODE, encodedCode);
        intent.putExtra(MainActivity.EXTRA_ENCODING, "base64");
        intent.putExtra(MainActivity.EXTRA_DESCRIPTION, currentDescription);
        startActivity(intent);

        // Save this final version
        saveCodeToFile();

        Toast.makeText(this, "Code accepted and sent to REPL", Toast.LENGTH_LONG).show();

        finish(); // Close this activity
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

        // Ensure generate button is enabled when returning from RenderActivity
        generateButton.setEnabled(true);

        // Make sure view containers are visible
        screenshotView.setVisibility(View.VISIBLE);
        screenshotsContainer.setVisibility(View.VISIBLE);

        // First check for screenshot data (existing functionality)
        if (intent.hasExtra(RenderActivity.EXTRA_RESULT_SCREENSHOT_PATHS)) {
            String[] screenshotPaths = intent.getStringArrayExtra(RenderActivity.EXTRA_RESULT_SCREENSHOT_PATHS);
            Log.d(TAG, "Received " + screenshotPaths.length + " screenshots in onNewIntent");

            // Save the screenshots for future reference
            currentScreenshots.clear();
            for (String path : screenshotPaths) {
                currentScreenshots.add(new File(path));
            }

            // Display all screenshots
            displayScreenshots(screenshotPaths);

            // Save the current code when returning from RenderActivity
            String currentCode = currentSession.getCurrentCode();
            if (currentCode != null && !currentCode.isEmpty()) {
                saveCodeToFile();
            }

            // Save screenshots to session
            if (currentSession != null && screenshotPaths.length > 0) {
                List<String> paths = new ArrayList<>(Arrays.asList(screenshotPaths));

                // Use the new API to add the screenshot set
                currentSession.addScreenshotSet(paths);

                // Log the addition of the new screenshot set
                Log.d(TAG, "Added a new set of " + paths.size() + " screenshots to session");

                // Update session in manager
                sessionManager.updateSession(currentSession);
            }
        }

        // Check if we have process logcat data
        if (intent.hasExtra(RenderActivity.EXTRA_RESULT_SUCCESS)) {
            boolean success = intent.getBooleanExtra(RenderActivity.EXTRA_RESULT_SUCCESS, false);
            Log.d(TAG, "RenderActivity success: " + success);
        }

        // Check for error feedback from RenderActivity
        if (intent.hasExtra(RenderActivity.EXTRA_RESULT_ERROR)) {
            String errorFeedback = intent.getStringExtra(RenderActivity.EXTRA_RESULT_ERROR);

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

    private void updateLlmSpinner(LLMClientFactory.LLMType type) {
        // Show a progress indicator
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Fetching available models...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Store if we need to select a model after loading
        assert currentSession != null;
        String pendingModelName = currentSession.getLlmModel();

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
                    llmSpinner.setVisibility(View.GONE);
                    modelsLoaded = false;
                    return;
                }

                // Add a prompt item as the first item in the spinner
                List<String> modelList = new ArrayList<>(models);
                modelList.add(0, "-- Select a model --");

                ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, modelList);
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

                // Add this flag to ignore the first invocation of the listener after setting it
                boolean ignoreNextSelectionEvent = false;

                // Temporarily disable spinner listener while updating UI
                AdapterView.OnItemSelectedListener existingListener = llmSpinner.getOnItemSelectedListener();
                llmSpinner.setOnItemSelectedListener(null);

                llmSpinner.setAdapter(modelAdapter);

                // Select the prompt item by default
                llmSpinner.setSelection(0);

                // If we have a saved model from a session, select it
                if (pendingModelName != null) {
                    for (int i = 0; i < modelList.size(); i++) {
                        if (modelList.get(i).equals(pendingModelName)) {
                            // Ensure the spinner is visible when selecting a saved model
                            llmSpinner.setSelection(i);
                            Log.d(TAG, "Setting spinner to saved model: " + pendingModelName + " index " + i);
                            // Set flag to ignore next event since we've selected an item
                            ignoreNextSelectionEvent = true;
                            break;
                        }
                    }
                } else {
                    // Keep the prompt selected
                    Log.d(TAG, "No saved model, keeping prompt selected");
                }

                // Create a wrapper listener that ignores the first call
                AdapterView.OnItemSelectedListener wrapperListener = null;
                if (existingListener != null) {
                    final boolean finalIgnoreNextEvent = ignoreNextSelectionEvent;
                    final AdapterView.OnItemSelectedListener finalExistingListener = existingListener;
                    wrapperListener = new AdapterView.OnItemSelectedListener() {
                        private boolean ignoreNext = finalIgnoreNextEvent;

                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            if (ignoreNext) {
                                Log.d(TAG, "Ignoring first automatic selection event after setting listener");
                                ignoreNext = false;
                                return;
                            }
                            finalExistingListener.onItemSelected(parent, view, position, id);
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {
                            finalExistingListener.onNothingSelected(parent);
                        }
                    };
                }

                // Restore the listener with the wrapper
                llmSpinner.setOnItemSelectedListener(wrapperListener);

                // Mark models as loaded
                modelsLoaded = true;
            })).exceptionally(e -> {
                progressDialog.dismiss();
                Log.e(TAG, "Error updating model spinner", e);
                runOnUiThread(() -> {
                    showLLMErrorDialog("Model Fetch Error",
                            "Failed to fetch models: " + e.getMessage());
                    llmSpinner.setVisibility(View.GONE);
                    modelsLoaded = false;
                });
                return null;
            });
        }, 500); // Short delay to ensure API key is saved first
    }

    // Update the model spinner listener to use factory
    private void setupLlmSpinnerListener() {
        llmSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                // Ignore the prompt selection (position 0)
                if (position == 0) {
                    Log.d(TAG, "Prompt item selected, not creating LLM client");
                    return;
                }

                String selectedModel = (String) parent.getItemAtPosition(position);
                Log.d(TAG, "User selected model: " + selectedModel);
                LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();

                // Only recreate the iteration manager if models are loaded (avoids initial
                // selection issues)
                // AND if this is not the session model we already created a client for
                String sessionRestoreModel = currentSession.getLlmModel();
                if (modelsLoaded && (sessionRestoreModel == null || !selectedModel.equals(sessionRestoreModel))) {
                    Log.d(TAG, "Creating new LLM client for user-selected model: " + selectedModel);

                    // Check if we need to shut down the existing iterationManager
                    if (iterationManager != null) {
                        Log.d(TAG, "Shutting down existing iterationManager before creating a new one");
                        iterationManager.shutdown();
                        iterationManager = null;
                    }

                    iterationManager = new ClojureIterationManager(
                            ClojureAppDesignActivity.this,
                            LLMClientFactory.createClient(ClojureAppDesignActivity.this, currentType, selectedModel),
                            currentSession.getId());
                    iterationManager.setExtractionErrorCallback(ClojureAppDesignActivity.this);

                    // Update session with new model
                    if (currentSession != null) {
                        currentSession.setLlmType(currentType);
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
        String serviceUrl;

        if (type == LLMClientFactory.LLMType.GEMINI) {
            serviceUrl = "https://makersuite.google.com/app/apikey";
        } else if (type == LLMClientFactory.LLMType.OPENAI) {
            serviceUrl = "https://platform.openai.com/api-keys";
        } else if (type == LLMClientFactory.LLMType.CLAUDE) {
            serviceUrl = "https://console.anthropic.com/keys";
        } else {
            serviceUrl = "";
        }

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

                // Clear all model caches since the new API key might have access to different
                // models
                LLMClientFactory.clearAllModelCaches();

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
                }).thenAccept(models -> runOnUiThread(() -> {
                    progressDialog.dismiss();

                    if (models.isEmpty()) {
                        Toast.makeText(this, "Failed to fetch models. Using fallback models.", Toast.LENGTH_SHORT)
                                .show();
                    }

                    // Update model spinner with fetched models
                    updateLlmSpinner(type);
                })).exceptionally(e -> {
                    Log.e(TAG, "Error in model fetching", e);
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        showLLMErrorDialog("Model Fetch Error",
                                "Failed to fetch models: " + e.getMessage());
                        // Still try to update spinner with fallback models
                        updateLlmSpinner(type);
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
        menu.add(0, R.id.action_clear_history, 0, "Clear Chat History")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.action_clear_api_key, 0, "Clear API Key")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.action_toggle_line_numbers, 0, "Toggle Line Numbers")
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
        } else if (id == R.id.action_clear_history) {
            clearChatHistory();
            return true;
        } else if (id == R.id.action_toggle_line_numbers) {
            toggleLineNumbersDisplay();
            return true;
        } else if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveCodeToFile() {
        String currentCode = currentSession.getCurrentCode();
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
            String filename = "design_" + timestamp + "_iter" + currentSession.getIterationCount() + ".clj";
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

            Log.d(TAG, "Code saved to: " + codeFile.getAbsolutePath() + " (Iteration "
                    + currentSession.getIterationCount() + ")");
            // Only show toast for manual saves, not automatic ones
        } catch (Exception e) {
            Log.e(TAG, "Error saving code to file", e);
        }
    }

    /**
     * Runs the current code without accepting it
     */
    private void runCurrentCode() {
        String currentCode = currentSession.getCurrentCode();
        if (currentCode == null || currentCode.isEmpty()) {
            Toast.makeText(this, "No code to run", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save any description updates before running the code
        String currentDescriptionText = appDescriptionInput.getText().toString().trim();
        String currentDescription = currentSession.getDescription();
        if (!currentDescriptionText.isEmpty() && !currentDescriptionText.equals(currentDescription)) {
            Log.d(TAG, "Description updated before running code: '" + currentDescriptionText + "'");
            currentDescription = currentDescriptionText;

            // Update the session if it exists
            if (currentSession != null) {
                currentSession.setDescription(currentDescription);
                sessionManager.updateSession(currentSession);
            }
        }

        // Start the activity
        RenderActivity.launch(this, ClojureAppDesignActivity.class,
                new RenderActivity.ExitCallback() {
                    @Override
                    public void onExit(String logcat) {
                        runOnUiThread(() -> {
                            // Update the logcat output view if it exists
                            processLogcat = logcat;
                            if (logcatOutput != null) {
                                logcatOutput.setText(processLogcat);
                            }

                            // Save logcat to session
                            if (currentSession != null && processLogcat != null && !processLogcat.isEmpty()) {
                                currentSession.setLastLogcat(processLogcat);
                                sessionManager.updateSession(currentSession);
                                Log.d(TAG, "Saved logcat to session");
                            }
                        });
                    }
                },
                currentCode,
                currentSession.getId().toString(),
                currentSession.getIterationCount(),
                true);
    }

    private void clearChatSession() {
        if (iterationManager != null) {
            LLMClient llmClient = iterationManager.getLLMClient();
            if (llmClient != null) {
                String currentDescription = currentSession.getDescription();
                llmClient.getOrCreateSession(currentSession.getId()).reset();
            }
        }
    }

    public String getSelectedModel() {
        if (llmSpinner != null) {
            return (String) llmSpinner.getSelectedItem();
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
                        llmSpinner.setVisibility(View.GONE);

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
        // Add extra logging to diagnose the issue
        Log.d(TAG, "showFeedbackDialog called, prefilledFeedback=" +
                (prefilledFeedback != null ? "present (len=" + prefilledFeedback.length() + ")" : "null") +
                ", iterationManager=" + (iterationManager != null ? "present" : "null"));

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

        // Set up screenshot checkbox
        CheckBox screenshotCheckbox = dialogView.findViewById(R.id.include_screenshot_checkbox);

        // Check if current model is multimodal
        boolean isMultimodal = false;
        if (iterationManager != null) {
            LLMClient.ModelProperties props = iterationManager.getModelProperties();
            isMultimodal = props != null && props.isMultimodal;
        }

        // Enable/disable checkbox based on multimodal capability
        screenshotCheckbox.setEnabled(isMultimodal && !currentScreenshots.isEmpty());

        // Set up checkbox listener
        screenshotCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                showScreenshotSelectionDialog(screenshotCheckbox);
            } else {
                selectedScreenshot = null;
            }
        });

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

            // We need to check if iterationManager is null here to avoid NPE
            if (iterationManager == null) {
                // Save the feedback for later use if the model is not yet selected
                String selectedFeedback = null;

                if (selectedPosition == options.length - 1 && hasFeedback) {
                    // "Use the error feedback below..." option
                    selectedFeedback = finalFeedback;
                } else if (selectedPosition == options.length - 2) {
                    // "Custom feedback..." option
                    dialog.dismiss();
                    showCustomFeedbackDialog(hasFeedback ? finalFeedback : null);
                    return;
                } else {
                    // Standard options
                    selectedFeedback = options[selectedPosition];
                }

                // Store this feedback for later use
                lastErrorFeedback = selectedFeedback;

                dialog.dismiss();

                // Show a toast message informing user to select an AI model
                Toast.makeText(this,
                        "Feedback saved. Please select an AI model first, then click 'Improve App' again.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // Normal feedback submission with a valid iterationManager
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
                if (iterationManager == null) {
                    // Save the feedback for later use
                    lastErrorFeedback = feedback;
                    Toast.makeText(this,
                            "Feedback saved. Please select an AI model first, then click 'Improve App' again.",
                            Toast.LENGTH_LONG).show();
                } else {
                    submitFeedbackWithText(feedback);
                }
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
                    String currentDescription = currentSession.getDescription();
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
                        llmClient.getOrCreateSession(currentSession.getId()).reset();

                        Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed to clear chat history", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    /**
     * Clears the generation state, allowing model selection again
     */
    private void clearGeneration() {
        if (iterationManager != null) {
            iterationManager.shutdown();
            iterationManager = null;
        }

        // Re-enable spinners when generation is cleared
        llmTypeSpinner.setEnabled(true);
        llmSpinner.setEnabled(true);

        Log.d(TAG, "Generation state cleared - model selection unlocked");
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Save the current description in case it was edited
        if (currentSession != null && appDescriptionInput != null) {
            String updatedDescription = appDescriptionInput.getText().toString().trim();
            if (!updatedDescription.isEmpty() &&
                    (currentSession.getDescription() == null ||
                            !updatedDescription.equals(currentSession.getDescription()))) {

                Log.d(TAG, "Saving updated description before exiting: " + updatedDescription);
                currentSession.setDescription(updatedDescription);
                sessionManager.updateSession(currentSession);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensure the description field matches the session if it exists
        if (currentSession != null && appDescriptionInput != null && currentSession.getDescription() != null) {
            // Only update if they're different to avoid resetting cursor position
            String currentText = appDescriptionInput.getText().toString().trim();
            if (!currentText.equals(currentSession.getDescription())) {
                Log.d(TAG, "Updating description field to match session on resume");
                appDescriptionInput.setText(currentSession.getDescription());
            }
        }
    }

    /**
     * Displays the code in the current DesignSession with line numbers
     * depending on the toggle.
     */
    private void displayCurrentCode() {
        if (currentSession == null || currentSession.getCurrentCode() == null) {
            Toast.makeText(this, "No code available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (showingLineNumbers) {
            // Display code with line numbers
            currentCodeView.setText(currentSession.getCodeWithLineNumbers());
            Toast.makeText(this, "Showing code with line numbers", Toast.LENGTH_SHORT).show();
        } else {
            // Display regular code
            currentCodeView.setText(currentSession.getCurrentCode());
            Toast.makeText(this, "Showing code without line numbers", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Toggles between showing code with or without line numbers
     */
    private void toggleLineNumbersDisplay() {
        showingLineNumbers = !showingLineNumbers;
        displayCurrentCode();
    }

    /**
     * Shows a popup dialog for LLM errors and re-enables the LLM provider and model
     * choice menus
     * 
     * @param title        The dialog title
     * @param errorMessage The error message to display
     */
    private void showLLMErrorDialog(String title, String errorMessage) {
        runOnUiThread(() -> {
            // Re-enable LLM provider and model choice menus when LLM errors occur
            llmTypeSpinner.setEnabled(true);
            llmSpinner.setEnabled(true);

            // Show popup dialog with error message
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(errorMessage)
                    .setPositiveButton("OK", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    /**
     * Implementation of ExtractionErrorCallback to show popup dialog for extraction
     * errors
     */
    @Override
    public void onExtractionError(String errorMessage) {
        runOnUiThread(() -> {
            // Re-enable buttons since the operation was cancelled
            generateButton.setEnabled(true);
            thumbsUpButton.setEnabled(true);
            runButton.setEnabled(true);

            // Re-enable LLM provider and model choice menus when extraction fails
            llmTypeSpinner.setEnabled(true);
            llmSpinner.setEnabled(true);

            // Show popup dialog with error message
            new AlertDialog.Builder(this)
                    .setTitle("Code Extraction Error")
                    .setMessage("Failed to extract Clojure code from the AI response:\n\n" + errorMessage)
                    .setPositiveButton("OK", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    /**
     * Shows a dialog for selecting a screenshot from the current screenshots
     */
    private void showScreenshotSelectionDialog(CheckBox screenshotCheckbox) {
        if (currentScreenshots.isEmpty()) {
            Toast.makeText(this, "No screenshots available", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Screenshot");

        // Create a horizontal scrollable layout for screenshots
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        // Create the dialog first so we can reference it in the click listeners
        final AlertDialog[] dialogRef = new AlertDialog[1];

        for (int i = 0; i < currentScreenshots.size(); i++) {
            File screenshot = currentScreenshots.get(i);

            // Create ImageView for each screenshot
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(
                    dpToPx(120), dpToPx(200)));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
            imageView.setBackgroundResource(android.R.drawable.btn_default);

            // Load and set the bitmap
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);

                    // Set click listener
                    final int index = i;
                    imageView.setOnClickListener(v -> {
                        selectedScreenshot = screenshot;
                        Toast.makeText(this, "Screenshot " + (index + 1) + " selected", Toast.LENGTH_SHORT).show();
                        if (dialogRef[0] != null) {
                            dialogRef[0].dismiss();
                        }
                    });

                    container.addView(imageView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading screenshot " + screenshot.getPath(), e);
            }
        }

        // Create a ScrollView to make it scrollable
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(container);

        builder.setView(scrollView);
        builder.setPositiveButton("OK", (d, which) -> d.dismiss());
        builder.setNegativeButton("Cancel", (d, which) -> {
            selectedScreenshot = null;
            d.dismiss();
            screenshotCheckbox.setChecked(false);
        });

        dialogRef[0] = builder.show();
    }
}
