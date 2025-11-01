package com.example.clojurerepl;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.util.HashSet;
import java.util.Set;
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
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

public class ClojureAppDesignActivity extends AppCompatActivity {
    private static final String TAG = "ClojureAppDesign";
    private static final String UNNAMED_SESSION = "(unnamed session)";

    private ScrollView chatHistoryContainer;
    private LinearLayout chatHistoryLayout;
    private EditText feedbackInput;
    private Button paperclipButton;
    private ImageView screenshotView;
    private LinearLayout feedbackButtonsContainer;
    private Button thumbsUpButton;
    private Button runButton;
    private TextView selectionStatusText;

    // Legacy buttons
    private Button confirmSuccessButton;

    private ClojureIterationManager iterationManager;

    // Note: screenshotsContainer removed - screenshots handled via paperclip button

    // Note: Toggle buttons and containers removed - functionality moved to chat
    // history

    // Track expansion state
    private boolean codeExpanded = false;
    private boolean screenshotsExpanded = false;
    private boolean logcatExpanded = false;

    // Add field to store current screenshots
    private List<File> currentScreenshots = new ArrayList<>();

    // Add LLM type spinner
    private Spinner llmTypeSpinner;
    private Spinner llmSpinner;
    private Button clearApiKeyButton;
    private LLMClientFactory.LLMType defaultLLMType = LLMClientFactory.LLMType.GEMINI;

    // Add this field at the top of the class
    private AlertDialog apiKeyDialog;

    // Add field to track selected screenshot for multimodal feedback
    private List<File> selectedScreenshots = new ArrayList<>();

    // Add session support
    private SessionManager sessionManager;
    private DesignSession currentSession;

    // Track selected chat entry
    private int selectedChatEntryIndex = -1;
    private LinearLayout selectedChatEntry = null;

    // Track the iteration number for the currently running code
    private int currentRunningIteration = -1;

    // Add a flag to track when the models are loaded
    private boolean modelsLoaded = false;

    // Add field to track whether line numbers are showing
    private boolean showingLineNumbers = true;

    // Track expanded code sections and system prompt
    private Set<Integer> expandedCodeSections = new HashSet<>();
    private Set<Integer> expandedLogcatSections = new HashSet<>();
    private boolean systemPromptExpanded = false;

    // Add fields for automatic iteration and cancel functionality
    private boolean autoIterateOnError = true;
    private boolean isIterating = false;
    private AlertDialog iterationProgressDialog;
    private AlertDialog initialGenerationProgressDialog;
    private Button cancelIterationButton;

    private void createNewSession() {
        // Create a new session
        currentSession = new DesignSession();
        currentSession.setSessionName(UNNAMED_SESSION);
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
        currentSession.setSessionName(description); // Use description as session name initially
        currentSession.setDescription(description);
        currentSession.setInitialCode(initialCode);
        currentSession.setLlmType(defaultLLMType);

        sessionManager.updateSession(currentSession);

        // Note: The description input will remain enabled until the first code
        // generation
        // This allows users to edit the description before starting AI improvement
        Toast.makeText(this,
                "You can run this code now or click 'Improve App' to enhance it with AI",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Initialize all UI views
     */
    private void initializeViews() {
        // Initialize views
        chatHistoryContainer = findViewById(R.id.chat_history_container);
        chatHistoryLayout = findViewById(R.id.chat_history_layout);
        feedbackInput = findViewById(R.id.feedback_input);
        paperclipButton = findViewById(R.id.paperclip_button);
        screenshotView = findViewById(R.id.screenshot_view);

        // Note: currentCodeView and logcatOutput removed as they're now handled in chat
        // history

        // New feedback UI
        feedbackButtonsContainer = findViewById(R.id.feedback_buttons_container);
        thumbsUpButton = findViewById(R.id.thumbs_up_button);
        runButton = findViewById(R.id.run_button);
        cancelIterationButton = findViewById(R.id.cancel_iteration_button);
        selectionStatusText = findViewById(R.id.selection_status_text);

        // Legacy buttons to maintain compatibility
        confirmSuccessButton = findViewById(R.id.confirm_success_button);

        // Note: Toggle buttons and containers removed - functionality moved to chat
        // history

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
                    assert false;
                }

                if (currentSession.getLlmType() != selectedType) {
                    currentSession.setLlmType(selectedType);
                    // We're changing the model provider so clear the model name in the current
                    // session, but do this only if we haven't started generating code yet.
                    currentSession.setLlmModel(null);
                    sessionManager.updateSession(currentSession);
                    Log.d(TAG, "Clearing LLM model for new model provider. Session " + currentSession.getId().toString()
                            + " has " + currentSession.getChatHistory().size() + " messages.");

                    // Check if we need to shut down the existing iterationManager
                    if (iterationManager != null) {
                        Log.d(TAG, "Shutting down existing iterationManager before creating a new one");
                        iterationManager.shutdown();
                        iterationManager = null;
                    }
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
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setOnClickListener(v -> handleSubmitFeedbackClick());
        thumbsUpButton.setOnClickListener(v -> acceptApp());
        runButton.setOnClickListener(v -> runSelectedCode(false));
        cancelIterationButton.setOnClickListener(v -> cancelCurrentIteration());

        // Set up paperclip button for screenshot attachment
        paperclipButton.setOnClickListener(v -> showScreenshotSelectionForChat());

        // Initialize paperclip button state
        updatePaperclipButtonState();

        // Legacy button listeners
        confirmSuccessButton.setOnClickListener(v -> acceptApp());

        // Hide feedback buttons initially - they should only be visible after
        // generation
        feedbackButtonsContainer.setVisibility(View.GONE);

        // Make feedback input always visible for chat-like interface
        feedbackInput.setVisibility(View.VISIBLE);

        // Note: screenshotsContainer no longer needed as screenshots are handled via
        // paperclip button

        // Note: Screenshots now handled via paperclip button in chat input

        // Make sure screenshot view is initially visible
        if (screenshotView != null) {
            screenshotView.setVisibility(View.VISIBLE);
        }

    }

    /**
     * Setup session state based on the current session
     */
    private void setupSessionState() {
        assert currentSession != null;

        // Update session name display
        updateSessionNameDisplay();

        // If we loaded an existing session, update the UI
        if (currentSession.getDescription() != null) {
            Log.d(TAG, "Setting up session state: " +
                    "id=" + currentSession.getId().toString() +
                    ", description=" + (currentSession.getDescription() != null ? "present" : "null") +
                    ", initialCode=" + (currentSession.getInitialCode() != null ? "present" : "null") +
                    ", code=" + (currentSession.getCurrentCode() != null ? "present" : "null") +
                    ", llmType=" + (currentSession.getLlmType() != null ? currentSession.getLlmType() : "null") +
                    ", llmModel=" + (currentSession.getLlmModel() != null ? currentSession.getLlmModel() : "null"));

            // Pre-populate the feedback input with the session description only for
            // completely new sessions
            if (currentSession.getCurrentCode() == null && currentSession.getInitialCode() == null) {
                feedbackInput.setText(currentSession.getDescription());
                // Select all text so user can easily delete or modify it
                feedbackInput.setSelection(0, currentSession.getDescription().length());
            }

            if (currentSession.getCurrentCode() != null) {
                String currentCode = currentSession.getCurrentCode();

                displayCurrentCode();

                // Show feedback buttons
                feedbackButtonsContainer.setVisibility(View.VISIBLE);
                thumbsUpButton.setVisibility(View.VISIBLE);
                runButton.setVisibility(View.VISIBLE);

                // Show chat history
                chatHistoryContainer.setVisibility(View.VISIBLE);
                updateChatHistoryDisplay();
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
                assert iterationManager == null : "iterationManager should be null before creating new instance";
                iterationManager = createIterationManagerWithSystemPrompt(currentSession);

                // We'll wait until llmTypeSpinner's selection listener fires to trigger model
                // enumeration
            }
        } else {
            Log.d(TAG, "Session restore: LLM type not set");
        }

        // Note: Logcat output now shown in chat history, no separate display needed

        // Restore latest screenshot set if available
        List<String> paths = currentSession.getCurrentIterationScreenshots();
        if (paths != null && !paths.isEmpty()) {
            Log.d(TAG, "Restoring " + paths.size() + " screenshots from current iteration");

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
        } else {
            // No screenshots in latest set, clear currentScreenshots
            currentScreenshots.clear();
            Log.d(TAG, "No screenshots available in current iteration");
        }

        // Restore error feedback if available
        // First check if we have an iteration-specific error for the latest AI response
        String errorToRestore = null;
        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (messages != null && !messages.isEmpty()) {
            // Find the latest AI response
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                    // Check if this AI response contains code
                    LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) messages.get(i);
                    String extractedCode = assistantMsg.getExtractedCode();

                    if (extractedCode != null && !extractedCode.isEmpty()) {
                        // Only check for iteration-specific errors for AI responses that contain code
                        int latestIteration = getIterationNumberForMessage(i);
                        String iterationError = currentSession.getIterationError(latestIteration);
                        if (iterationError != null && !iterationError.trim().isEmpty()) {
                            errorToRestore = iterationError;
                            Log.d(TAG,
                                    "Found iteration-specific error for latest response (iteration " + latestIteration
                                            + ")");
                        }
                    } else {
                        Log.d(TAG, "Latest AI response contains no code, will not load iteration-specific error");
                    }
                    break;
                }
            }
        }

        if (errorToRestore != null) {
            feedbackInput.setText(errorToRestore);
            Log.d(TAG, "Restored iteration-specific error feedback from session");
        } else {
            // Restore saved input state if no iteration-specific error feedback
            restoreCurrentInputState();
        }

