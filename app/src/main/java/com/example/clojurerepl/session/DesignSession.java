package com.example.clojurerepl.session;

import android.content.Context;
import android.util.Log;

import com.example.clojurerepl.LLMClient;
import com.example.clojurerepl.LLMClientFactory;
import com.example.clojurerepl.ClojureIterationManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Represents a Clojure app design session.
 * Stores information about the app design, the LLM used, and iterations.
 */
public class DesignSession {
    private static final String TAG = "DesignSession";

    private UUID id;
    private String sessionName;
    private String description;
    private Date createdAt;
    private String initialCode;
    private LLMClientFactory.LLMType llmType;
    private String llmModel;
    private LLMClient.ChatSession chatSession;
    private String lastLogcat;
    private String lastErrorFeedback;
    private boolean hasError;
    // screenshotSets and screenshotSetIterations are not serialized to/from
    // JSON.  They are reconstructed from a filesystem scan.
    private List<List<String>> screenshotSets;
    private List<Integer> screenshotSetIterations; // Tracks which iteration each screenshot set belongs to
    private String currentInputText;
    private List<String> selectedImagePaths; // New field for multiple image paths
    private Map<Integer, String> iterationErrors; // Maps iteration number to error message
    private int selectedMessageIndex = -1; // Index of currently selected AI response message

    public DesignSession() {
        this.id = UUID.randomUUID();
        this.createdAt = new Date();
        this.screenshotSets = new ArrayList<>();
        this.screenshotSetIterations = new ArrayList<>();
        this.hasError = false;
        this.iterationErrors = new HashMap<>();
        this.chatSession = new LLMClient.ChatSession(this.id.toString());
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description.trim();
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public void setInitialCode(String initialCode) {
        this.initialCode = initialCode != null ? initialCode.trim() : null;
    }

    public String getInitialCode() {
        return initialCode;
    }

    public List<String> getAllCode() {
        List<String> code = new ArrayList<>();
        for (LLMClient.Message message : chatSession.getMessages()) {
            if (LLMClient.MessageRole.ASSISTANT.equals(message.role)) {
                LLMClient.AssistantResponse assistantMsg = (LLMClient.AssistantResponse) message;
                String extractedCode = assistantMsg.getExtractedCode();
                if (extractedCode != null && !extractedCode.isEmpty()) {
                    code.add(extractedCode);
                }
            }
        }

        return code;
    }

    public String getCurrentCode() {
        List<String> code = getAllCode();
        if (code.size() > 0) {
            return code.get(code.size() - 1);
        }
        return null;
    }

    public void setCurrentCode(String currentCode) {
        String code = getCurrentCode();
        // They are either both null or contain identical strings.
        assert code == currentCode || code.equals(currentCode);
    }

    public int getIterationCount() {
        List<String> code = getAllCode();
        if (code == null) {
            return 0;
        }
        return code.size();
    }

    public LLMClientFactory.LLMType getLlmType() {
        return llmType;
    }

    public void setLlmType(LLMClientFactory.LLMType llmType) {
        this.llmType = llmType;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public LLMClient.ChatSession getChatSession() {
        return chatSession;
    }

    /**
     * Queues the system prompt if the chat session is empty (no messages).
     * This should be called once when setting up a new session.
     * 
     * @param systemPrompt The system prompt to queue
     */
    public void queueSystemPromptIfEmpty(String systemPrompt) {
        if (chatSession.getMessages().isEmpty()) {
            chatSession.queueSystemPrompt(new LLMClient.SystemPrompt(systemPrompt));
        }
    }

    public List<LLMClient.Message> getChatHistory() {
        return chatSession.getMessages();
    }

    public String getLastLogcat() {
        return lastLogcat;
    }

    public void setLastLogcat(String lastLogcat) {
        this.lastLogcat = lastLogcat;
    }

    /**
     * Gets all screenshot sets associated with this session.
     *
     * @return The list of screenshot sets.
     */
    public List<List<String>> getScreenshotSets() {
        return screenshotSets;
    }

    /**
     * Gets the iteration numbers corresponding to each screenshot set.
     *
     * @return The list of iteration numbers, parallel to screenshotSets.
     */
    public List<Integer> getScreenshotSetIterations() {
        return screenshotSetIterations;
    }

    /**
     * Adds a set of screenshots to the session.
     *
     * @param screenshotSet A list of paths to screenshot files.
     * @param iteration     The iteration number this screenshot set belongs to.
     */
    public void addScreenshotSet(List<String> screenshotSet, int iteration) {
        if (this.screenshotSets == null) {
            this.screenshotSets = new ArrayList<>();
        }
        if (this.screenshotSetIterations == null) {
            this.screenshotSetIterations = new ArrayList<>();
        }
        this.screenshotSets.add(new ArrayList<>(screenshotSet));
        this.screenshotSetIterations.add(iteration);
    }

    /**
     * Adds a set of screenshots to the session (legacy method for backward
     * compatibility).
     * Uses the current iteration count as the iteration number.
     *
     * @param screenshotSet A list of paths to screenshot files.
     */
    public void addScreenshotSet(List<String> screenshotSet) {
        addScreenshotSet(screenshotSet, getIterationCount());
    }

    /**
     * Gets the latest screenshot set, or empty list if no sets exist.
     *
     * @return The most recently added screenshot set.
     */
    public List<String> getLatestScreenshotSet() {
        if (this.screenshotSets == null || this.screenshotSets.isEmpty()) {
            return new ArrayList<>();
        }
        return this.screenshotSets.get(this.screenshotSets.size() - 1);
    }

    /**
     * Gets all screenshots for a specific iteration number.
     *
     * @param iterationNumber The iteration number to get screenshots for
     * @return A list of all screenshot paths for that iteration.
     */
    public List<String> getScreenshotsForIteration(int iterationNumber) {
        if (this.screenshotSets == null || this.screenshotSets.isEmpty() ||
                this.screenshotSetIterations == null || this.screenshotSetIterations.isEmpty()) {
            return new ArrayList<>();
        }

        // Collect all screenshots for the requested iteration
        List<String> iterationScreenshots = new ArrayList<>();
        for (int i = 0; i < this.screenshotSetIterations.size(); i++) {
            if (this.screenshotSetIterations.get(i) == iterationNumber) {
                iterationScreenshots.addAll(this.screenshotSets.get(i));
            }
        }

        return iterationScreenshots;
    }

    /**
     * Gets all screenshots from the current iteration (the iteration with the
     * highest number).
     *
     * @return A list of all screenshot paths from the current iteration.
     */
    public List<String> getCurrentIterationScreenshots() {
        if (this.screenshotSets == null || this.screenshotSets.isEmpty() ||
                this.screenshotSetIterations == null || this.screenshotSetIterations.isEmpty()) {
            return new ArrayList<>();
        }

        // Find the highest iteration number (current iteration)
        int currentIteration = Collections.max(this.screenshotSetIterations);

        return getScreenshotsForIteration(currentIteration);
    }

    public String getLastErrorFeedback() {
        return lastErrorFeedback;
    }

    public void setLastErrorFeedback(String lastErrorFeedback) {
        this.lastErrorFeedback = lastErrorFeedback;
    }

    public boolean hasError() {
        return hasError;
    }

    public void setHasError(boolean hasError) {
        this.hasError = hasError;
    }

    public String getCurrentInputText() {
        return currentInputText;
    }

    public void setCurrentInputText(String currentInputText) {
        this.currentInputText = currentInputText;
    }

    public List<String> getSelectedImagePaths() {
        return selectedImagePaths;
    }

    public void setSelectedImagePaths(List<String> selectedImagePaths) {
        this.selectedImagePaths = selectedImagePaths;
    }

    /**
     * Sets an error message for a specific iteration.
     *
     * @param iteration    The iteration number (1-based)
     * @param errorMessage The error message, or null to clear the error
     */
    public void setIterationError(int iteration, String errorMessage) {
        if (this.iterationErrors == null) {
            this.iterationErrors = new HashMap<>();
        }

        if (errorMessage != null) {
            errorMessage = errorMessage.trim();
        }

        if (errorMessage == null || errorMessage.isEmpty()) {
            this.iterationErrors.remove(iteration);
        } else {
            this.iterationErrors.put(iteration, errorMessage);
        }
    }

    /**
     * Gets the error message for a specific iteration.
     *
     * @param iteration The iteration number (1-based)
     * @return The error message, or null if no error for this iteration
     */
    public String getIterationError(int iteration) {
        if (this.iterationErrors == null) {
            return null;
        }
        return this.iterationErrors.get(iteration);
    }

    /**
     * Checks if a specific iteration has an error.
     *
     * @param iteration The iteration number (1-based)
     * @return True if this iteration has an error, false otherwise
     */
    public boolean hasIterationError(int iteration) {
        return getIterationError(iteration) != null;
    }

    /**
     * Gets all iteration errors.
     * 
     * @return Map of iteration numbers to error messages
     */
    public Map<Integer, String> getAllIterationErrors() {
        if (this.iterationErrors == null) {
            return new HashMap<>();
        }
        return new HashMap<>(this.iterationErrors);
    }

    /**
     * Gets the index of the currently selected AI response message.
     *
     * @return The message index, or -1 if no message is selected
     */
    public int getSelectedMessageIndex() {
        return selectedMessageIndex;
    }

    /**
     * Sets the index of the currently selected AI response message.
     *
     * @param selectedMessageIndex The message index, or -1 for no selection
     */
    public void setSelectedMessageIndex(int selectedMessageIndex) {
        this.selectedMessageIndex = selectedMessageIndex;
    }

    /**
     * Gets the code with line numbers formatted
     *
     * @return The code with line numbers
     */
    public String getCodeWithLineNumbers() {
        String currentCode = getCurrentCode();
        if (currentCode == null)
            return null;
        return addLineNumbersToCode(currentCode);
    }

    /**
     * Formats Clojure code with line numbers.
     * Line numbers are right-aligned in a column and followed by a colon.
     *
     * @param code The Clojure code to format with line numbers
     * @return The code with line numbers added
     */
    private String addLineNumbersToCode(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        // Split the code into lines
        String[] lines = code.split("\n");

        // Calculate column width based on the number of lines
        int columnWidth = String.valueOf(lines.length).length() + 1;

        // Format for right-aligned numbers with a colon
        String formatString = "%" + columnWidth + "d: %s";

        // Build the result
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            result.append(String.format(formatString, i + 1, lines[i])).append("\n");
        }

        return result.toString();
    }

    /**
     * Interface for code line number formatting
     */
    public interface CodeLineNumberFormatter {
        String format(String code);
    }

    /**
     * Scans the filesystem for screenshots belonging to this session and
     * reconstructs
     * screenshot sets organized by iteration number.
     *
     * @param context Android context to access cache directory
     */
    private void reconstructScreenshotSetsFromFilesystem(Context context) {
        // Clear existing screenshot sets since we're reconstructing from filesystem
        this.screenshotSets = new ArrayList<>();
        this.screenshotSetIterations = new ArrayList<>();

        // Get the screenshots directory
        File screenshotDir = new File(context.getCacheDir(), "screenshots");
        if (!screenshotDir.exists() || !screenshotDir.isDirectory()) {
            Log.d(TAG, "Screenshots directory does not exist: " + screenshotDir.getAbsolutePath());
            return;
        }

        // Pattern to match screenshot files:
        // session_{sessionId}_iter_{iteration}_{timestamp}.png
        String sessionIdStr = this.id.toString();
        Pattern screenshotPattern = Pattern
                .compile("session_" + Pattern.quote(sessionIdStr) + "_iter_(\\d+)_\\d+\\.png");

        // Map to group screenshots by iteration number
        Map<Integer, List<String>> iterationScreenshots = new HashMap<>();

        // Scan all files in the screenshots directory
        File[] files = screenshotDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    Matcher matcher = screenshotPattern.matcher(file.getName());
                    if (matcher.matches()) {
                        try {
                            int iteration = Integer.parseInt(matcher.group(1));
                            String absolutePath = file.getAbsolutePath();

                            // Add to the appropriate iteration group
                            iterationScreenshots.computeIfAbsent(iteration, k -> new ArrayList<>()).add(absolutePath);

                            Log.d(TAG, "Found screenshot for session " + sessionIdStr +
                                    ", iteration " + iteration + ": " + file.getName());
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Invalid iteration number in screenshot filename: " + file.getName());
                        }
                    }
                }
            }
        }

        // Convert the map to a list of screenshot sets, ordered by iteration number
        if (!iterationScreenshots.isEmpty()) {
            List<Integer> sortedIterations = new ArrayList<>(iterationScreenshots.keySet());
            Collections.sort(sortedIterations);

            for (Integer iteration : sortedIterations) {
                List<String> screenshots = iterationScreenshots.get(iteration);
                if (screenshots != null && !screenshots.isEmpty()) {
                    // Sort screenshots within each iteration by timestamp (extracted from filename)
                    screenshots.sort((path1, path2) -> {
                        try {
                            String name1 = new File(path1).getName();
                            String name2 = new File(path2).getName();

                            // Extract timestamp from filename
                            String timestamp1 = name1.substring(name1.lastIndexOf('_') + 1, name1.lastIndexOf('.'));
                            String timestamp2 = name2.substring(name2.lastIndexOf('_') + 1, name2.lastIndexOf('.'));

                            long ts1 = Long.parseLong(timestamp1);
                            long ts2 = Long.parseLong(timestamp2);

                            return Long.compare(ts1, ts2);
                        } catch (Exception e) {
                            Log.w(TAG, "Error sorting screenshots by timestamp", e);
                            return 0;
                        }
                    });

                    this.screenshotSets.add(new ArrayList<>(screenshots));
                    this.screenshotSetIterations.add(iteration);
                    Log.d(TAG, "Added " + screenshots.size() + " screenshots for iteration " + iteration);
                }
            }

            Log.d(TAG, "Reconstructed " + this.screenshotSets.size() + " screenshot sets from filesystem");
        } else {
            Log.d(TAG, "No screenshots found for session " + sessionIdStr);
        }
    }

    /**
     * Converts this session to a JSONObject
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id.toString());
        if (sessionName != null) {
            json.put("sessionName", sessionName);
        }
        json.put("description", description);
        json.put("createdAt", createdAt.getTime());
        json.put("initialCode", initialCode);

        // Only include LLM type and model if they're not null
        if (llmType != null) {
            json.put("llmType", llmType.name());
        }

        if (llmModel != null) {
            json.put("llmModel", llmModel);
        }

        JSONArray messagesJson = new JSONArray();
        List<LLMClient.Message> chatHistory = getChatHistory();
        for (LLMClient.Message message : chatHistory) {
            JSONObject messageJson = new JSONObject();
            messageJson.put("role", message.role.getApiValue());
            messageJson.put("content", message.content);

            // Add model information and extracted code for assistant responses
            if (message.role == LLMClient.MessageRole.ASSISTANT) {
                LLMClient.AssistantResponse assistantMsg = (LLMClient.AssistantResponse) message;
                if (assistantMsg.getModelProvider() != null && assistantMsg.getModelName() != null) {
                    messageJson.put("modelProvider", assistantMsg.getModelProvider().name());
                    messageJson.put("modelName", assistantMsg.getModelName());
                }
                messageJson.put("completionStatus", assistantMsg.getCompletionStatus().name());

                // Save the complete code extraction result
                LLMClient.CodeExtractionResult codeResult = assistantMsg.getCodeExtractionResult();
                if (codeResult != null) {
                    JSONObject codeResultJson = new JSONObject();
                    codeResultJson.put("success", codeResult.success);
                    codeResultJson.put("code", codeResult.code != null ? codeResult.code : "");
                    codeResultJson.put("textBeforeCode",
                            codeResult.textBeforeCode != null ? codeResult.textBeforeCode : "");
                    codeResultJson.put("textAfterCode",
                            codeResult.textAfterCode != null ? codeResult.textAfterCode : "");
                    codeResultJson.put("errorMessage", codeResult.errorMessage != null ? codeResult.errorMessage : "");
                    messageJson.put("codeExtractionResult", codeResultJson);
                }

                // For backwards compatibility, still save extractedCode field
                String extractedCode = assistantMsg.getExtractedCode();
                messageJson.put("extractedCode", extractedCode != null ? extractedCode : "");
            }

            // Add image file paths and MIME types if the message has images
            if (message.role == LLMClient.MessageRole.USER) {
                LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
                if (userMsg.hasImages()) {
                    List<File> validImages = userMsg.getValidImageFiles();
                    List<String> validMimes = userMsg.getValidMimeTypes();
                    if (!validImages.isEmpty()) {
                        // For backward compatibility, store first image in old fields
                        messageJson.put("imageFile", validImages.get(0).getPath());
                        messageJson.put("mimeType", validMimes.get(0));

                        // Store all images in new array fields
                        JSONArray imageFiles = new JSONArray();
                        JSONArray mimeTypes = new JSONArray();
                        for (File imageFile : validImages) {
                            imageFiles.put(imageFile.getPath());
                        }
                        for (String mimeType : validMimes) {
                            mimeTypes.put(mimeType);
                        }
                        messageJson.put("imageFiles", imageFiles);
                        messageJson.put("mimeTypes", mimeTypes);
                    }
                }
            }

            // Add additional fields for user messages
            if (message.role == LLMClient.MessageRole.USER) {
                LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
                if (userMsg.getLogcat() != null) {
                    messageJson.put("logcat", userMsg.getLogcat());
                }
                if (userMsg.getFeedback() != null) {
                    messageJson.put("feedback", userMsg.getFeedback());
                }
                if (userMsg.getInitialCode() != null) {
                    messageJson.put("initialCode", userMsg.getInitialCode());
                }
            }

            // Add marker-specific fields
            if (message.role == LLMClient.MessageRole.MARKER) {
                if (message instanceof LLMClient.AutoIterationMarker) {
                    LLMClient.AutoIterationMarker marker = (LLMClient.AutoIterationMarker) message;
                    messageJson.put("markerType", "AutoIterationMarker");
                    messageJson.put("autoIterationEvent", marker.getEvent().name());
                }
            }

            messagesJson.put(messageJson);
        }
        json.put("chatHistory", messagesJson);

        // Add new fields
        if (lastLogcat != null) {
            json.put("lastLogcat", lastLogcat);
        }

        // Note: Screenshot sets are no longer saved to JSON.
        // They will be reconstructed from the filesystem during deserialization
        // based on the session ID and iteration numbers in the screenshot filenames.

        if (lastErrorFeedback != null) {
            json.put("lastErrorFeedback", lastErrorFeedback);
        }

        json.put("hasError", hasError);

        if (currentInputText != null) {
            json.put("currentInputText", currentInputText);
        }

        if (selectedImagePaths != null && !selectedImagePaths.isEmpty()) {
            JSONArray imagePathsArray = new JSONArray();
            for (String imagePath : selectedImagePaths) {
                imagePathsArray.put(imagePath);
            }
            json.put("selectedImagePaths", imagePathsArray);
        }

        // Save iteration errors
        if (iterationErrors != null && !iterationErrors.isEmpty()) {
            JSONObject errorsJson = new JSONObject();
            for (Map.Entry<Integer, String> entry : iterationErrors.entrySet()) {
                errorsJson.put(entry.getKey().toString(), entry.getValue());
            }
            json.put("iterationErrors", errorsJson);
        }

        // Save selected message index
        json.put("selectedMessageIndex", selectedMessageIndex);

        return json;
    }

    /**
     * Creates a DesignSession from a JSONObject
     *
     * @param json    The JSON object containing session data
     * @param context Android context needed to scan for screenshots in cache
     *                directory
     */
    public static DesignSession fromJson(JSONObject json, Context context) throws JSONException {
        DesignSession session = new DesignSession();
        session.id = UUID.fromString(json.getString("id"));
        if (json.has("sessionName")) {
            session.sessionName = json.getString("sessionName");
        }
        session.description = json.getString("description");
        session.createdAt = new Date(json.getLong("createdAt"));

        if (json.has("initialCode")) {
            try {
                session.setInitialCode(json.getString("initialCode"));
            } catch (JSONException e) {
                Log.w(TAG, "Invalid initialCode in session: " + json.getString("initialCode"));
            }
        }

        // Handle potentially missing LLM type and model
        if (json.has("llmType")) {
            try {
                session.llmType = LLMClientFactory.LLMType.valueOf(json.getString("llmType"));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid LLM type in session: " + json.getString("llmType"));
            }
        }

        if (json.has("llmModel")) {
            session.llmModel = json.getString("llmModel");
        }

        List<LLMClient.Message> chatHistory = new ArrayList<>();
        if (json.has("chatHistory")) {
            JSONArray messagesJson = json.getJSONArray("chatHistory");
            for (int i = 0; i < messagesJson.length(); i++) {
                JSONObject messageJson = messagesJson.getJSONObject(i);
                String roleString = messageJson.getString("role");
                String content = messageJson.getString("content");

                // Convert string role to enum
                LLMClient.MessageRole role = LLMClient.MessageRole.fromApiValue(roleString);
                if (role == null) {
                    Log.w(TAG, "Unknown role in session: " + roleString + ", defaulting to USER");
                    role = LLMClient.MessageRole.USER;
                }

                // Get model information for assistant responses
                LLMClientFactory.LLMType modelProvider = null;
                String modelName = null;
                if (role == LLMClient.MessageRole.ASSISTANT && messageJson.has("modelProvider")
                        && messageJson.has("modelName")) {
                    try {
                        modelProvider = LLMClientFactory.LLMType.valueOf(messageJson.getString("modelProvider"));
                        modelName = messageJson.getString("modelName");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Invalid model provider in session: " + messageJson.getString("modelProvider"));
                    }
                }

                // Create appropriate message type based on role
                if (role == LLMClient.MessageRole.SYSTEM) {
                    chatHistory.add(new LLMClient.SystemPrompt(content));
                } else if (role == LLMClient.MessageRole.MARKER) {
                    // Handle marker deserialization
                    if (messageJson.has("markerType")) {
                        String markerType = messageJson.getString("markerType");
                        if ("AutoIterationMarker".equals(markerType) && messageJson.has("autoIterationEvent")) {
                            try {
                                LLMClient.AutoIterationMarker.AutoIterationEvent event = LLMClient.AutoIterationMarker.AutoIterationEvent
                                        .valueOf(messageJson.getString("autoIterationEvent"));
                                chatHistory.add(new LLMClient.AutoIterationMarker(event));
                            } catch (IllegalArgumentException e) {
                                Log.w(TAG, "Invalid auto iteration event: " + messageJson.getString("autoIterationEvent"));
                            }
                        } else {
                            Log.w(TAG, "Unknown or incomplete marker type: " + markerType);
                        }
                    } else {
                        Log.w(TAG, "Marker message missing markerType field");
                    }
                } else if (role == LLMClient.MessageRole.ASSISTANT) {
                    // Handle code extraction result field
                    LLMClient.CodeExtractionResult codeResult = null;
                    LLMClient.AssistantResponse.CompletionStatus completionStatus = LLMClient.AssistantResponse.CompletionStatus.COMPLETE;
                    if (messageJson.has("completionStatus")) {
                        try {
                            completionStatus = LLMClient.AssistantResponse.CompletionStatus
                                    .valueOf(messageJson.getString("completionStatus"));
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "Invalid completion status in session: " + messageJson.getString("completionStatus"));
                        }
                    }

                    if (messageJson.has("codeExtractionResult")) {
                        // New format with complete CodeExtractionResult
                        JSONObject codeResultJson = messageJson.getJSONObject("codeExtractionResult");
                        boolean success = codeResultJson.getBoolean("success");
                        String code = codeResultJson.getString("code");
                        String textBeforeCode = codeResultJson.getString("textBeforeCode");
                        String textAfterCode = codeResultJson.getString("textAfterCode");
                        String errorMessage = codeResultJson.getString("errorMessage");

                        // Convert empty strings back to null for consistency
                        code = code.isEmpty() ? null : code;
                        textBeforeCode = textBeforeCode.isEmpty() ? null : textBeforeCode;
                        textAfterCode = textAfterCode.isEmpty() ? null : textAfterCode;
                        errorMessage = errorMessage.isEmpty() ? null : errorMessage;

                        if (success) {
                            codeResult = LLMClient.CodeExtractionResult.success(code, textBeforeCode, textAfterCode);
                        } else {
                            codeResult = LLMClient.CodeExtractionResult.failure(errorMessage);
                        }

                        // Use constructor with explicit CodeExtractionResult
                        chatHistory.add(new LLMClient.AssistantResponse(content, modelProvider, modelName,
                                completionStatus, codeResult));
                    } else if (messageJson.has("extractedCode")) {
                        // TODO(extractedCode): remove this else if
                        // Backwards compatibility: old format with just extractedCode
                        String codeValue = messageJson.getString("extractedCode");
                        String extractedCode = (codeValue != null && !codeValue.trim().isEmpty()) ? codeValue : null;

                        LLMClient.CodeExtractionResult extractedResult;
                        if (extractedCode != null) {
                            extractedResult = LLMClient.CodeExtractionResult.success(extractedCode, "", "");
                        } else {
                            extractedResult = LLMClient.CodeExtractionResult.success("",
                                    content != null ? content : "", "");
                        }

                        chatHistory.add(new LLMClient.AssistantResponse(content, modelProvider, modelName,
                                completionStatus, extractedResult));
                    } else {
                        // For compatibility: no code field exists, use regular constructor (which will
                        // auto-extract)
                        if (modelProvider != null && modelName != null) {
                            chatHistory.add(new LLMClient.AssistantResponse(content, modelProvider, modelName,
                                    completionStatus));
                        } else {
                            chatHistory.add(new LLMClient.AssistantResponse(content, null, null, completionStatus));
                        }
                    }
                } else if (role == LLMClient.MessageRole.USER) {
                    // Get additional fields for user messages
                    String logcat = null;
                    String feedback = null;
                    String messageInitialCode = null;
                    if (messageJson.has("logcat")) {
                        logcat = messageJson.getString("logcat");
                    }
                    if (messageJson.has("feedback")) {
                        feedback = messageJson.getString("feedback");
                    }
                    if (messageJson.has("initialCode")) {
                        messageInitialCode = messageJson.getString("initialCode");
                    }

                    // Check if the message has images (new format with arrays)
                    if (messageJson.has("imageFiles") && messageJson.has("mimeTypes")) {
                        JSONArray imagePathsArray = messageJson.getJSONArray("imageFiles");
                        JSONArray mimeTypesArray = messageJson.getJSONArray("mimeTypes");

                        List<File> imageFiles = new ArrayList<>();
                        List<String> mimeTypes = new ArrayList<>();

                        // Process all images
                        for (int imgIdx = 0; imgIdx < imagePathsArray.length()
                                && imgIdx < mimeTypesArray.length(); imgIdx++) {
                            String imagePath = imagePathsArray.getString(imgIdx);
                            String mimeType = mimeTypesArray.getString(imgIdx);
                            File imageFile = new File(imagePath);

                            if (imageFile.exists()) {
                                imageFiles.add(imageFile);
                                mimeTypes.add(mimeType);
                            } else {
                                Log.w(TAG, "Image file not found during deserialization: " + imagePath);
                            }
                        }

                        if (!imageFiles.isEmpty()) {
                            chatHistory.add(new LLMClient.UserMessage(content, imageFiles, mimeTypes, logcat, feedback,
                                    messageInitialCode));
                        } else {
                            chatHistory.add(new LLMClient.UserMessage(content, logcat, feedback, messageInitialCode));
                        }
                    }
                    // Check if the message has a single image (old format for backward
                    // compatibility)
                    else if (messageJson.has("imageFile") && messageJson.has("mimeType")) {
                        String imagePath = messageJson.getString("imageFile");
                        String mimeType = messageJson.getString("mimeType");
                        File imageFile = new File(imagePath);

                        // Only create message with image if the file exists
                        if (imageFile.exists()) {
                            // Convert single image to list for new API
                            List<File> singleImageList = new ArrayList<>();
                            List<String> singleMimeList = new ArrayList<>();
                            singleImageList.add(imageFile);
                            singleMimeList.add(mimeType);
                            chatHistory.add(new LLMClient.UserMessage(content, singleImageList, singleMimeList, logcat,
                                    feedback,
                                    messageInitialCode));
                        } else {
                            // File doesn't exist, create regular message
                            Log.w(TAG, "Image file not found during deserialization: " + imagePath);
                            chatHistory.add(new LLMClient.UserMessage(content, logcat, feedback, messageInitialCode));
                        }
                    } else {
                        // Regular message without image
                        chatHistory.add(new LLMClient.UserMessage(content, logcat, feedback, messageInitialCode));
                    }
                }
            }
        }

        // Now from the reconstructed chat history, rebuild the
        // LLMClient.ChatSession object. Could've done this in the loop above
        // but I find this easier to read.
        String lastCode = null;
        for (LLMClient.Message message : chatHistory) {
            if (LLMClient.MessageRole.SYSTEM.equals(message.role)) {
                LLMClient.SystemPrompt systemPrompt = (LLMClient.SystemPrompt) message;
                session.chatSession.queueSystemPrompt(systemPrompt);
            } else if (LLMClient.MessageRole.USER.equals(message.role)) {
                LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
                session.chatSession.queueUserMessage(userMsg);
            } else if (LLMClient.MessageRole.ASSISTANT.equals(message.role)) {
                LLMClient.AssistantResponse assistantMsg = (LLMClient.AssistantResponse) message;
                session.chatSession.queueAssistantResponse(assistantMsg);
                String extractedCode = assistantMsg.getExtractedCode();
                if (extractedCode != null && !extractedCode.isEmpty()) {
                    lastCode = extractedCode;
                } else {
                    // Fallback for backwards compatibility if extractedCode is null
                    LLMClient.CodeExtractionResult extractionResult = LLMClient
                            .extractClojureCode(message.content);
                    if (extractionResult.success) {
                        lastCode = extractionResult.code;
                    }
                }
            } else if (LLMClient.MessageRole.MARKER.equals(message.role)) {
                LLMClient.Marker marker = (LLMClient.Marker) message;
                session.chatSession.queueMarker(marker);
            } else {
                throw new JSONException("Unknown message type " + message.role);
            }
        }

        // Load new fields
        if (json.has("lastLogcat")) {
            session.lastLogcat = json.getString("lastLogcat");
        }

        // Reconstruct screenshot sets from filesystem instead of loading from JSON
        session.reconstructScreenshotSetsFromFilesystem(context);

        if (json.has("lastErrorFeedback")) {
            session.lastErrorFeedback = json.getString("lastErrorFeedback");
        }

        if (json.has("hasError")) {
            session.hasError = json.getBoolean("hasError");
        }

        if (json.has("currentInputText")) {
            session.currentInputText = json.getString("currentInputText");
        }

        String selectedImagePath = null;
        if (json.has("selectedImagePath")) {
            selectedImagePath = json.getString("selectedImagePath");
        }

        if (json.has("selectedImagePaths")) {
            JSONArray imagePathsArray = json.getJSONArray("selectedImagePaths");
            List<String> imagePaths = new ArrayList<>();
            for (int i = 0; i < imagePathsArray.length(); i++) {
                imagePaths.add(imagePathsArray.getString(i));
            }
            session.selectedImagePaths = imagePaths;
        } else if (selectedImagePath != null) {
            // For backward compatibility: if we have a single path but no paths array,
            // convert the single path to a list
            session.selectedImagePaths = new ArrayList<>();
            session.selectedImagePaths.add(selectedImagePath);
        }

        // Load iteration errors
        if (json.has("iterationErrors")) {
            JSONObject errorsJson = json.getJSONObject("iterationErrors");
            session.iterationErrors = new HashMap<>();

            @SuppressWarnings("unchecked")
            java.util.Iterator<String> keys = errorsJson.keys();
            while (keys.hasNext()) {
                String iterationStr = keys.next();
                try {
                    int iteration = Integer.parseInt(iterationStr);
                    String errorMessage = errorsJson.getString(iterationStr);
                    session.iterationErrors.put(iteration, errorMessage);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid iteration number in error data: " + iterationStr);
                }
            }
        }

        // Load selected message index
        if (json.has("selectedMessageIndex")) {
            session.selectedMessageIndex = json.getInt("selectedMessageIndex");
        }

        return session;
    }

    /**
     * Returns a short summary of the session for display
     */
    public String getDisplaySummary() {
        // Use session name if available, otherwise use description
        String displayText = sessionName != null && !sessionName.trim().isEmpty()
                ? sessionName
                : description;

        if (displayText == null || displayText.trim().isEmpty()) {
            displayText = "Untitled Session";
        }

        return displayText.length() > 50
                ? displayText.substring(0, 47) + "..."
                : displayText;
    }
}