        // Update paperclip button state after session restoration is complete
        updatePaperclipButtonState();
    }

    /**
     * Handles clicks on the Generate button
     * - Initial click: generates the first version
     * - Subsequent clicks: shows feedback dialog for iteration
     * - If non-latest AI response is selected: shows fork dialog
     */
    private void handleSubmitFeedbackClick() {
        // Get the feedback text from the input field
        String feedbackText = feedbackInput.getText().toString().trim();

        if (feedbackText.isEmpty()) {
            feedbackInput.setError("Please enter some text");
            return;
        }

        // Check if we have an existing session with AI responses to provide feedback on
        boolean hasExistingSession = currentSession.getIterationCount() > 0;

        Log.d(TAG, "handleSubmitFeedbackClick: hasExistingSession=" + hasExistingSession +
                ", iterationCount=" + currentSession.getIterationCount() +
                ", selectedChatEntryIndex=" + selectedChatEntryIndex);

        if (hasExistingSession) {
            // Check if the user has selected a non-latest AI response for forking
            boolean requiresFork = doesSelectedMessageRequireFork();
            Log.d(TAG, "handleSubmitFeedbackClick: requiresFork=" + requiresFork);

            if (requiresFork) {
                showForkConfirmationDialog(feedbackText);
                return;
            }

            // We have existing session, so this is feedback for improvement
            Log.d(TAG, "Submitting feedback for existing session: " + feedbackText);

            // Save the selected screenshots before clearing them
            List<File> imagesToSubmit = new ArrayList<>(selectedScreenshots);

            // Clear the input field and saved state for existing code feedback
            feedbackInput.setText("");
            currentSession.setCurrentInputText(null);
            currentSession.setSelectedImagePaths(null);
            selectedScreenshots.clear();
            paperclipButton.setText("📎"); // Reset paperclip button

            submitFeedbackWithText(feedbackText, imagesToSubmit);
        } else {
            // No existing code, so this is initial app description
            Log.d(TAG, "Starting new design with description: " + feedbackText);

            // Save current input state before attempting to start new design
            // in case validation fails and we need to restore the text
            saveCurrentInputState();

            // Update the session description
            currentSession.setDescription(feedbackText);
            sessionManager.updateSession(currentSession);

            // Clear the input field optimistically
            feedbackInput.setText("");

            // Start a new design - if this fails due to validation,
            // the text will be restored in startNewDesign()
            startNewDesign(feedbackText);
        }
    }

    private void startNewDesign(String originalFeedbackText) {
        String description = currentSession.getDescription();
        if (description == null || description.isEmpty()) {
            Toast.makeText(this, "Please enter a description", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get the current LLM type and model
        LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
        String selectedModel = (String) llmSpinner.getSelectedItem();

        // Check if a model has been selected
        if (selectedModel == null) {
            Toast.makeText(this, "Please select a model before generating code", Toast.LENGTH_SHORT).show();
            // Restore the user's input text and selected screenshot since validation failed
            restoreCurrentInputState();
            return;
        }

        // Check if the selected model is the prompt item
        if (selectedModel.equals("-- Select a model --")) {
            Toast.makeText(this, "Please select a specific model from the dropdown", Toast.LENGTH_SHORT).show();
            // Restore the user's input text and selected screenshot since validation failed
            restoreCurrentInputState();
            return;
        }

        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEW APP DESIGN           ║\n" +
                "╚═══════════════════════════════════════════╝");

        // Clear saved input state since we successfully passed validation
        // and are now starting the generation
        currentSession.setCurrentInputText(null);
        currentSession.setSelectedImagePaths(null);
        selectedScreenshots.clear();
        paperclipButton.setText("📎"); // Reset paperclip button

        // Disable the submit button during generation
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setEnabled(false);

        if (iterationManager != null) {
            Log.d(TAG, "Shutting down existing iterationManager before creating a new one");
            iterationManager.shutdown();
            iterationManager = null;
        }

        iterationManager = createIterationManagerWithSystemPrompt(currentSession);

        // Update paperclip button state for new iteration manager
        updatePaperclipButtonState();

        // Check if we have existing code to use as a starting point
        String initialCode = currentSession.getInitialCode();
        if (initialCode != null && !initialCode.isEmpty()) {
            Log.d(TAG, "Using existing code as a starting point. Length: " + initialCode.length());
            Toast.makeText(this, "Using existing code as a starting point", Toast.LENGTH_SHORT).show();
        }

        // Create a custom progress dialog with cancel button
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Generating Initial Code");

        // Create a custom layout for the dialog
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        // Add progress message
        TextView progressText = new TextView(this);
        progressText.setText("Generating initial code...");
        progressText.setTextSize(16);
        layout.addView(progressText);

        // Add some spacing
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20));
        layout.addView(spacer);

        // Add cancel button
        Button dialogCancelButton = new Button(this);
        dialogCancelButton.setText("Cancel Generation");
        dialogCancelButton.setOnClickListener(v -> {
            cancelInitialGeneration();
            // The dialog will be dismissed in cancelInitialGeneration()
        });
        layout.addView(dialogCancelButton);

        builder.setView(layout);
        builder.setCancelable(false);

        initialGenerationProgressDialog = builder.create();
        initialGenerationProgressDialog.show();

        // Manage message history and call sendMessages directly
        LLMClient.ChatSession chatSession = iterationManager.getLLMClient().getChatSession();

        // Format initial prompt and queue user message (system prompt already queued
        // when creating iteration manager)
        String prompt = iterationManager.getLLMClient().formatInitialPrompt(description, initialCode);
        chatSession.queueUserMessage(new LLMClient.UserMessage(prompt, null, null, initialCode));

        // Call sendMessages directly
        iterationManager.sendMessages(chatSession)
                .thenAccept(assistantMessage -> {
                    String code = assistantMessage.getExtractedCode();
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        if (initialGenerationProgressDialog != null) {
                            initialGenerationProgressDialog.dismiss();
                            initialGenerationProgressDialog = null;
                        }

                        // Check if code extraction succeeded
                        if (code == null) {
                            handleCodeExtractionError("No code found in LLM response");
                            // Restore the user's input text since the operation failed
                            feedbackInput.setText(originalFeedbackText);
                            feedbackInput.setSelection(originalFeedbackText.length()); // Move cursor to end
                            return;
                        }

                        // Update session with code
                        currentSession.setCurrentCode(code);
                        sessionManager.updateSession(currentSession);

                        displayCurrentCode();

                        // Show the feedback buttons
                        feedbackButtonsContainer.setVisibility(View.VISIBLE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);

                        // Show chat history
                        chatHistoryContainer.setVisibility(View.VISIBLE);
                        updateChatHistoryDisplayWithLatestSelection();

                        // Only launch RenderActivity after we have the code
                        if (code != null && !code.isEmpty()) {
                            runSelectedCode(true);
                        }

                        // Re-enable the submit button
                        submitFeedbackButton.setEnabled(true);
                    });
                })
                .exceptionally(throwable -> {
                    // Remove the user message we added before sendMessages (1 message)
                    // System prompt stays in the chat session permanently
                    chatSession.removeLastMessages(1);
                    Log.d(TAG, "Removed 1 message (user message) due to failure in initial generation");

                    // Check if this is a cancellation exception, which is expected behavior
                    if (throwable instanceof CancellationException ||
                            (throwable instanceof CompletionException &&
                                    throwable.getCause() instanceof CancellationException)) {
                        Log.d(TAG, "Initial generation was cancelled - this is expected behavior");
                        runOnUiThread(() -> {
                            // Dismiss progress dialog
                            if (initialGenerationProgressDialog != null) {
                                initialGenerationProgressDialog.dismiss();
                                initialGenerationProgressDialog = null;
                            }

                            // Restore the user's input text since the operation was cancelled
                            feedbackInput.setText(originalFeedbackText);
                            feedbackInput.setSelection(originalFeedbackText.length()); // Move cursor to end

                            // Re-enable the submit button
                            submitFeedbackButton.setEnabled(true);
                        });
                        return null;
                    }

                    // Handle actual errors
                    Log.e(TAG, "Error generating code", throwable);
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        if (initialGenerationProgressDialog != null) {
                            initialGenerationProgressDialog.dismiss();
                            initialGenerationProgressDialog = null;
                        }

                        // Restore the user's input text since the operation failed
                        feedbackInput.setText(originalFeedbackText);
                        feedbackInput.setSelection(originalFeedbackText.length()); // Move cursor to end

                        showLLMErrorDialog("Code Generation Error",
                                "Error generating code: " + throwable.getMessage());
                        submitFeedbackButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void submitFeedbackWithText(String feedback) {
        submitFeedbackWithText(feedback, selectedScreenshots);
    }

    private void submitFeedbackWithText(String feedback, List<File> images) {
        Log.d(TAG, "\n" +
                "╔═══════════════════════════════════════════╗\n" +
                "║         STARTING NEXT ITERATION           ║\n" +
                "╚═══════════════════════════════════════════╝");

        Log.d(TAG, "submitFeedbackWithText: feedback=" +
                (feedback != null ? "present (len=" + feedback.length() + ")" : "null") +
                ", images=" + (images != null ? images.size() + " selected" : "none") +
                ", iterationManager=" + (iterationManager != null ? "present" : "null") +
                ", LLM client="
                + (iterationManager != null && iterationManager.getLLMClient() != null ? "initialized" : "null"));

        // Check if models are still loading
        if (!modelsLoaded) {
            Toast.makeText(this, "Models are still loading, please wait a moment and try again", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        // Get the submit feedback button reference
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);

        // Make sure buttons are enabled
        thumbsUpButton.setEnabled(true);
        runButton.setEnabled(true);
        submitFeedbackButton.setEnabled(true);

        // Ensure we have a valid description
        String currentDescription = currentSession.getDescription();
        if (currentDescription == null || currentDescription.isEmpty()) {
            Toast.makeText(this, "No description available. Please start a new session.", Toast.LENGTH_SHORT).show();
            // Keep the text in the input field by restoring it
            feedbackInput.setText(feedback);
            feedbackInput.setSelection(feedback.length()); // Move cursor to end
            return;
        }

        // Check if iterationManager is available
        if (iterationManager == null) {
            Toast.makeText(this, "No iteration manager available. Please select a model first.", Toast.LENGTH_SHORT)
                    .show();
            // Keep the text in the input field by restoring it
            feedbackInput.setText(feedback);
            feedbackInput.setSelection(feedback.length()); // Move cursor to end
            return;
        }

        // Ensure we have current code
        String currentCode = currentSession.getCurrentCode();
        if (currentCode == null || currentCode.isEmpty()) {
            Toast.makeText(this, "No code to improve. Please generate initial code first.", Toast.LENGTH_SHORT).show();
            // Keep the text in the input field by restoring it
            feedbackInput.setText(feedback);
            feedbackInput.setSelection(feedback.length()); // Move cursor to end
            return;
        }

        // Get the current screenshot if any
        File currentScreenshot = null;
        if (!currentScreenshots.isEmpty()) {
            currentScreenshot = currentScreenshots.get(currentScreenshots.size() - 1);
        }

        // Get the current logcat output from session
        String logcatText = currentSession.getLastLogcat() != null ? currentSession.getLastLogcat() : "";

        // Disable buttons during generation
        thumbsUpButton.setEnabled(false);
        runButton.setEnabled(false);
        submitFeedbackButton.setEnabled(false);

        // Create a custom progress dialog with cancel button
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Generating Next Iteration");

        // Create a custom layout for the dialog
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        // Add progress message
        TextView progressText = new TextView(this);
        progressText.setText("Generating next iteration...");
        progressText.setTextSize(16);
        layout.addView(progressText);

        // Add some spacing
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20));
        layout.addView(spacer);

        // Add cancel button
        Button dialogCancelButton = new Button(this);
        dialogCancelButton.setText("Cancel Iteration");
        dialogCancelButton.setOnClickListener(v -> {
            cancelManualIteration(feedback, images);
            // The dialog will be dismissed in cancelManualIteration()
        });
        layout.addView(dialogCancelButton);

        builder.setView(layout);
        builder.setCancelable(false);

        iterationProgressDialog = builder.create();
        iterationProgressDialog.show();

        // Check if images are provided and model is multimodal
        if (images != null && !images.isEmpty()) {
            LLMClient.ModelProperties props = iterationManager.getModelProperties();
            if (props == null || !props.isMultimodal) {
                showLLMErrorDialog("Model Error", "Images parameter provided but model is not multimodal");
                // Make sure buttons are enabled on error
                thumbsUpButton.setEnabled(true);
                runButton.setEnabled(true);
                submitFeedbackButton.setEnabled(true);
                return;
            }
        }

        // Manage message history and call sendMessages directly
        LLMClient.ChatSession chatSession = iterationManager.getLLMClient().getChatSession();

        // Format the iteration prompt
        String prompt = iterationManager.getLLMClient().formatIterationPrompt(currentSession.getDescription(),
                currentCode, logcatText, feedback, images != null && !images.isEmpty());

        // Queue the user message (with images attachment if provided)
        LLMClient.UserMessage userMessage = new LLMClient.UserMessage(prompt, images, logcatText, feedback, null);
        chatSession.queueUserMessage(userMessage);

        // Call sendMessages directly
        iterationManager.sendMessages(chatSession)
                .thenAccept(assistantMessage -> {
                    String code = assistantMessage.getExtractedCode();
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        if (iterationProgressDialog != null) {
                            iterationProgressDialog.dismiss();
                            iterationProgressDialog = null;
                        }

                        // Check if code extraction succeeded
                        if (code == null) {
                            handleCodeExtractionError("No code found in LLM response");
                            // Restore the user's input text since the operation failed
                            feedbackInput.setText(feedback);
                            feedbackInput.setSelection(feedback.length()); // Move cursor to end
                            return;
                        }

                        assert currentSession != null;

                        currentSession.setCurrentCode(code);
                        sessionManager.updateSession(currentSession);

                        displayCurrentCode();

                        // Make sure buttons are enabled after response
                        thumbsUpButton.setEnabled(true);
                        runButton.setEnabled(true);
                        submitFeedbackButton.setEnabled(true);

                        // Update chat history and force selection of the latest AI response
                        updateChatHistoryDisplayWithLatestSelection();

                        runSelectedCode(true);
                    });
                })
                .exceptionally(throwable -> {
                    // Remove the user message we added before sendMessages (1 message)
                    chatSession.removeLastMessages(1);
                    Log.d(TAG, "Removed 1 message (user message) due to failure in generateNextIteration");

                    // Check if this is a cancellation exception, which is expected behavior
                    if (throwable instanceof CancellationException ||
                            (throwable instanceof CompletionException &&
                                    throwable.getCause() instanceof CancellationException)) {
                        Log.d(TAG, "Manual iteration was cancelled - this is expected behavior");
                        runOnUiThread(() -> {
                            // Dismiss progress dialog
                            if (iterationProgressDialog != null) {
                                iterationProgressDialog.dismiss();
                                iterationProgressDialog = null;
                            }

                            // Restore the user's input text since the operation was cancelled
                            feedbackInput.setText(feedback);
                            feedbackInput.setSelection(feedback.length()); // Move cursor to end

                            // Restore the selected screenshots if any were attached
                            if (images != null && !images.isEmpty()) {
                                selectedScreenshots.clear();
                                selectedScreenshots.addAll(images);
                                paperclipButton.setText("📎✓ (" + selectedScreenshots.size() + ")"); // Visual feedback
                                                                                                     // that screenshots
                                                                                                     // are selected
                            }

                            // Make sure buttons are enabled after cancellation
                            thumbsUpButton.setEnabled(true);
                            runButton.setEnabled(true);
                            submitFeedbackButton.setEnabled(true);
                        });
                        return null;
                    }

                    // Handle actual errors
                    Log.e(TAG, "Error generating next iteration", throwable);
                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        if (iterationProgressDialog != null) {
                            iterationProgressDialog.dismiss();
                            iterationProgressDialog = null;
                        }

                        // Restore the user's input text since the operation failed
                        feedbackInput.setText(feedback);
                        feedbackInput.setSelection(feedback.length()); // Move cursor to end

                        // Restore the selected screenshots if any were attached
                        if (images != null && !images.isEmpty()) {
                            selectedScreenshots.addAll(images);
                            paperclipButton.setText("📎✓"); // Visual feedback that screenshots are selected
                        }

                        showLLMErrorDialog("Iteration Error",
                                "Error generating next iteration: " + throwable.getMessage());
                        // Make sure buttons are enabled on error
                        thumbsUpButton.setEnabled(true);
                        runButton.setEnabled(true);
                        submitFeedbackButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void acceptApp() {
        // Re-enable buttons after accepting
        thumbsUpButton.setEnabled(true);
        runButton.setEnabled(true);

        // Get code from the selected message (AI response or last AI response before
        // user message)
        if (selectedChatEntryIndex < 0 || currentSession == null) {
            Toast.makeText(this, "No message selected", Toast.LENGTH_SHORT).show();
            return;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (selectedChatEntryIndex >= messages.size()) {
            Toast.makeText(this, "Selected message not found", Toast.LENGTH_SHORT).show();
            return;
        }

        LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);

        // Get code for execution using smart fallback logic
        CodeSearchResult codeResult = getCodeForExecution(selectedMessage, selectedChatEntryIndex, "Accept");

        if (!codeResult.found) {
            Toast.makeText(this, "No code found in message history. Please generate code first.", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        String currentCode = codeResult.code;
        int codeMessageIndex = codeResult.messageIndex;

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
        intent.putExtra(MainActivity.EXTRA_DESCRIPTION, currentSession.getDescription());
        startActivity(intent);

        // Save this final version
        saveCodeToFile();

        Toast.makeText(this, "Code accepted and sent to REPL", Toast.LENGTH_LONG).show();

        finish(); // Close this activity
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Save current input state before destroying
        saveCurrentInputState();

        if (iterationManager != null) {
            iterationManager.shutdown();
        }

        if (apiKeyDialog != null && apiKeyDialog.isShowing()) {
            apiKeyDialog.dismiss();
        }

        if (iterationProgressDialog != null && iterationProgressDialog.isShowing()) {
            iterationProgressDialog.dismiss();
        }

        if (initialGenerationProgressDialog != null && initialGenerationProgressDialog.isShowing()) {
            initialGenerationProgressDialog.dismiss();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);

        // Ensure submit button is enabled when returning from RenderActivity
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setEnabled(true);

        // Make sure view containers are visible
        screenshotView.setVisibility(View.VISIBLE);
        // Note: screenshotsContainer removed - handled via paperclip

        boolean doUpdateSession = false;
        // First check for screenshot data (existing functionality)
        if (intent.hasExtra(RenderActivity.EXTRA_RESULT_SCREENSHOT_PATHS)) {
            String[] screenshotPaths = intent.getStringArrayExtra(RenderActivity.EXTRA_RESULT_SCREENSHOT_PATHS);
            Log.d(TAG, "Received " + screenshotPaths.length + " screenshots in onNewIntent");

            // Save the screenshots for future reference
            // Don't clear if we're adding to the same iteration
            for (String path : screenshotPaths) {
                currentScreenshots.add(new File(path));
            }

            // Display all screenshots
            displayScreenshots(screenshotPaths);

            // Save screenshots to session
            if (currentSession != null && screenshotPaths.length > 0) {
                List<String> paths = new ArrayList<>(Arrays.asList(screenshotPaths));

                // Use the correct iteration number for the screenshot set
                if (currentRunningIteration > 0) {
                    currentSession.addScreenshotSet(paths, currentRunningIteration);
                    Log.d(TAG, "Added a new set of " + paths.size() + " screenshots to session for iteration "
                            + currentRunningIteration);
                } else {
                    // Fallback to legacy method if iteration number not available
                    currentSession.addScreenshotSet(paths);
                    Log.w(TAG, "Added screenshots using legacy method (iteration number not available)");
                }
                doUpdateSession = true;
            }

            // Update paperclip button state now that we have screenshots
            updatePaperclipButtonState();
        }

        // Save the current code when returning from RenderActivity
        saveCodeToFile();

        // Check for error feedback from RenderActivity
        if (intent.hasExtra(RenderActivity.EXTRA_RESULT_ERROR)) {
            Log.d(TAG, "RenderActivity returned error status: "
                    + intent.getStringExtra(RenderActivity.EXTRA_RESULT_ERROR));
            String errorFeedback = intent.getStringExtra(RenderActivity.EXTRA_RESULT_ERROR);

            // Pre-fill the feedback input
            if (feedbackInput != null) {
                feedbackInput.setText(errorFeedback);
            }

            // Save error info to session (both legacy and iteration-specific)
            if (currentSession != null) {
                currentSession.setLastErrorFeedback(errorFeedback);
                currentSession.setHasError(true);

                // Save error for the specific iteration that was just run
                if (currentRunningIteration > 0) {
                    currentSession.setIterationError(currentRunningIteration, errorFeedback);
                    Log.d(TAG, "Saved error feedback for iteration " + currentRunningIteration + ": " + errorFeedback);
                } else {
                    Log.w(TAG, "No current running iteration to associate error with");
                }

                doUpdateSession = true;
                Log.d(TAG, "Saved error feedback to session");
            }

            // Check if we should automatically iterate on error
            if (intent.hasExtra(RenderActivity.EXTRA_RESULT_AUTO_RETURN_ON_ERROR) &&
                    intent.getBooleanExtra(RenderActivity.EXTRA_RESULT_AUTO_RETURN_ON_ERROR, false) &&
                    autoIterateOnError && !isIterating) {
                Log.d(TAG, "Auto-iterating on error");
                startAutomaticIteration(errorFeedback);
            } else {
                // Note: showFeedbackDialog() removed - feedback is now handled through the chat
                // input
            }
        } else {
            Log.d(TAG, "RenderActivity returned with no error status");
            currentSession.setLastErrorFeedback(null);
            currentSession.setHasError(false);

            // Clear error for the specific iteration that was just run successfully
            if (currentRunningIteration > 0) {
                currentSession.setIterationError(currentRunningIteration, null);
                Log.d(TAG, "Cleared error for iteration " + currentRunningIteration);
            }

            doUpdateSession = true;

            // Clear the input field since there's no error
            if (feedbackInput != null) {
                feedbackInput.setText("");
            }
        }

        if (doUpdateSession) {
            sessionManager.updateSession(currentSession);
        }

        // Reset the running iteration after all processing is complete
        currentRunningIteration = -1;
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
        // Screenshots are now handled via paperclip button in chat input
        // Just save them to currentScreenshots for selection via paperclip

        if (screenshotPaths == null || screenshotPaths.length == 0) {
            Log.d(TAG, "No screenshots to save");
            return;
        }

        Log.d(TAG, "Saving " + screenshotPaths.length + " screenshots for paperclip selection");

        // Add new screenshots to current screenshots list (don't clear to accumulate
        // within iteration)
        for (String path : screenshotPaths) {
            File screenshotFile = new File(path);
            if (screenshotFile.exists()) {
                currentScreenshots.add(screenshotFile);
                Log.d(TAG, "Saved screenshot for paperclip selection: " + path);
            } else {
                Log.e(TAG, "Screenshot file doesn't exist: " + path);
            }
        }

        Log.d(TAG, "Total screenshots available for selection: " + currentScreenshots.size());
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

                // Handle the prompt selection (position 0) - clear the model
                if (position == 0) {
                    Log.d(TAG, "Prompt item selected, clearing LLM model");

                    // Shut down existing iteration manager
                    if (iterationManager != null) {
                        Log.d(TAG, "Shutting down existing iterationManager due to model deselection");
                        iterationManager.shutdown();
                        iterationManager = null;
                    }

                    // Clear the model from the session
                    if (currentSession != null) {
                        currentSession.setLlmModel(null);
                        sessionManager.updateSession(currentSession);
                        Log.d(TAG, "Cleared LLM model from session");
                    }

                    // Update paperclip button state since no model is selected
                    updatePaperclipButtonState();

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

                    // Update session with new model
                    assert currentSession != null;
                    Log.d(TAG, "Type: " + currentType + ", LLM: " +
                            selectedModel + ". Session " + currentSession.getId().toString() +
                            " has " + currentSession.getChatHistory().size() + " messages.");
                    assert currentSession.getLlmType() == currentType;
                    currentSession.setLlmModel(selectedModel);
                    sessionManager.updateSession(currentSession);

                    iterationManager = createIterationManagerWithSystemPrompt(currentSession);

                    // Update paperclip button state for new model
                    updatePaperclipButtonState();
                } else if (sessionRestoreModel != null && selectedModel.equals(sessionRestoreModel)) {
                    Log.d(TAG, "Skipping client creation - already using session model: " + sessionRestoreModel);

                    // Still update paperclip button state in case it wasn't set before
                    updatePaperclipButtonState();
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
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit_session_name) {
            showEditSessionNameDialog();
            return true;
        } else if (id == R.id.action_api_key) {
            LLMClientFactory.LLMType currentType = (LLMClientFactory.LLMType) llmTypeSpinner.getSelectedItem();
            showApiKeyDialog(currentType);
            return true;
        } else if (id == R.id.action_clear_api_key) {
            clearCurrentApiKey();
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

    @Override
    public void onBackPressed() {
        // Check if session is unnamed and prompt user to name it
        if (isSessionUnnamed()) {
            showSessionNamingBeforeExitDialog();
        } else {
            super.onBackPressed();
        }
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

            Log.d(TAG, "Code saved to: " + codeFile.getAbsolutePath() + " (Iteration "
                    + currentSession.getIterationCount() + ")");
            // Only show toast for manual saves, not automatic ones
        } catch (Exception e) {
            Log.e(TAG, "Error saving code to file", e);
        }
    }

    /**
     * Runs the code from the selected message (AI response) or the last AI response
     * before selected user message
     */
    private void runSelectedCode(boolean returnOnError) {
        if (selectedChatEntryIndex < 0 || currentSession == null) {
            Toast.makeText(this, "No message selected", Toast.LENGTH_SHORT).show();
            return;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (selectedChatEntryIndex >= messages.size()) {
            Toast.makeText(this, "Selected message not found", Toast.LENGTH_SHORT).show();
            return;
        }

        LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);

        // Get code for execution using smart fallback logic
        CodeSearchResult codeResult = getCodeForExecution(selectedMessage, selectedChatEntryIndex, "Run");

        if (!codeResult.found) {
            Toast.makeText(this, "No code found in message history. Please generate code first.", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        String codeToRun = codeResult.code;
        int codeMessageIndex = codeResult.messageIndex;

        // Calculate the correct iteration number for the code message
        int selectedIteration = getIterationNumberForMessage(codeMessageIndex);

        // Store the iteration number for use when screenshots are returned
        currentRunningIteration = selectedIteration;

        Log.d(TAG,
                "Running code from iteration " + selectedIteration + " (AI response at index " + codeMessageIndex +
                        ", originally selected index " + selectedChatEntryIndex + ")");

        // Start the activity with the selected code
        RenderActivity.launch(this, ClojureAppDesignActivity.class,
                new RenderActivity.ExitCallback() {
                    @Override
                    public void onExit(String logcat) {
                        runOnUiThread(() -> {
                            // Save logcat to session
                            if (currentSession != null && logcat != null && !logcat.isEmpty()) {
                                currentSession.setLastLogcat(logcat);
                                sessionManager.updateSession(currentSession);
                                Log.d(TAG, "Saved logcat to session");
                            }
                        });
                    }
                },
                codeToRun,
                currentSession.getId().toString(),
                selectedIteration, // Use the correct iteration number
                true,
                returnOnError); // Enable return_on_error flag
    }

    /**
     * Finds the last AI response message before the given message index.
     *
     * @param messageIndex The index to search before
     * @return The index of the last AI response before messageIndex, or -1 if none
     *         found
     */
    private int findLastAiResponseBefore(int messageIndex) {
        if (currentSession == null) {
            return -1;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (messages == null || messageIndex <= 0) {
            return -1;
        }

        // Search backwards from the message before the given index
        for (int i = messageIndex - 1; i >= 0; i--) {
            if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Result class for AI response code search operations
     */
    private static class CodeSearchResult {
        public final int messageIndex;
        public final String code;
        public final boolean found;

        private CodeSearchResult(int messageIndex, String code, boolean found) {
            this.messageIndex = messageIndex;
            this.code = code;
            this.found = found;
        }

        public static CodeSearchResult success(int messageIndex, String code) {
            return new CodeSearchResult(messageIndex, code, true);
        }

        public static CodeSearchResult notFound() {
            return new CodeSearchResult(-1, null, false);
        }
    }

    /**
     * Finds the last AI response message with code, searching backwards from the
     * given message index.
     * If messageIndex is -1, searches from the end of the message history.
     *
     * @param messageIndex The index to search before, or -1 to search from the end
     * @return CodeSearchResult containing the index and extracted code, or
     *         notFound() if none found
     */
    private CodeSearchResult findLastAiResponseWithCode(int messageIndex) {
        if (currentSession == null) {
            return CodeSearchResult.notFound();
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (messages == null || messages.isEmpty()) {
            return CodeSearchResult.notFound();
        }

        // Determine starting point for search
        int startIndex = (messageIndex == -1) ? messages.size() - 1 : messageIndex - 1;

        // Search backwards from the starting index
        for (int i = startIndex; i >= 0; i--) {
            if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                // Check if this AI response contains code
                LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) messages.get(i);
                String extractedCode = assistantMsg.getExtractedCode();
                if (extractedCode != null && !extractedCode.isEmpty()) {
                    return CodeSearchResult.success(i, extractedCode);
                }
            }
        }

        return CodeSearchResult.notFound();
    }

    /**
     * Gets code for execution based on the selected message with smart fallback.
     * Handles both AI responses and user messages with consistent fallback logic.
     *
     * @param selectedMessage The currently selected message
     * @param selectedIndex   The index of the selected message
     * @param operation       A description of the operation for logging (e.g.,
     *                        "Accept", "Run")
     * @return CodeSearchResult containing the code and its message index, or
     *         notFound() if no code available
     */
    private CodeSearchResult getCodeForExecution(LLMClient.Message selectedMessage, int selectedIndex,
            String operation) {
        if (selectedMessage.role == LLMClient.MessageRole.USER) {
            // User message selected - search backwards for any AI response with code
            CodeSearchResult result = findLastAiResponseWithCode(selectedIndex);
            if (result.found) {
                Log.d(TAG, "User message selected for " + operation + ", using code from AI response at index "
                        + result.messageIndex);
            }
            return result;
        } else if (selectedMessage.role == LLMClient.MessageRole.ASSISTANT) {
            // AI response selected - check if it has code, otherwise fall back to search
            LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) selectedMessage;
            String extractedCode = assistantMsg.getExtractedCode();

            if (extractedCode != null && !extractedCode.isEmpty()) {
                // Use code from the selected AI response if it contains code
                return CodeSearchResult.success(selectedIndex, extractedCode);
            } else {
                // If selected AI response doesn't contain code, search backwards for the last
                // AI response with code
                CodeSearchResult result = findLastAiResponseWithCode(selectedIndex);
                if (result.found) {
                    Log.d(TAG, "Selected AI response contains no code for " + operation
                            + ", using code from message at index " + result.messageIndex);
                }
                return result;
            }
        } else {
            // Invalid message type
            return CodeSearchResult.notFound();
        }
    }

    /**
     * Calculates the iteration number for a given message index.
     * Assistant messages with code are numbered starting from 1.
     *
     * @param messageIndex The index of the message in the chat history
     * @return The iteration number (1-based) for the message
     */
    private int getIterationNumberForMessage(int messageIndex) {

        assert currentSession != null;

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        assert messages != null && messageIndex >= 0 && messageIndex < messages.size();

        // Count assistant messages with code up to and including the selected message
        int iterationNumber = 0;
        for (int i = 0; i <= messageIndex; i++) {
            LLMClient.Message message = messages.get(i);
            if (message.role == LLMClient.MessageRole.ASSISTANT) {
                // Check if this assistant message contains code
                LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) message;
                String extractedCode = assistantMsg.getExtractedCode();
                if (extractedCode != null && !extractedCode.isEmpty()) {
                    iterationNumber++;
                }
            }
        }

        assert iterationNumber > 0;
        return iterationNumber;
    }

    /**
     * Gets the screenshots for a specific iteration number.
     * 
     * @param iterationNumber The iteration number (1-based)
     * @return List of screenshot file paths for that iteration, or empty list if
     *         none found
     */
    private List<String> getScreenshotsForIteration(int iterationNumber) {
        assert currentSession != null;

        List<String> screenshots = currentSession.getScreenshotsForIteration(iterationNumber);
        Log.d(TAG, "Found " + screenshots.size() + " screenshots for iteration " + iterationNumber);
        return screenshots;
    }

    private void clearChatSession() {
        if (iterationManager != null) {
            LLMClient.ChatSession chatSession = currentSession.getChatSession();
            if (chatSession != null) {
                chatSession.reset();
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
     * Clears the generation state, allowing model selection again
     */
    private void clearGeneration() {
        if (iterationManager != null) {
            iterationManager.shutdown();
            iterationManager = null;
        }

        Log.d(TAG, "Generation state cleared");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save current input state to session
        saveCurrentInputState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Note: Description is now managed through chat input
    }

    /**
     * Updates the chat history display
     */
    private void updateChatHistoryDisplay() {
        updateChatHistoryDisplay(true, true); // Default: auto-select last and auto-scroll
    }

    /**
     * Updates the chat history display and forces selection of the latest AI
     * response
     * (ignoring any saved selection). This is used when a new AI response is
     * generated.
     */
    private void updateChatHistoryDisplayWithLatestSelection() {
        // Clear any saved selection to ensure we select the latest AI response
        if (currentSession != null) {
            currentSession.setSelectedMessageIndex(-1);
        }
        updateChatHistoryDisplay(true, true); // Auto-select last (will pick latest), auto-scroll
    }

    /**
     * Updates the chat history display with control over selection and scrolling
     * behavior
     * 
     * @param autoSelectLast Whether to auto-select the last AI response
     * @param autoScroll     Whether to auto-scroll to bottom
     */
    private void updateChatHistoryDisplay(boolean autoSelectLast, boolean autoScroll) {
        if (currentSession != null) {
            List<LLMClient.Message> messages = currentSession.getChatHistory();
            if (messages != null && !messages.isEmpty()) {
                // Determine the correct selection BEFORE rendering the UI
                if (autoSelectLast) {
                    // First try to restore saved selection from session
                    int savedSelection = currentSession.getSelectedMessageIndex();
                    if (savedSelection >= 0 && savedSelection < messages.size()) {
                        LLMClient.MessageRole savedRole = messages.get(savedSelection).role;
                        // Allow both AI responses and user messages to be restored
                        if (savedRole == LLMClient.MessageRole.ASSISTANT || savedRole == LLMClient.MessageRole.USER) {
                            selectedChatEntryIndex = savedSelection;
                            Log.d(TAG, "Restored saved message selection: " + savedSelection + " (" + savedRole + ")");
                        } else {
                            // Fall back to selecting the last (most recent) AI response
                            selectedChatEntryIndex = -1; // Reset selection
                            for (int i = messages.size() - 1; i >= 0; i--) {
                                if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                                    selectedChatEntryIndex = i;
                                    Log.d(TAG, "Auto-selected last AI response: " + selectedChatEntryIndex);
                                    break;
                                }
                            }
                        }
                    } else {
                        // Fall back to selecting the last (most recent) AI response
                        selectedChatEntryIndex = -1; // Reset selection
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                                selectedChatEntryIndex = i;
                                Log.d(TAG, "Auto-selected last AI response: " + selectedChatEntryIndex);
                                break;
                            }
                        }
                    }

                    // Save the selected message index to the session if we made a selection
                    if (selectedChatEntryIndex >= 0) {
                        currentSession.setSelectedMessageIndex(selectedChatEntryIndex);
                        sessionManager.updateSession(currentSession);
                        Log.d(TAG, "Saved auto-selected message index " + selectedChatEntryIndex + " to session");
                    }
                }

                // Clear existing views
                chatHistoryLayout.removeAllViews();

                int messageIndex = 0;
                for (LLMClient.Message message : messages) {
                    // Create message container with margin for better separation
                    LinearLayout messageContainer = new LinearLayout(this);
                    LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    containerParams.setMargins(8, 4, 8, 4); // Add margins between entries
                    messageContainer.setLayoutParams(containerParams);
                    messageContainer.setOrientation(LinearLayout.VERTICAL);
                    messageContainer.setPadding(16, 12, 16, 12);

                    // Make both AI responses and user messages selectable
                    boolean isSelectableMessage = (message.role == LLMClient.MessageRole.ASSISTANT ||
                            message.role == LLMClient.MessageRole.USER);
                    if (isSelectableMessage) {
                        messageContainer.setClickable(true);
                        messageContainer.setFocusable(true);

                        // Set up selection behavior for AI responses and user messages
                        final int currentMessageIndex = messageIndex;
                        messageContainer.setOnClickListener(v -> {
                            selectChatEntry(currentMessageIndex, messageContainer);
                        });
                    }

                    // Add alternating background colors for visual distinction
                    boolean isEvenIndex = (messageIndex % 2 == 0);
                    int defaultBackgroundColor = isEvenIndex ? 0xFFF8F8F8 : 0xFFFFFFFF; // Light gray vs white
                    messageContainer.setBackgroundColor(defaultBackgroundColor);

                    // Add rounded corners and subtle border effect
                    messageContainer.setElevation(2.0f);

                    // Apply selection state if this entry is currently selected (and it's
                    // selectable)
                    if (messageIndex == selectedChatEntryIndex && isSelectableMessage) {
                        messageContainer.setBackgroundColor(0x4400FF00); // Light green selection
                        selectedChatEntry = messageContainer;
                    }

                    // Create role label with better styling
                    TextView roleLabel = new TextView(this);
                    roleLabel.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    roleLabel.setTextSize(13);
                    roleLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                    roleLabel.setPadding(8, 4, 8, 8);
                    roleLabel.setBackgroundColor(0x15000000); // Very light gray background

                    if (message.role == LLMClient.MessageRole.USER) {
                        roleLabel.setText("👤 You:");
                    } else if (message.role == LLMClient.MessageRole.ASSISTANT) {
                        roleLabel.setText("🤖 AI:");
                    } else {
                        roleLabel.setText("⚙️ System:");
                    }
                    messageContainer.addView(roleLabel);

                    // Handle system prompts
                    if (message.role == LLMClient.MessageRole.SYSTEM) {
                        createSystemPromptView(messageContainer, messageIndex);
                    } else {
                        // Handle regular messages with potential code
                        createMessageView(messageContainer, message, messageIndex);
                    }

                    chatHistoryLayout.addView(messageContainer);
                    messageIndex++;
                }

                // Update action buttons based on selection
                updateActionButtonsForSelection();

                // Update selection status text
                updateSelectionStatusText();

                // Auto-scroll to bottom after updating chat history only if requested
                if (autoScroll) {
                    chatHistoryContainer.post(() -> {
                        chatHistoryContainer.fullScroll(View.FOCUS_DOWN);
                    });
                }
            }
        }
    }

    /**
     * Handles selection of a chat entry (AI responses and user messages)
     */
    private void selectChatEntry(int messageIndex, LinearLayout messageContainer) {
        // Clear previous selection by restoring its default background
        if (selectedChatEntry != null) {
            // Calculate the default background for the previously selected entry
            boolean wasEvenIndex = (selectedChatEntryIndex % 2 == 0);
            int prevDefaultBg = wasEvenIndex ? 0xFFF8F8F8 : 0xFFFFFFFF;
            selectedChatEntry.setBackgroundColor(prevDefaultBg);
        }

        // Set new selection
        selectedChatEntryIndex = messageIndex;
        selectedChatEntry = messageContainer;
        messageContainer.setBackgroundColor(0x4400FF00); // Light green selection

        // Save selection to session
        if (currentSession != null) {
            currentSession.setSelectedMessageIndex(messageIndex);
            sessionManager.updateSession(currentSession);
            Log.d(TAG, "Saved selected message index " + messageIndex + " to session");
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        LLMClient.Message selectedMessage = messages.get(messageIndex);

        Log.d(TAG, "Selected " + selectedMessage.role + " message at index: " + messageIndex);

        // Handle different message types for input field population
        if (currentSession != null && feedbackInput != null && messageIndex >= 0 && messageIndex < messages.size()) {
            if (selectedMessage.role == LLMClient.MessageRole.ASSISTANT) {
                // AI response selected - load iteration-specific error if it exists
                // Check if this AI response contains code
                LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) selectedMessage;

                String extractedCode = assistantMsg.getExtractedCode();
                if (extractedCode != null && !extractedCode.isEmpty()) {
                    // Only load iteration-specific errors for AI responses that contain code
                    // Calculate the iteration number for this AI response
                    int selectedIteration = getIterationNumberForMessage(messageIndex);
                    String iterationError = currentSession.getIterationError(selectedIteration);

                    if (iterationError != null && !iterationError.trim().isEmpty()) {
                        // Load the error into the text input
                        feedbackInput.setText(iterationError);
                        feedbackInput.setSelection(iterationError.length()); // Move cursor to end
                        Log.d(TAG, "Loaded error for iteration " + selectedIteration + ": " + iterationError);
                    } else {
                        // Clear the text input if no error for this iteration
                        feedbackInput.setText("");
                        Log.d(TAG, "No error for iteration " + selectedIteration + ", cleared input field");
                    }
                } else {
                    // This AI response doesn't contain code, so clear the input field
                    feedbackInput.setText("");
                    Log.d(TAG, "Selected AI response contains no code, cleared input field");
                }
            } else if (selectedMessage.role == LLMClient.MessageRole.USER) {
                // User message selected - pre-fill with original feedback and restore images
                LLMClient.UserMessage userMsg = (LLMClient.UserMessage) selectedMessage;

                // Pre-fill feedback text
                String originalFeedback = userMsg.getFeedback();
                if (originalFeedback != null && !originalFeedback.trim().isEmpty()) {
                    feedbackInput.setText(originalFeedback);
                    feedbackInput.setSelection(originalFeedback.length()); // Move cursor to end
                    Log.d(TAG, "Pre-filled input with original feedback: " + originalFeedback);
                } else {
                    // Fall back to message content if no separate feedback
                    feedbackInput.setText(selectedMessage.content);
                    feedbackInput.setSelection(selectedMessage.content.length());
                    Log.d(TAG, "Pre-filled input with message content: " + selectedMessage.content);
                }

                // Restore image attachments
                selectedScreenshots.clear();
                if (userMsg.hasImages()) {
                    List<File> validImages = userMsg.getValidImageFiles();
                    selectedScreenshots.addAll(validImages);

                    if (!selectedScreenshots.isEmpty()) {
                        paperclipButton.setText("📎✓ (" + selectedScreenshots.size() + ")");
                        Log.d(TAG, "Restored " + selectedScreenshots.size() + " image attachments from user message");
                    }
                } else {
                    paperclipButton.setText("📎");
                    Log.d(TAG, "No images to restore from user message");
                }
            }
        }

        // Update button states based on selected response
        updateActionButtonsForSelection();

        // Update selection status text
        updateSelectionStatusText();
    }

    /**
     * Updates action buttons based on the currently selected message (AI response
     * or user message)
     */
    private void updateActionButtonsForSelection() {
        if (selectedChatEntryIndex >= 0 && currentSession != null) {
            List<LLMClient.Message> messages = currentSession.getChatHistory();
            if (selectedChatEntryIndex < messages.size()) {
                LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);

                if (selectedMessage.role == LLMClient.MessageRole.ASSISTANT) {
                    // AI response selected - check if it has code
                    LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) selectedMessage;
                    String extractedCode = assistantMsg.getExtractedCode();

                    // Enable/disable buttons based on whether we have code (either in selected
                    // response or in history)
                    boolean hasCodeInResponse = extractedCode != null && !extractedCode.isEmpty();
                    boolean hasCodeInHistory = false;

                    if (!hasCodeInResponse) {
                        // Check if we can find code in the message history
                        CodeSearchResult searchResult = findLastAiResponseWithCode(selectedChatEntryIndex);
                        hasCodeInHistory = searchResult.found;
                    }

                    boolean hasCode = hasCodeInResponse || hasCodeInHistory;

                    runButton.setEnabled(hasCode);
                    thumbsUpButton.setEnabled(hasCode);

                    Log.d(TAG, "Updated action buttons for selected AI response. Has code in response: "
                            + hasCodeInResponse + ", has code in history: " + hasCodeInHistory);
                } else if (selectedMessage.role == LLMClient.MessageRole.USER) {
                    // User message selected - search backwards for any AI response with code
                    CodeSearchResult searchResult = findLastAiResponseWithCode(selectedChatEntryIndex);
                    boolean hasCodeInHistory = searchResult.found;

                    runButton.setEnabled(hasCodeInHistory);
                    thumbsUpButton.setEnabled(hasCodeInHistory);

                    Log.d(TAG,
                            "Updated action buttons for selected user message. Has code in history: "
                                    + hasCodeInHistory);
                }
            }
        } else {
            // No selection, disable buttons
            runButton.setEnabled(false);
            thumbsUpButton.setEnabled(false);
        }
    }

    /**
     * Updates the selection status text to show which message is selected
     */
    private void updateSelectionStatusText() {
        if (currentSession == null || selectionStatusText == null) {
            return;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (messages == null) {
            return;
        }

        // Count total AI responses and user messages
        int totalAiResponses = 0;
        int totalUserMessages = 0;
        int selectedAiResponseNumber = 0;
        int selectedUserMessageNumber = 0;

        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                totalAiResponses++;
                if (i == selectedChatEntryIndex) {
                    selectedAiResponseNumber = totalAiResponses;
                }
            } else if (messages.get(i).role == LLMClient.MessageRole.USER) {
                totalUserMessages++;
                if (i == selectedChatEntryIndex) {
                    selectedUserMessageNumber = totalUserMessages;
                }
            }
        }

        if (totalAiResponses == 0 && totalUserMessages == 0) {
            selectionStatusText.setText("No messages available");
            selectionStatusText.setVisibility(View.GONE);
        } else if (selectedChatEntryIndex >= 0) {
            LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);
            if (selectedMessage.role == LLMClient.MessageRole.ASSISTANT && selectedAiResponseNumber > 0) {
                selectionStatusText
                        .setText("Selected AI response " + selectedAiResponseNumber + " of " + totalAiResponses);
            } else if (selectedMessage.role == LLMClient.MessageRole.USER && selectedUserMessageNumber > 0) {
                selectionStatusText
                        .setText("Selected user message " + selectedUserMessageNumber + " of " + totalUserMessages);
            } else {
                selectionStatusText.setText("Selected message (unknown type)");
            }
            selectionStatusText.setVisibility(View.VISIBLE);
        } else {
            String statusText = "No message selected";
            if (totalAiResponses > 0 && totalUserMessages > 0) {
                statusText += " (" + totalAiResponses + " AI responses, " + totalUserMessages
                        + " user messages available)";
            } else if (totalAiResponses > 0) {
                statusText += " (" + totalAiResponses + " AI responses available)";
            } else if (totalUserMessages > 0) {
                statusText += " (" + totalUserMessages + " user messages available)";
            }
            selectionStatusText.setText(statusText);
            selectionStatusText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Creates a view for system prompt messages
     */
    private void createSystemPromptView(LinearLayout container, int messageIndex) {
        // Get the system message content
        List<LLMClient.Message> messages = currentSession.getChatHistory();
        String systemContent = "";
        for (LLMClient.Message message : messages) {
            if (message.role == LLMClient.MessageRole.SYSTEM) {
                systemContent = message.content;
                break;
            }
        }
        // Create clickable button for system prompt
        Button systemPromptButton = new Button(this);
        systemPromptButton.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        systemPromptButton.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        systemPromptButton.setBackgroundResource(android.R.color.transparent);
        systemPromptButton.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        systemPromptButton.setPadding(0, 8, 0, 8);

        if (systemPromptExpanded) {
            systemPromptButton.setText("📋 [Click to hide system prompt]");
        } else {
            systemPromptButton.setText("📋 [Click to show system prompt]");
        }

        systemPromptButton.setOnClickListener(v -> {
            systemPromptExpanded = !systemPromptExpanded;
            updateChatHistoryDisplay(false, false); // Preserve selection, no auto-scroll
        });
        container.addView(systemPromptButton);

        // Add system prompt content if expanded
        if (systemPromptExpanded) {
            TextView contentView = new TextView(this);
            contentView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            contentView.setTextSize(14);
            contentView.setPadding(16, 8, 16, 8);
            contentView.setBackgroundColor(getResources().getColor(android.R.color.white));
            contentView.setText(systemContent);
            container.addView(contentView);
        }
    }

    /**
     * Creates a view for regular messages (user/assistant)
     */
    private void createMessageView(LinearLayout container, LLMClient.Message message, int messageIndex) {
        // Check if this is a user message with logcat output
        if (message.role == LLMClient.MessageRole.USER) {
            LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
            String logcat = userMsg.getLogcat();

            if (logcat != null && !logcat.isEmpty()) {
                // Remove logcat content from the main message content
                String cleanContent = message.content;
                if (cleanContent.contains(logcat)) {
                    int logcatIndex = cleanContent.indexOf(logcat);
                    cleanContent = cleanContent.substring(0, logcatIndex).trim();
                }

                // Show the cleaned message content (feedback without logcat)
                if (!cleanContent.isEmpty()) {
                    TextView feedbackView = new TextView(this);
                    feedbackView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    feedbackView.setTextSize(14);
                    feedbackView.setPadding(0, 4, 0, 4);
                    feedbackView.setText(cleanContent);
                    container.addView(feedbackView);
                }

                // Create clickable button for logcat
                Button logcatButton = new Button(this);
                logcatButton.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                logcatButton.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
                logcatButton.setBackgroundResource(android.R.color.transparent);
                logcatButton.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
                logcatButton.setPadding(0, 8, 0, 8);

                if (expandedLogcatSections.contains(messageIndex)) {
                    logcatButton.setText("📋 [Click to hide logcat output]");
                } else {
                    logcatButton.setText("📋 [Click to show logcat output]");
                }

                logcatButton.setOnClickListener(v -> {
                    if (expandedLogcatSections.contains(messageIndex)) {
                        expandedLogcatSections.remove(messageIndex);
                    } else {
                        expandedLogcatSections.add(messageIndex);
                    }
                    updateChatHistoryDisplay(false, false); // Preserve selection, no auto-scroll
                });
                container.addView(logcatButton);

                // Add logcat content if expanded
                if (expandedLogcatSections.contains(messageIndex)) {
                    // Create horizontal scrollable container for logcat
                    HorizontalScrollView logcatScrollView = new HorizontalScrollView(this);
                    logcatScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    logcatScrollView.setBackgroundColor(getResources().getColor(android.R.color.white));
                    logcatScrollView.setPadding(16, 8, 16, 8);

                    TextView logcatView = new TextView(this);
                    logcatView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    logcatView.setTextSize(10);
                    // Try multiple monospace fonts to ensure we get a truly fixed-width font
                    Typeface monoTypeface = null;
                    try {
                        // First try Droid Sans Mono (common on Android)
                        monoTypeface = Typeface.create("Droid Sans Mono", Typeface.NORMAL);
                    } catch (Exception e) {
                        try {
                            // Fallback to Courier
                            monoTypeface = Typeface.create("Courier", Typeface.NORMAL);
                        } catch (Exception e2) {
                            // Final fallback to system monospace
                            monoTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
                        }
                    }
                    logcatView.setTypeface(monoTypeface);
                    logcatView.setPadding(0, 0, 0, 0);
                    logcatView.setText("Logcat output:\n" + logcat);

                    logcatScrollView.addView(logcatView);
                    container.addView(logcatScrollView);
                }
                return; // Exit early since we handled the logcat case
            }
        }

        // No logcat found, check for code
        String extractedCode = null;
        if (message.role == LLMClient.MessageRole.ASSISTANT) {
            LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) message;
            extractedCode = assistantMsg.getExtractedCode();
        }

        if (extractedCode != null && !extractedCode.isEmpty()) {
            // Show text before code - get the complete extraction result from
            // AssistantMessage
            LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) message;
            LLMClient.CodeExtractionResult result = assistantMsg.getCodeExtractionResult();
            if (result != null && result.textBeforeCode != null && !result.textBeforeCode.isEmpty()) {
                TextView beforeTextView = new TextView(this);
                beforeTextView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                beforeTextView.setTextSize(14);
                beforeTextView.setPadding(0, 4, 0, 4);
                beforeTextView.setText(result.textBeforeCode);
                container.addView(beforeTextView);
            }

            // Create container for code controls
            LinearLayout codeControlsContainer = new LinearLayout(this);
            codeControlsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            codeControlsContainer.setOrientation(LinearLayout.HORIZONTAL);

            // Create clickable button for code
            Button codeButton = new Button(this);
            codeButton.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f));
            codeButton.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            codeButton.setBackgroundResource(android.R.color.transparent);
            codeButton.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            codeButton.setPadding(0, 8, 0, 8);

            if (expandedCodeSections.contains(messageIndex)) {
                codeButton.setText("📄 [Click to hide Clojure code]");
            } else {
                codeButton.setText("📄 [Click to show Clojure code]");
            }

            codeButton.setOnClickListener(v -> {
                if (expandedCodeSections.contains(messageIndex)) {
                    expandedCodeSections.remove(messageIndex);
                } else {
                    expandedCodeSections.add(messageIndex);
                }
                updateChatHistoryDisplay(false, false); // Preserve selection, no auto-scroll
            });

            // Add line numbers toggle button (only show when code is expanded)
            if (expandedCodeSections.contains(messageIndex)) {
                Button lineNumbersButton = new Button(this);
                lineNumbersButton.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                lineNumbersButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                lineNumbersButton.setBackgroundResource(android.R.color.transparent);
                lineNumbersButton.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
                lineNumbersButton.setTextSize(12);
                lineNumbersButton.setPadding(8, 8, 8, 8);
                lineNumbersButton.setText(showingLineNumbers ? "🔢-" : "🔢+");

                lineNumbersButton.setOnClickListener(v -> {
                    showingLineNumbers = !showingLineNumbers;
                    updateChatHistoryDisplay(false, false); // Preserve selection, no auto-scroll
                });
                codeControlsContainer.addView(lineNumbersButton);
            }

            codeControlsContainer.addView(codeButton);
            container.addView(codeControlsContainer);

            // Add code content if expanded
            if (expandedCodeSections.contains(messageIndex)) {
                // Create horizontal scrollable container for code
                HorizontalScrollView codeScrollView = new HorizontalScrollView(this);
                codeScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                codeScrollView.setBackgroundColor(getResources().getColor(android.R.color.white));
                codeScrollView.setPadding(16, 8, 16, 8);

                TextView codeView = new TextView(this);
                codeView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                codeView.setTextSize(12);
                // Try multiple monospace fonts to ensure we get a truly fixed-width font
                Typeface monoTypeface = null;
                try {
                    // First try Droid Sans Mono (common on Android)
                    monoTypeface = Typeface.create("Droid Sans Mono", Typeface.NORMAL);
                } catch (Exception e) {
                    try {
                        // Fallback to Courier
                        monoTypeface = Typeface.create("Courier", Typeface.NORMAL);
                    } catch (Exception e2) {
                        // Final fallback to system monospace
                        monoTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
                    }
                }
                codeView.setTypeface(monoTypeface);
                codeView.setPadding(0, 0, 0, 0);

                // Show code with or without line numbers based on toggle (without backticks)
                String displayCode;
                if (showingLineNumbers) {
                    // Add line numbers to the code
                    String[] lines = extractedCode.split("\n");
                    StringBuilder numberedCode = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) {
                        numberedCode.append(String.format("%3d: %s\n", i + 1, lines[i]));
                    }
                    displayCode = numberedCode.toString();
                } else {
                    displayCode = extractedCode;
                }

                codeView.setText(displayCode);
                codeScrollView.addView(codeView);
                container.addView(codeScrollView);
            }

            // Show text after code
            if (result != null && result.textAfterCode != null && !result.textAfterCode.isEmpty()) {
                TextView afterTextView = new TextView(this);
                afterTextView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                afterTextView.setTextSize(14);
                afterTextView.setPadding(0, 4, 0, 4);
                afterTextView.setText(result.textAfterCode);
                container.addView(afterTextView);
            }
        } else {
            // No code found, show full message
            TextView contentView = new TextView(this);
            contentView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            contentView.setTextSize(14);
            contentView.setPadding(0, 4, 0, 4);
            contentView.setText(message.content);
            container.addView(contentView);
        }
    }

    /**
     * Updates the chat history display (code now shown inline in chat)
     */
    private void displayCurrentCode() {
        if (currentSession == null || currentSession.getCurrentCode() == null) {
            Log.d(TAG, "No code available to display");
            return;
        }

        // Code is now displayed inline in chat history, just update the chat display
        updateChatHistoryDisplay(); // Use default behavior (auto-select last, auto-scroll)
    }

    /**
     * Toggles between showing code with or without line numbers
     */
    private void toggleLineNumbersDisplay() {
        showingLineNumbers = !showingLineNumbers;
        updateChatHistoryDisplay(false, false); // Preserve selection, no auto-scroll
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
     * Handles code extraction errors by showing a popup dialog and re-enabling
     * buttons
     * 
     * @param errorMessage The error message to display
     */
    private void handleCodeExtractionError(String errorMessage) {
        runOnUiThread(() -> {
            // Re-enable buttons since the operation was cancelled
            Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
            submitFeedbackButton.setEnabled(true);
            thumbsUpButton.setEnabled(true);
            runButton.setEnabled(true);

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
                        selectedScreenshots.clear();
                        selectedScreenshots.add(screenshot);
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

        // Create a HorizontalScrollView to make it horizontally scrollable
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.addView(container);

        builder.setView(scrollView);
        builder.setPositiveButton("OK", (d, which) -> d.dismiss());
        builder.setNegativeButton("Cancel", (d, which) -> {
            selectedScreenshots.clear();
            d.dismiss();
            screenshotCheckbox.setChecked(false);
        });

        dialogRef[0] = builder.show();
    }

    /**
     * Shows screenshot selection dialog for chat input with multi-selection support
     */
    private void showScreenshotSelectionForChat() {
        // Check if current model supports images
        boolean isMultimodal = false;
        if (iterationManager != null) {
            LLMClient.ModelProperties props = iterationManager.getModelProperties();
            isMultimodal = props != null && props.isMultimodal;
        }

        if (!isMultimodal) {
            Toast.makeText(this, "Current model does not support images. Please select a multimodal model.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Get screenshots from the selected iteration, not all current screenshots
        List<String> availableScreenshots;
        String iterationInfo;

        if (selectedChatEntryIndex >= 0) {
            // Show screenshots from the selected iteration
            int selectedIteration = getIterationNumberForMessage(selectedChatEntryIndex);
            availableScreenshots = getScreenshotsForIteration(selectedIteration);
            iterationInfo = " (Iteration " + selectedIteration + ")";
        } else {
            // No message selected, show latest screenshots
            availableScreenshots = new ArrayList<>();
            for (File screenshot : currentScreenshots) {
                availableScreenshots.add(screenshot.getAbsolutePath());
            }
            iterationInfo = " (Latest)";
        }

        if (availableScreenshots.isEmpty()) {
            String message = selectedChatEntryIndex >= 0
                    ? "No screenshots available for selected iteration"
                    : "No screenshots available";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Screenshots" + iterationInfo + " (Multiple selection enabled)");

        // Create main container with vertical layout for screenshots and buttons
        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        // Create a horizontal scrollable layout for screenshots
        LinearLayout screenshotContainer = new LinearLayout(this);
        screenshotContainer.setOrientation(LinearLayout.HORIZONTAL);
        screenshotContainer.setPadding(0, 0, 0, dpToPx(16)); // Bottom padding before buttons

        // Create the dialog first so we can reference it in the click listeners
        final AlertDialog[] dialogRef = new AlertDialog[1];

        // Keep track of currently selected screenshots for this dialog
        final List<File> tempSelectedScreenshots = new ArrayList<>(selectedScreenshots);

        for (int i = 0; i < availableScreenshots.size(); i++) {
            String screenshotPath = availableScreenshots.get(i);
            File screenshot = new File(screenshotPath);
            final int index = i;

            ImageView imageView = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dpToPx(100), dpToPx(150));
            params.setMargins(dpToPx(8), 0, dpToPx(8), 0);
            imageView.setLayoutParams(params);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            // Check if this screenshot is currently selected
            boolean isCurrentlySelected = tempSelectedScreenshots.stream()
                    .anyMatch(f -> f.getAbsolutePath().equals(screenshot.getAbsolutePath()));

            // Function to update image highlighting
            Runnable updateHighlight = () -> {
                boolean isSelected = tempSelectedScreenshots.stream()
                        .anyMatch(f -> f.getAbsolutePath().equals(screenshot.getAbsolutePath()));
                if (isSelected) {
                    // Highlight selected screenshots with a green border
                    imageView.setBackgroundColor(0xFF4CAF50); // Green background
                    imageView.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)); // Padding to show border
                } else {
                    imageView.setBackgroundResource(android.R.drawable.btn_default);
                    imageView.setPadding(0, 0, 0, 0);
                }
            };

            // Initial highlight
            updateHighlight.run();

            try {
                Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading screenshot: " + screenshot.getAbsolutePath(), e);
                imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            }

            imageView.setOnClickListener(v -> {
                boolean isSelected = tempSelectedScreenshots.stream()
                        .anyMatch(f -> f.getAbsolutePath().equals(screenshot.getAbsolutePath()));

                if (isSelected) {
                    // Deselect this screenshot
                    tempSelectedScreenshots.removeIf(f -> f.getAbsolutePath().equals(screenshot.getAbsolutePath()));
                    Log.d(TAG, "Screenshot deselected: " + screenshot.getAbsolutePath());
                } else {
                    // Select this screenshot
                    tempSelectedScreenshots.add(screenshot);
                    Log.d(TAG, "Screenshot selected: " + screenshot.getAbsolutePath());
                }

                // Update highlight
                updateHighlight.run();

                // Update toast message
                if (tempSelectedScreenshots.isEmpty()) {
                    Toast.makeText(this, "No screenshots selected", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, tempSelectedScreenshots.size() + " screenshot(s) selected", Toast.LENGTH_SHORT)
                            .show();
                }
            });

            screenshotContainer.addView(imageView);
        }

        // Create horizontal scroll view for screenshots
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.addView(screenshotContainer);

        // Add screenshots scroll view to main container
        mainContainer.addView(scrollView);

        // Create button container for horizontal layout
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Clear selection button
        Button clearButton = new Button(this);
        clearButton.setText("Clear Selection");
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        clearParams.setMargins(0, 0, dpToPx(8), 0);
        clearButton.setLayoutParams(clearParams);
        clearButton.setBackgroundResource(android.R.drawable.btn_default);
        clearButton.setTextColor(getResources().getColor(android.R.color.black));
        clearButton.setOnClickListener(v -> {
            tempSelectedScreenshots.clear();
            selectedScreenshots.clear();
            paperclipButton.setText("📎"); // Reset paperclip button
            Log.d(TAG, "Screenshot selections cleared");
            Toast.makeText(this, "Selection cleared", Toast.LENGTH_SHORT).show();
            dialogRef[0].dismiss();
        });

        // Accept button
        Button acceptButton = new Button(this);
        acceptButton.setText("Accept");
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        acceptParams.setMargins(dpToPx(8), 0, 0, 0);
        acceptButton.setLayoutParams(acceptParams);
        acceptButton.setBackgroundResource(android.R.drawable.btn_default);
        acceptButton.setTextColor(getResources().getColor(android.R.color.black));
        acceptButton.setOnClickListener(v -> {
            // Apply the selection
            selectedScreenshots.clear();
            selectedScreenshots.addAll(tempSelectedScreenshots);

            // Update paperclip button
            if (selectedScreenshots.isEmpty()) {
                paperclipButton.setText("📎");
            } else {
                paperclipButton.setText("📎✓ (" + selectedScreenshots.size() + ")");
            }

            Log.d(TAG, "Accepted " + selectedScreenshots.size() + " screenshot selections");
            String message = selectedScreenshots.isEmpty() ? "No screenshots selected"
                    : selectedScreenshots.size() + " screenshot(s) selected for next message";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            dialogRef[0].dismiss();
        });

        buttonContainer.addView(clearButton);
        buttonContainer.addView(acceptButton);
        mainContainer.addView(buttonContainer);

        builder.setView(mainContainer);
        builder.setNegativeButton("Cancel", (d, which) -> d.dismiss());

        dialogRef[0] = builder.create();
        dialogRef[0].show();
    }

    /**
     * Creates a new ClojureIterationManager and queues the system prompt if needed
     * 
     * @param session The design session to create the manager for
     * @return The new ClojureIterationManager
     */
    private ClojureIterationManager createIterationManagerWithSystemPrompt(DesignSession session) {
        ClojureIterationManager manager = new ClojureIterationManager(this, session);

        // Queue system prompt if the chat session is empty (new session)
        LLMClient llmClient = manager.getLLMClient();
        if (llmClient != null) {
            session.queueSystemPromptIfEmpty(llmClient.getSystemPrompt());
            Log.d(TAG, "Queued system prompt for session: " + session.getId().toString());
        }

        return manager;
    }

    /**
     * Updates the paperclip button state based on current model's multimodal
     * capability
     */
    private void updatePaperclipButtonState() {
        boolean isMultimodal = false;
        if (iterationManager != null) {
            LLMClient.ModelProperties props = iterationManager.getModelProperties();
            isMultimodal = props != null && props.isMultimodal;
        }

        if (isMultimodal) {
            paperclipButton.setEnabled(true);
            paperclipButton.setTextColor(0xFF666666); // Dark gray when enabled
            paperclipButton.setAlpha(1.0f);
        } else {
            paperclipButton.setEnabled(false);
            paperclipButton.setTextColor(0xFFCCCCCC); // Light gray when disabled
            paperclipButton.setAlpha(0.5f);
            // Clear any selected screenshot since we can't use it
            selectedScreenshots.clear();
            paperclipButton.setText("📎");
        }
    }

    /**
     * Starts automatic iteration when an error is returned from RenderActivity
     */
    private void startAutomaticIteration(String errorFeedback) {

        assert isIterating == false;
        assert iterationManager != null;

        Log.d(TAG, "Starting automatic iteration with error feedback: " + errorFeedback);
        isIterating = true;

        // Create a custom progress dialog with cancel button
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Automatically Fixing Error");

        // Create a custom layout for the dialog
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        // Add progress message
        TextView progressText = new TextView(this);
        progressText.setText("Generating next iteration...");
        progressText.setTextSize(16);
        layout.addView(progressText);

        // Add some spacing
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20));
        layout.addView(spacer);

        // Add cancel button
        Button dialogCancelButton = new Button(this);
        dialogCancelButton.setText("Cancel Iteration");
        dialogCancelButton.setOnClickListener(v -> {
            cancelCurrentIteration();
            // The dialog will be dismissed in cancelCurrentIteration()
        });
        layout.addView(dialogCancelButton);

        builder.setView(layout);
        builder.setCancelable(false);

        iterationProgressDialog = builder.create();
        iterationProgressDialog.show();

        // Hide the main cancel button since it's now in the dialog
        cancelIterationButton.setVisibility(View.GONE);
        thumbsUpButton.setVisibility(View.GONE);
        runButton.setVisibility(View.GONE);

        // Disable other buttons during iteration
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setEnabled(false);

        // Get the current screenshot if any
        File currentScreenshot = null;
        if (!currentScreenshots.isEmpty()) {
            currentScreenshot = currentScreenshots.get(currentScreenshots.size() - 1);
        }

        // Get the current logcat output from session
        String logcatText = currentSession.getLastLogcat() != null ? currentSession.getLastLogcat() : "";

        // Manage message history and call sendMessages directly
        LLMClient.ChatSession chatSession = iterationManager.getLLMClient().getChatSession();

        // Format the iteration prompt
        String prompt = iterationManager.getLLMClient().formatIterationPrompt(currentSession.getDescription(),
                currentSession.getCurrentCode(), logcatText, errorFeedback, false);

        // Queue the user message (no images for automatic iteration)
        LLMClient.UserMessage userMessage = new LLMClient.UserMessage(prompt, new ArrayList<>(), logcatText,
                errorFeedback, null);
        chatSession.queueUserMessage(userMessage);

        // Call sendMessages directly
        iterationManager.sendMessages(chatSession)
                .thenAccept(assistantMessage -> {
                    String code = assistantMessage.getExtractedCode();
                    runOnUiThread(() -> {
                        // Clear the flag, which we set only at the top of this method.
                        isIterating = false;

                        // Dismiss progress dialog
                        if (iterationProgressDialog != null) {
                            iterationProgressDialog.dismiss();
                            iterationProgressDialog = null;
                        }

                        // Check if code extraction succeeded
                        if (code == null) {
                            handleCodeExtractionError("No code found in LLM response");
                            // Re-enable buttons and show normal buttons
                            cancelIterationButton.setVisibility(View.GONE);
                            thumbsUpButton.setVisibility(View.VISIBLE);
                            runButton.setVisibility(View.VISIBLE);
                            return;
                        }

                        assert currentSession != null;

                        currentSession.setCurrentCode(code);

                        // Clear error state since automatic iteration succeeded
                        currentSession.setLastErrorFeedback(null);
                        currentSession.setHasError(false);

                        // Also clear iteration-specific error for the current iteration
                        int currentIteration = currentSession.getIterationCount();
                        if (currentIteration > 0) {
                            currentSession.setIterationError(currentIteration, null);
                            Log.d(TAG, "Cleared iteration error for automatic iteration " + currentIteration);
                        }

                        sessionManager.updateSession(currentSession);

                        displayCurrentCode();

                        // Re-enable buttons and show normal buttons
                        cancelIterationButton.setVisibility(View.GONE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);
                        submitFeedbackButton.setEnabled(true);

                        // Clear the input field since the error was automatically fixed
                        if (feedbackInput != null) {
                            feedbackInput.setText("");
                        }

                        // Update chat history and force selection of the latest AI response
                        updateChatHistoryDisplayWithLatestSelection();

                        // Automatically run the fixed code
                        runSelectedCode(true);
                    });
                })
                .exceptionally(throwable -> {
                    // Clear the flag, which we set only at the top of this method.
                    isIterating = false;

                    // Remove the user message we added before sendMessages (1 message)
                    chatSession.removeLastMessages(1);
                    Log.d(TAG, "Removed 1 message (user message) due to failure in automatic iteration");

                    runOnUiThread(() -> {
                        // Dismiss progress dialog
                        if (iterationProgressDialog != null) {
                            iterationProgressDialog.dismiss();
                            iterationProgressDialog = null;
                        }

                        // Re-enable buttons and show normal buttons
                        cancelIterationButton.setVisibility(View.GONE);
                        thumbsUpButton.setVisibility(View.VISIBLE);
                        runButton.setVisibility(View.VISIBLE);
                        submitFeedbackButton.setEnabled(true);

                        final boolean gotCancelled = throwable instanceof CancellationException ||
                            (throwable instanceof CompletionException &&
                            throwable.getCause() instanceof CancellationException);

                        if (!gotCancelled) {
                            Log.e(TAG, "Error in automatic iteration", throwable);
                            showLLMErrorDialog("Automatic Iteration Error",
                                    "Error during automatic iteration: " + throwable.getMessage());
                        }
                        else {
                            Log.d(TAG, "Automatic iteration was cancelled - this is expected behavior");
                        }
                    });
                    return null;
                });
    }

    /**
     * Cancels the current iteration
     */
    private void cancelCurrentIteration() {
        if (!isIterating) {
            Log.d(TAG, "No iteration in progress to cancel");
            return;
        }

        Log.d(TAG, "Cancelling current iteration");
        isIterating = false;

        // Cancel the underlying LLM request and generation
        if (iterationManager != null) {
            boolean cancelled = iterationManager.cancelCurrentRequest();
            Log.d(TAG, "Cancellation result: " + cancelled);
        }

        // Dismiss progress dialog
        if (iterationProgressDialog != null) {
            iterationProgressDialog.dismiss();
            iterationProgressDialog = null;
        }

        // Re-enable buttons and hide cancel button
        cancelIterationButton.setVisibility(View.GONE);
        thumbsUpButton.setVisibility(View.VISIBLE);
        runButton.setVisibility(View.VISIBLE);
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setEnabled(true);

        // Note: showFeedbackDialog() removed - feedback is now handled through the chat
        // input

        Toast.makeText(this, "Iteration cancelled", Toast.LENGTH_SHORT).show();
    }

    private void cancelInitialGeneration() {
        Log.d(TAG, "Cancelling initial generation");

        // Cancel the underlying LLM request and generation
        if (iterationManager != null) {
            boolean cancelled = iterationManager.cancelCurrentRequest();
            Log.d(TAG, "Initial generation cancellation result: " + cancelled);
        }

        // Dismiss progress dialog
        if (initialGenerationProgressDialog != null) {
            initialGenerationProgressDialog.dismiss();
            initialGenerationProgressDialog = null;
        }

        // Re-enable the submit button
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        submitFeedbackButton.setEnabled(true);

        Toast.makeText(this, "Initial generation cancelled", Toast.LENGTH_SHORT).show();
    }

    private void cancelManualIteration(String originalFeedback, List<File> originalImages) {
        Log.d(TAG, "Cancelling manual iteration");

        // Cancel the underlying LLM request and generation
        if (iterationManager != null) {
            boolean cancelled = iterationManager.cancelCurrentRequest();
            Log.d(TAG, "Manual iteration cancellation result: " + cancelled);
        }

        // Dismiss progress dialog
        if (iterationProgressDialog != null) {
            iterationProgressDialog.dismiss();
            iterationProgressDialog = null;
        }

        // Restore the user's input text since the operation was cancelled
        feedbackInput.setText(originalFeedback);
        feedbackInput.setSelection(originalFeedback.length()); // Move cursor to end

        // Restore the selected screenshots if any were attached
        if (originalImages != null && !originalImages.isEmpty()) {
            selectedScreenshots.clear();
            selectedScreenshots.addAll(originalImages);
            paperclipButton.setText("📎✓ (" + selectedScreenshots.size() + ")"); // Visual feedback that screenshots are
                                                                                 // selected
        }

        // Re-enable buttons
        Button submitFeedbackButton = findViewById(R.id.submit_feedback_button);
        thumbsUpButton.setEnabled(true);
        runButton.setEnabled(true);
        submitFeedbackButton.setEnabled(true);

        Toast.makeText(this, "Manual iteration cancelled", Toast.LENGTH_SHORT).show();
    }

    /**
     * Saves the current input state (text and selected image) to the session
     */
    private void saveCurrentInputState() {
        if (currentSession == null) {
            return;
        }

        // Save current input text
        String inputText = feedbackInput != null ? feedbackInput.getText().toString() : null;
        currentSession.setCurrentInputText(inputText);

        // Save all selected image paths
        if (!selectedScreenshots.isEmpty()) {
            List<String> imagePaths = new ArrayList<>();
            for (File screenshot : selectedScreenshots) {
                imagePaths.add(screenshot.getAbsolutePath());
            }
            currentSession.setSelectedImagePaths(imagePaths);
        } else {
            currentSession.setSelectedImagePaths(null);
        }

        // Update the session in storage
        sessionManager.updateSession(currentSession);

        Log.d(TAG,
                "Saved input state: text=" + (inputText != null ? "present (len=" + inputText.length() + ")" : "null") +
                        ", images=" + selectedScreenshots.size() + " selected");
    }

    /**
     * Restores the current input state (text and selected image) from the session
     */
    private void restoreCurrentInputState() {
        if (currentSession == null) {
            return;
        }

        // Restore input text
        String inputText = currentSession.getCurrentInputText();
        if (inputText != null && feedbackInput != null) {
            feedbackInput.setText(inputText);
            feedbackInput.setSelection(inputText.length()); // Move cursor to end
            Log.d(TAG, "Restored input text: " + inputText);
        }

        // Restore selected images (with fallback to single image for backward
        // compatibility)
        List<String> imagePaths = currentSession.getSelectedImagePaths();
        if (imagePaths != null && !imagePaths.isEmpty()) {
            selectedScreenshots.clear();
            int restoredCount = 0;
            for (String imagePath : imagePaths) {
                File imageFile = new File(imagePath);
                if (imageFile.exists()) {
                    selectedScreenshots.add(imageFile);
                    restoredCount++;
                } else {
                    Log.w(TAG, "Saved image no longer exists: " + imagePath);
                }
            }

            if (restoredCount > 0) {
                paperclipButton.setText("📎✓ (" + selectedScreenshots.size() + ")"); // Visual feedback that screenshots
                                                                                     // are selected
                Log.d(TAG, "Restored " + restoredCount + " selected images");
            } else {
                // None of the saved images exist anymore, clear the saved paths
                currentSession.setSelectedImagePaths(null);
                sessionManager.updateSession(currentSession);
                Log.w(TAG, "None of the saved images exist anymore, cleared selection");
            }
        }
    }

    /**
     * Checks if the currently selected message requires forking to a new session.
     * Returns true if a non-latest AI response is selected OR a user message is
     * selected.
     */
    private boolean doesSelectedMessageRequireFork() {
        if (selectedChatEntryIndex < 0 || currentSession == null) {
            Log.d(TAG, "doesSelectedMessageRequireFork: false (no selection or session)");
            return false;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (messages == null || selectedChatEntryIndex >= messages.size()) {
            Log.d(TAG, "doesSelectedMessageRequireFork: false (invalid messages or index)");
            return false;
        }

        // Find the latest AI response index
        int latestAiResponseIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == LLMClient.MessageRole.ASSISTANT) {
                latestAiResponseIndex = i;
                break;
            }
        }

        // If no AI responses exist, no fork needed
        if (latestAiResponseIndex == -1) {
            Log.d(TAG, "doesSelectedMessageRequireFork: false (no AI responses)");
            return false;
        }

        // Check if selected message is the latest AI response or a user message
        LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);
        boolean isSelectedMessageAI = (selectedMessage.role == LLMClient.MessageRole.ASSISTANT);
        boolean isSelectedMessageUser = (selectedMessage.role == LLMClient.MessageRole.USER);
        boolean isLatestAIResponse = (selectedChatEntryIndex == latestAiResponseIndex);

        Log.d(TAG, "doesSelectedMessageRequireFork: selectedIndex=" + selectedChatEntryIndex +
                ", latestAiIndex=" + latestAiResponseIndex +
                ", isSelectedAI=" + isSelectedMessageAI +
                ", isSelectedUser=" + isSelectedMessageUser +
                ", isLatest=" + isLatestAIResponse);

        // Fork is needed if:
        // 1. An AI response is selected, but it's not the latest one, OR
        // 2. A user message is selected (fork to previous AI response)
        boolean requiresFork = (isSelectedMessageAI && !isLatestAIResponse) || isSelectedMessageUser;
        Log.d(TAG, "doesSelectedMessageRequireFork: result=" + requiresFork);
        return requiresFork;
    }

    /**
     * Shows a confirmation dialog asking the user if they want to create a new
     * session
     * forked from the selected message.
     */
    private void showForkConfirmationDialog(String feedbackText) {
        if (currentSession == null) {
            return;
        }

        String currentSessionName = currentSession.getSessionName();
        String forkedSessionName = "(fork) " +
                ((currentSessionName == null || currentSessionName.trim().isEmpty()) ? UNNAMED_SESSION
                        : currentSessionName);

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);

        String dialogMessage;
        if (selectedMessage.role == LLMClient.MessageRole.ASSISTANT) {
            dialogMessage = "You're responding to a previous AI response. Do you want to create a new session " +
                    "starting from that point?";
        } else if (selectedMessage.role == LLMClient.MessageRole.USER) {
            dialogMessage = "You're editing a previous user message. Do you want to create a new session " +
                    "starting from the last AI response before that message?";
        } else {
            dialogMessage = "You're responding to a previous message. Do you want to create a new session " +
                    "starting from that point?";
        }

        dialogMessage += "\n\nNew session will be called:\n\"" + forkedSessionName + "\"";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Session?");
        builder.setMessage(dialogMessage);

        builder.setPositiveButton("OK", (dialog, which) -> {
            // Create the fork and continue with the feedback
            forkSession(feedbackText, forkedSessionName);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            // Do nothing, keep the current text in the input field
            dialog.dismiss();
        });

        builder.setCancelable(true);
        builder.show();
    }

    /**
     * Creates a new session forked from the current session up to the selected
     * message or the previous AI response (for user messages)
     * and switches to use the new session.
     */
    private void forkSession(String feedbackText, String forkedSessionName) {
        if (currentSession == null || selectedChatEntryIndex < 0) {
            Log.e(TAG, "Cannot create fork: invalid session or selection");
            return;
        }

        List<LLMClient.Message> messages = currentSession.getChatHistory();
        if (selectedChatEntryIndex >= messages.size()) {
            Log.e(TAG, "Cannot create fork: invalid selection index");
            return;
        }

        LLMClient.Message selectedMessage = messages.get(selectedChatEntryIndex);
        int forkPointIndex = selectedChatEntryIndex;

        // If user message is selected, fork to the last AI response before it
        if (selectedMessage.role == LLMClient.MessageRole.USER) {
            forkPointIndex = findLastAiResponseBefore(selectedChatEntryIndex);
            if (forkPointIndex < 0) {
                Log.e(TAG, "Cannot create fork: no AI response found before selected user message");
                Toast.makeText(this, "No AI response found before selected user message", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d(TAG, "User message selected, forking to previous AI response at index " + forkPointIndex);
        }

        Log.d(TAG, "Creating fork session from message index " + forkPointIndex + " (originally selected: "
                + selectedChatEntryIndex + ")");

        // Create new session
        DesignSession forkedSession = new DesignSession();

        // Copy basic properties
        String originalDescription = currentSession.getDescription();
        String originalSessionName = currentSession.getSessionName();

        forkedSession.setSessionName(forkedSessionName);
        forkedSession.setDescription(originalDescription);
        forkedSession.setInitialCode(currentSession.getInitialCode());
        forkedSession.setLlmType(currentSession.getLlmType());
        forkedSession.setLlmModel(currentSession.getLlmModel());

        // Copy messages up to and including the fork point
        LLMClient.ChatSession forkChatSession = forkedSession.getChatSession();
        for (int i = 0; i <= forkPointIndex; i++) {
            LLMClient.Message message = messages.get(i);

            if (message.role == LLMClient.MessageRole.SYSTEM) {
                LLMClient.SystemPrompt systemPrompt = (LLMClient.SystemPrompt) message;
                forkChatSession.queueSystemPrompt(systemPrompt);
            } else if (message.role == LLMClient.MessageRole.USER) {
                LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
                forkChatSession.queueUserMessage(userMsg);
            } else if (message.role == LLMClient.MessageRole.ASSISTANT) {
                LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) message;
                forkChatSession.queueAssistantResponse(assistantMsg);
            }
        }

        // Set the current code to the code from the fork point (AI response)
        LLMClient.Message forkPointMessage = messages.get(forkPointIndex);
        if (forkPointMessage.role == LLMClient.MessageRole.ASSISTANT) {
            LLMClient.AssistantMessage assistantMsg = (LLMClient.AssistantMessage) forkPointMessage;
            String extractedCode = assistantMsg.getExtractedCode();
            if (extractedCode != null && !extractedCode.isEmpty()) {
                forkedSession.setCurrentCode(extractedCode);
            }
        }

        // Copy relevant screenshot sets up to the fork point iteration
        int forkIteration = getIterationNumberForMessage(forkPointIndex);
        List<List<String>> originalScreenshotSets = currentSession.getScreenshotSets();
        List<Integer> originalIterations = currentSession.getScreenshotSetIterations();

        if (originalScreenshotSets != null && originalIterations != null) {
            for (int i = 0; i < originalScreenshotSets.size() && i < originalIterations.size(); i++) {
                int iterationNumber = originalIterations.get(i);
                if (iterationNumber <= forkIteration) {
                    forkedSession.addScreenshotSet(originalScreenshotSets.get(i), iterationNumber);
                }
            }
        }

        // Copy relevant iteration errors up to the fork point iteration
        Map<Integer, String> originalErrors = currentSession.getAllIterationErrors();
        if (originalErrors != null && !originalErrors.isEmpty()) {
            for (Map.Entry<Integer, String> entry : originalErrors.entrySet()) {
                int iterationNumber = entry.getKey();
                if (iterationNumber <= forkIteration) {
                    forkedSession.setIterationError(iterationNumber, entry.getValue());
                    Log.d(TAG, "Copied error for iteration " + iterationNumber + " to fork session");
                }
            }
        }

        // Set the selected message to the fork point
        forkedSession.setSelectedMessageIndex(forkPointIndex);
        Log.d(TAG, "Set fork session selected message to fork point: " + forkPointIndex);

        // Save the new fork session
        sessionManager.updateSession(forkedSession);

        // Switch to the fork session
        currentSession = forkedSession;

        // Shut down the existing iteration manager since we're switching sessions
        if (iterationManager != null) {
            Log.d(TAG, "Shutting down existing iterationManager for fork session");
            iterationManager.shutdown();
            iterationManager = null;
        }

        // Create new iteration manager for the fork session
        iterationManager = createIterationManagerWithSystemPrompt(currentSession);

        // Update UI to reflect the new session
        updateSessionStateAfterFork();

        Log.d(TAG, "Successfully created and switched to fork session: " + forkedSession.getId().toString());

        // Now continue with the feedback submission
        List<File> imagesToSubmit = new ArrayList<>(selectedScreenshots);

        // Clear the input field and saved state for feedback
        feedbackInput.setText("");
        currentSession.setCurrentInputText(null);
        currentSession.setSelectedImagePaths(null);
        selectedScreenshots.clear();
        paperclipButton.setText("📎"); // Reset paperclip button

        submitFeedbackWithText(feedbackText, imagesToSubmit);
    }

    /**
     * Updates the UI state after switching to a fork session
     */
    private void updateSessionStateAfterFork() {
        // Update session name display
        updateSessionNameDisplay();

        // Update chat history display (auto-select based on saved selection, no
        // auto-scroll)
        updateChatHistoryDisplay(true, false); // Auto-select (will use saved selection), no auto-scroll

        // Update paperclip button state
        updatePaperclipButtonState();

        // Restore screenshot state for the fork session
        List<String> paths = currentSession.getCurrentIterationScreenshots();
        currentScreenshots.clear();
        if (paths != null && !paths.isEmpty()) {
            for (String path : paths) {
                File screenshotFile = new File(path);
                if (screenshotFile.exists()) {
                    currentScreenshots.add(screenshotFile);
                }
            }
            if (!currentScreenshots.isEmpty()) {
                displayScreenshot(currentScreenshots.get(0));
            }
        }

        Toast.makeText(this, "Created new fork session: " + currentSession.getSessionName(), Toast.LENGTH_LONG).show();
    }

    /**
     * Updates the session name display in the activity title
     */
    private void updateSessionNameDisplay() {
        if (currentSession != null) {
            String sessionName = currentSession.getSessionName();
            if (sessionName == null || sessionName.trim().isEmpty()) {
                // Use description as fallback or generate a default name
                sessionName = currentSession.getDescription();
                if (sessionName == null || sessionName.trim().isEmpty()) {
                    sessionName = "Untitled Session";
                }
                // Truncate if too long for display (action bar titles should be shorter)
                if (sessionName.length() > 30) {
                    sessionName = sessionName.substring(0, 27) + "...";
                }
            }
            setTitle(sessionName);
        }
    }

    /**
     * Shows a session naming dialog with customizable title, message, and
     * completion callback
     *
     * @param title       Dialog title
     * @param message     Dialog message (can be null for no message)
     * @param onNameSaved Callback when name is saved (receives the new name)
     * @param onCancelled Callback when dialog is cancelled (can be null)
     */
    private void showSessionNamingDialog(String title, String message,
            java.util.function.Consumer<String> onNameSaved,
            Runnable onCancelled) {
        if (currentSession == null) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show();
            if (onCancelled != null) {
                onCancelled.run();
            }
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        if (message != null && !message.trim().isEmpty()) {
            builder.setMessage(message);
        }

        // Create input field
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        // Set current session name or description as default
        String currentName = currentSession.getSessionName();
        if (currentName == null || currentName.trim().isEmpty() || currentName.equals(UNNAMED_SESSION)) {
            String description = currentSession.getDescription();
            if (description != null && !description.trim().isEmpty()) {
                // Truncate long descriptions to make reasonable session names
                if (description.length() > 50) {
                    currentName = description.substring(0, 47) + "...";
                } else {
                    currentName = description;
                }
            } else {
                currentName = "";
            }
        }

        if (!currentName.isEmpty()) {
            input.setText(currentName);
            input.setSelection(currentName.length()); // Place cursor at end
        }

        input.setHint("Enter session name");

        // Add padding to the input
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                onNameSaved.accept(newName);
            } else {
                Toast.makeText(this, "Session name cannot be empty", Toast.LENGTH_SHORT).show();
                if (onCancelled != null) {
                    onCancelled.run();
                }
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            if (onCancelled != null) {
                onCancelled.run();
            }
        });

        builder.setCancelable(true);
        AlertDialog dialog = builder.create();
        dialog.show();

        // Show keyboard immediately
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /**
     * Shows a dialog to edit the session name (which becomes the activity title)
     */
    private void showEditSessionNameDialog() {
        showSessionNamingDialog(
                "Edit Session Name",
                null, // No message
                newName -> {
                    // Update session name
                    currentSession.setSessionName(newName);
                    sessionManager.updateSession(currentSession);

                    // Update display
                    updateSessionNameDisplay();

                    Toast.makeText(this, "Session name updated", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Session name updated to: " + newName);
                },
                null // No cancellation callback needed
        );
    }

    /**
     * Checks if the current session has an unnamed/default name
     */
    private boolean isSessionUnnamed() {
        if (currentSession == null) {
            return true;
        }

        String sessionName = currentSession.getSessionName();
        if (sessionName == null || sessionName.trim().isEmpty()) {
            return true;
        }

        // Check if it's exactly the unnamed session
        if (sessionName.equals(UNNAMED_SESSION)) {
            return true;
        }

        // Check if it's the unnamed session with zero or more "(fork) " prefixes
        // Remove all "(fork) " prefixes and see if what remains is UNNAMED_SESSION
        String withoutForkPrefixes = sessionName.replaceAll("^(\\(fork\\) )*", "");
        return withoutForkPrefixes.equals(UNNAMED_SESSION);
    }

    /**
     * Shows a dialog to name the session before exiting the activity
     */
    private void showSessionNamingBeforeExitDialog() {
        showSessionNamingDialog(
                "Name This Session",
                "Name this session before returning to the session list?",
                newName -> {
                    // Update session name
                    currentSession.setSessionName(newName);
                    sessionManager.updateSession(currentSession);
                    Log.d(TAG, "Session named before exit: " + newName);
                    finish(); // Exit after naming
                },
                () -> {
                    // Exit without naming
                    finish();
                });
    }
}
