package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONException;

public abstract class LLMClient {
    private static final String TAG = "LLMClient";
    private static final String PROMPT_TEMPLATE_PATH = "prompt.txt";

    protected final Context context;
    private String promptTemplate;
    protected final ChatSession chatSession;

    /**
     * A cancellable CompletableFuture that can be cancelled and tracks cancellation
     * state
     */
    public static class CancellableCompletableFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        public CancellableCompletableFuture() {
            super();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelled.compareAndSet(false, true)) {
                Log.d(TAG, "Cancelling CompletableFuture");
                return super.cancel(mayInterruptIfRunning);
            }
            return false;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get() || super.isCancelled();
        }

        @Override
        public boolean complete(T value) {
            if (completed.compareAndSet(false, true)) {
                return super.complete(value);
            }
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            if (completed.compareAndSet(false, true)) {
                return super.completeExceptionally(ex);
            }
            return false;
        }

        public boolean isCompleted() {
            return completed.get() || super.isDone();
        }

        public boolean isCancelledOrCompleted() {
            return isCancelled() || isCompleted();
        }
    }

    public ChatSession getChatSession() {
        return chatSession;
    }

    public LLMClient(Context context, ChatSession chatSession) {
        Log.d(TAG, "Creating new LLMClient");
        this.context = context.getApplicationContext();
        this.chatSession = chatSession;
        loadPromptTemplate();
    }

    /**
     * Enum representing the different message roles in chat conversations
     */
    public enum MessageRole {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        MARKER("marker");

        private final String apiValue;

        MessageRole(String apiValue) {
            this.apiValue = apiValue;
        }

        /**
         * Get the string value used by the API
         * 
         * @return The API string value
         */
        public String getApiValue() {
            return apiValue;
        }

        /**
         * Get the MessageRole enum from an API string value
         * 
         * @param apiValue The API string value
         * @return The corresponding MessageRole enum, or null if not found
         */
        public static MessageRole fromApiValue(String apiValue) {
            for (MessageRole role : values()) {
                if (role.apiValue.equals(apiValue)) {
                    return role;
                }
            }
            return null;
        }
    }

    /**
     * Gets the LLM type for this client
     * 
     * @return The LLMType enum value
     */
    public abstract LLMClientFactory.LLMType getType();

    /**
     * Gets the current model name for this client
     * 
     * @return The model name as a string
     */
    public abstract String getModel();

    private void loadPromptTemplate() {
        Log.d(TAG, "Loading prompt template");
        // Try to load from assets
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(PROMPT_TEMPLATE_PATH)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            promptTemplate = sb.toString();
            Log.d(TAG, "Loaded prompt template from assets");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load prompt template from assets: " + PROMPT_TEMPLATE_PATH, e);
            promptTemplate = "";
        }
    }

    /**
     * Format initial prompt with option to provide starting code
     * 
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return Formatted prompt string
     */
    public String formatInitialPrompt(String description, String initialCode) {
        Log.d(TAG, "Formatting initial prompt with description: " + description +
                ", initial code provided: " + (initialCode != null && !initialCode.isEmpty()));

        StringBuilder prompt = new StringBuilder();
        prompt.append(description != null ? description.trim() : "")
                .append("\n");

        if (initialCode != null && !initialCode.isEmpty()) {
            prompt.append(
                    "\nExisting code for context (do not rewrite unless I request an updated version):\n```clojure\n")
                    .append(initialCode)
                    .append("\n```");
        }

        return prompt.toString();
    }

    /**
     * Returns the system prompt to be used when initializing a conversation.
     * This prompt provides instruction on how to generate Clojure code.
     */
    public String getSystemPrompt() {
        return promptTemplate;
    }

    public String formatIterationPrompt(String description,
            String currentCode,
            String logcat,
            String feedback,
            boolean hasImages,
            boolean forceCodeGeneration) {
        Log.d(TAG, "Formatting iteration prompt with description: " + description +
                ", feedback: " + feedback +
                ", hasImages: " + hasImages +
                ", forceCodeGeneration: " + forceCodeGeneration);

        boolean hasLogcat = logcat != null && !logcat.isEmpty();
        String sanitizedFeedback = feedback != null ? feedback.trim() : "";

        if (forceCodeGeneration) {
            if (hasLogcat) {
                return String.format(
                        "The app needs work. Provide an improved version addressing the feedback%s logcat output%s.\n" +
                                "User feedback: %s\n" +
                                "Logcat output:\n```\n%s\n```\n\n" +
                                "Return the complete updated Clojure app in a single ```clojure``` block. Include any brief rationale before the code.",
                        hasImages ? "," : " and",
                        hasImages ? ", and attached images" : "",
                        sanitizedFeedback,
                        logcat);
            } else {
                return String.format(
                        "The app needs work. Provide an improved version addressing the feedback%s.\n" +
                                "User feedback: %s\n\n" +
                                "Return the complete updated Clojure app in a single ```clojure``` block. Include any brief rationale before the code.",
                        hasImages ? " and attached images" : "",
                        sanitizedFeedback);
            }
        }

        StringBuilder prompt = new StringBuilder();
        if (sanitizedFeedback != null && !sanitizedFeedback.isEmpty()) {
            prompt.append(sanitizedFeedback)
                    .append("\n\n");
        }

        if (hasLogcat) {
            prompt.append("Relevant logcat output:\n```\n")
                    .append(logcat)
                    .append("\n```\n\n");
        }

        if (hasImages) {
            prompt.append("The user also provided screenshots/images for additional context.\n\n");
        }

        return prompt.toString().trim();
    }

    // Base Message class for chat history
    public static abstract class Message {
        public final MessageRole role;
        public final String content;

        protected Message(MessageRole role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // System message - simple text only
    public static class SystemPrompt extends Message {
        public SystemPrompt(String content) {
            super(MessageRole.SYSTEM, content);
        }
    }

    // User message - can have images, logcat, feedback, initialCode
    public static class UserMessage extends Message {
        public final List<File> imageFiles;
        public final List<String> mimeTypes;
        public final String logcat;
        public final String feedback;
        public final String initialCode;

        public UserMessage(String content) {
            super(MessageRole.USER, content);
            this.imageFiles = new ArrayList<>();
            this.mimeTypes = new ArrayList<>();
            this.logcat = null;
            this.feedback = null;
            this.initialCode = null;
        }

        public UserMessage(String content, List<File> imageFiles, List<String> mimeTypes) {
            super(MessageRole.USER, content);
            this.imageFiles = imageFiles != null ? new ArrayList<>(imageFiles) : new ArrayList<>();
            this.mimeTypes = mimeTypes != null ? new ArrayList<>(mimeTypes) : new ArrayList<>();
            this.logcat = null;
            this.feedback = null;
            this.initialCode = null;
        }

        public UserMessage(String content, String logcat, String feedback, String initialCode) {
            super(MessageRole.USER, content);
            this.imageFiles = new ArrayList<>();
            this.mimeTypes = new ArrayList<>();
            this.logcat = logcat;
            this.feedback = feedback;
            this.initialCode = initialCode;
        }

        public UserMessage(String content, List<File> imageFiles, List<String> mimeTypes,
                String logcat, String feedback, String initialCode) {
            super(MessageRole.USER, content);
            this.imageFiles = imageFiles != null ? new ArrayList<>(imageFiles) : new ArrayList<>();
            this.mimeTypes = mimeTypes != null ? new ArrayList<>(mimeTypes) : new ArrayList<>();
            this.logcat = logcat;
            this.feedback = feedback;
            this.initialCode = initialCode;
        }

        /**
         * Constructor that automatically detects MIME types from image files
         *
         * @param content     The user message content
         * @param imageFiles  The image files to attach (MIME types will be
         *                    auto-detected)
         * @param logcat      The logcat output (can be null)
         * @param feedback    The user feedback (can be null)
         * @param initialCode The initial code (can be null)
         */
        public UserMessage(String content, List<File> imageFiles, String logcat, String feedback, String initialCode) {
            super(MessageRole.USER, content);
            this.imageFiles = imageFiles != null ? new ArrayList<>(imageFiles) : new ArrayList<>();
            this.logcat = logcat;
            this.feedback = feedback;
            this.initialCode = initialCode;

            // Auto-detect MIME types for all images
            this.mimeTypes = new ArrayList<>();
            if (this.imageFiles != null) {
                for (File imageFile : this.imageFiles) {
                    if (imageFile != null) {
                        this.mimeTypes.add(determineMimeType(imageFile));
                    }
                }
            }
        }

        /**
         * Check if this message has any images
         * 
         * @return true if the message has at least one valid image, false otherwise
         */
        public boolean hasImage() {
            return hasImages();
        }

        /**
         * Check if this message has any images
         * 
         * @return true if the message has at least one valid image, false otherwise
         */
        public boolean hasImages() {
            if (imageFiles == null || imageFiles.isEmpty()) {
                return false;
            }
            for (File imageFile : imageFiles) {
                if (imageFile != null && imageFile.exists() && imageFile.canRead()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Get the list of valid image files
         * 
         * @return List of valid image files
         */
        public List<File> getValidImageFiles() {
            List<File> validFiles = new ArrayList<>();
            if (imageFiles != null) {
                for (File imageFile : imageFiles) {
                    if (imageFile != null && imageFile.exists() && imageFile.canRead()) {
                        validFiles.add(imageFile);
                    }
                }
            }
            return validFiles;
        }

        /**
         * Get the MIME types for valid images
         * 
         * @return List of MIME types corresponding to valid image files
         */
        public List<String> getValidMimeTypes() {
            List<String> validMimeTypes = new ArrayList<>();
            if (imageFiles != null && mimeTypes != null) {
                for (int i = 0; i < Math.min(imageFiles.size(), mimeTypes.size()); i++) {
                    File imageFile = imageFiles.get(i);
                    if (imageFile != null && imageFile.exists() && imageFile.canRead()) {
                        validMimeTypes.add(mimeTypes.get(i));
                    }
                }
            }
            return validMimeTypes;
        }

        /**
         * Get the logcat output associated with this user message
         * 
         * @return The logcat output, or null if not present
         */
        public String getLogcat() {
            return logcat;
        }

        /**
         * Get the feedback associated with this user message
         * 
         * @return The feedback, or null if not present
         */
        public String getFeedback() {
            return feedback;
        }

        /**
         * Get the initial code associated with this user message
         * 
         * @return The initial code, or null if not present
         */
        public String getInitialCode() {
            return initialCode;
        }
    }

    // Abstract marker class for internal markers
    public static abstract class Marker extends Message {
        protected Marker(String content) {
            super(MessageRole.MARKER, content);
        }
    }

    // Auto iteration marker - marks automatic iteration events
    public static class AutoIterationMarker extends Marker {
        public enum AutoIterationEvent {
            START,
            DONE,
            ERROR,
            CANCEL
        }

        public final AutoIterationEvent event;

        public AutoIterationMarker(AutoIterationEvent event) {
            super(""); // Content is empty for markers
            this.event = event;
        }

        /**
         * Get the auto iteration event type
         *
         * @return The event type
         */
        public AutoIterationEvent getEvent() {
            return event;
        }
    }

    // Assistant response - can have model provider info
    public static class AssistantResponse extends Message {
        public enum CompletionStatus {
            COMPLETE,
            TRUNCATED_MAX_TOKENS
        }

        public final LLMClientFactory.LLMType modelProvider;
        public final String modelName;
        public final CodeExtractionResult codeExtractionResult; // Complete code extraction result
        private final CompletionStatus completionStatus;

        public AssistantResponse(String content) {
            this(content, null, null, CompletionStatus.COMPLETE,
                    extractJsonResponse(content, false));
        }

        public AssistantResponse(String content, LLMClientFactory.LLMType modelProvider, String modelName) {
            this(content, modelProvider, modelName, CompletionStatus.COMPLETE,
                    extractJsonResponse(content, false));
        }

        public AssistantResponse(String content, LLMClientFactory.LLMType modelProvider, String modelName,
                CompletionStatus completionStatus) {
            this(content, modelProvider, modelName, completionStatus,
                    extractJsonResponse(content, completionStatus == CompletionStatus.TRUNCATED_MAX_TOKENS));
        }

        /**
         * Constructor for deserialization with explicit code extraction result
         */
        public AssistantResponse(String content, LLMClientFactory.LLMType modelProvider, String modelName,
                CodeExtractionResult codeExtractionResult) {
            this(content, modelProvider, modelName, CompletionStatus.COMPLETE, codeExtractionResult);
        }

        public AssistantResponse(String content, LLMClientFactory.LLMType modelProvider, String modelName,
                CompletionStatus completionStatus, CodeExtractionResult codeExtractionResult) {
            super(MessageRole.ASSISTANT, content);
            this.modelProvider = modelProvider;
            this.modelName = modelName;
            this.codeExtractionResult = codeExtractionResult;
            this.completionStatus = completionStatus != null ? completionStatus : CompletionStatus.COMPLETE;
        }

        /**
         * Constructor for backwards compatibility with extractedCode string
         * TODO(extractedCode): remove this else if
         */
        public AssistantResponse(String content, LLMClientFactory.LLMType modelProvider, String modelName,
                String extractedCode) {
            this(content, modelProvider, modelName, CompletionStatus.COMPLETE,
                    extractedCode != null && !extractedCode.trim().isEmpty()
                            ? CodeExtractionResult.success(extractedCode, "", "")
                            : CodeExtractionResult.success("", content != null ? content : "", ""));
        }

        /**
         * Get the model provider for this assistant response
         * 
         * @return The model provider, or null if not set
         */
        public LLMClientFactory.LLMType getModelProvider() {
            return modelProvider;
        }

        /**
         * Get the model name for this assistant response
         * 
         * @return The model name, or null if not set
         */
        public String getModelName() {
            return modelName;
        }

        /**
         * Get the extracted code for this assistant response
         *
         * @return The extracted Clojure code, or null if no code was found
         */
        public String getExtractedCode() {
            if (codeExtractionResult != null && codeExtractionResult.success &&
                    codeExtractionResult.code != null && !codeExtractionResult.code.trim().isEmpty()) {
                return codeExtractionResult.code;
            }
            return null;
        }

        /**
         * Get the reasoning for this assistant response
         *
         * @return The reasoning text, or null if no reasoning was found
         */
        public String getReasoning() {
            if (codeExtractionResult != null && codeExtractionResult.success &&
                    codeExtractionResult.reasoning != null && !codeExtractionResult.reasoning.trim().isEmpty()) {
                return codeExtractionResult.reasoning;
            }
            return null;
        }

        /**
         * Get the complete code extraction result for this assistant response
         *
         * @return The CodeExtractionResult containing code, text before/after, and
         *         success status
         */
        public CodeExtractionResult getCodeExtractionResult() {
            return codeExtractionResult;
        }

        /**
         * Get the completion status for this assistant response
         *
         * @return The completion status enum
         */
        public CompletionStatus getCompletionStatus() {
            return completionStatus;
        }

        /**
         * Convenience helper indicating whether the model stopped due to token limits
         *
         * @return true if the response was truncated by the model's max output token
         *         limit
         */
        public boolean wasTruncatedByTokenLimit() {
            return completionStatus == CompletionStatus.TRUNCATED_MAX_TOKENS;
        }
    }

    /**
     * Model properties data class
     */
    public static class ModelProperties {
        public final int maxInputTokens;
        public final int maxOutputTokens;
        public final boolean isMultimodal;
        public final String status;

        public ModelProperties(int maxInputTokens, int maxOutputTokens, boolean isMultimodal, String status) {
            this.maxInputTokens = maxInputTokens;
            this.maxOutputTokens = maxOutputTokens;
            this.isMultimodal = isMultimodal;
            this.status = status;
        }

        @Override
        public String toString() {
            return String.format("ModelProperties{maxInputTokens=%d, maxOutputTokens=%d, isMultimodal=%s, status='%s'}",
                    maxInputTokens, maxOutputTokens, isMultimodal, status);
        }
    }

    /**
     * Result class for code extraction operations
     * Now supports JSON structure with reasoning and code fields
     */
    public static class CodeExtractionResult {
        public final String reasoning;
        public final boolean reasoningComplete;
        public final String code;
        public final boolean codeComplete;
        public final String textBeforeCode;
        public final String textAfterCode;
        public final boolean success;
        public final String errorMessage;

        private CodeExtractionResult(String code, String reasoning, String textBeforeCode, String textAfterCode,
                boolean success, String errorMessage, boolean reasoningComplete, boolean codeComplete) {
            this.code = code;
            this.reasoning = reasoning;
            this.textBeforeCode = textBeforeCode;
            this.textAfterCode = textAfterCode;
            this.success = success;
            this.errorMessage = errorMessage;
            this.reasoningComplete = reasoningComplete;
            this.codeComplete = codeComplete;
        }

        public static CodeExtractionResult success(String code, String textBeforeCode, String textAfterCode) {
            return new CodeExtractionResult(code, null, textBeforeCode, textAfterCode, true, null, true, true);
        }

        public static CodeExtractionResult success(String code, String reasoning, String textBeforeCode,
                String textAfterCode, boolean reasoningComplete, boolean codeComplete) {
            return new CodeExtractionResult(code, reasoning, textBeforeCode, textAfterCode, true, null,
                    reasoningComplete, codeComplete);
        }

        public static CodeExtractionResult failure(String errorMessage) {
            return new CodeExtractionResult(null, null, null, null, false, errorMessage, false, false);
        }
    }

    /**
     * Extracts reasoning and code from a JSON response.
     * Handles partial responses by completing incomplete JSON fields.
     * Expected JSON structure from LLM:
     * {
     * "reasoning": "...",
     * "code": "..."
     * }
     * At least one of "reasoning" or "code" must be present.
     * 
     * The "complete" field is determined by us based on whether the response was
     * truncated.
     * If a field was cut off mid-way, we mark it as incomplete (complete: false).
     *
     * @param input     The input text that should contain JSON
     * @param isPartial Whether the response was truncated (e.g.,
     *                  TRUNCATED_MAX_TOKENS)
     * @return A CodeExtractionResult containing the extracted reasoning, code, and
     *         completion status
     */
    public static CodeExtractionResult extractJsonResponse(String input, boolean isPartial) {
        if (input == null || input.isEmpty()) {
            Log.d(TAG, "Input is null or empty, returning empty result");
            return CodeExtractionResult.success("", null, "", "", true, true);
        }

        String jsonText = input.trim();

        // Aggressively remove markdown code block markers if present
        // Handle cases like: ```json\n{...}\n``` or ```\n{...}\n``` or ```json{...}```
        // Also handle text before/after code blocks

        // Remove opening code fence (```json, ```clojure, ```, etc.)
        if (jsonText.startsWith("```")) {
            // Find the first newline after ```
            int firstNewline = jsonText.indexOf('\n');
            if (firstNewline != -1) {
                jsonText = jsonText.substring(firstNewline + 1);
            } else {
                // No newline, check if there's a language identifier
                // Pattern: ```json or ```clojure (3 backticks + optional language)
                int codeFenceEnd = 3; // Start after ```
                // Skip language identifier if present (alphanumeric characters)
                while (codeFenceEnd < jsonText.length() &&
                       Character.isLetterOrDigit(jsonText.charAt(codeFenceEnd))) {
                    codeFenceEnd++;
                }
                jsonText = jsonText.substring(codeFenceEnd);
            }
            jsonText = jsonText.trim();
        }

        // Remove closing code fence (```) - handle both end of string and before trailing text
        // Look for ``` that might be at the end or followed by whitespace/newlines
        int closingFenceIndex = jsonText.lastIndexOf("```");
        if (closingFenceIndex != -1) {
            // Check if it's actually a closing fence (not part of content)
            String afterFence = jsonText.substring(closingFenceIndex + 3).trim();
            // If there's only whitespace/newlines after the ```, it's a closing fence
            if (afterFence.isEmpty() || afterFence.matches("^[\\s\\n\\r]*$")) {
                jsonText = jsonText.substring(0, closingFenceIndex).trim();
            }
        }

        // Also check for closing ``` at the very end
        if (jsonText.endsWith("```")) {
            jsonText = jsonText.substring(0, jsonText.length() - 3).trim();
        }

        // Remove any remaining leading/trailing whitespace
        jsonText = jsonText.trim();

        // Try to find JSON object in the response
        // Look for the first '{' that starts a JSON object
        int jsonStart = jsonText.indexOf('{');
        if (jsonStart == -1) {
            Log.e(TAG, "No JSON object found in response");
            return CodeExtractionResult.failure(
                    "Response does not contain valid JSON. Expected JSON object with 'reasoning' and/or 'code' fields. Response must start with { and contain only JSON.");
        }

        // Warn if there's text before the JSON (shouldn't happen with updated prompt)
        if (jsonStart > 0) {
            String textBeforeJson = jsonText.substring(0, jsonStart).trim();
            Log.w(TAG, "Found text before JSON (should be pure JSON only): " + 
                    (textBeforeJson.length() > 100 ? textBeforeJson.substring(0, 100) + "..." : textBeforeJson));
        }

        // Extract the JSON portion - find the last } to ensure we get complete JSON
        String originalJsonContent = jsonText.substring(jsonStart);
        // Find the last closing brace to get the complete JSON object
        int jsonEnd = originalJsonContent.lastIndexOf('}');
        if (jsonEnd != -1) {
            originalJsonContent = originalJsonContent.substring(0, jsonEnd + 1);
        }
        String jsonContent = originalJsonContent;

        // Detect which fields were incomplete before completing the JSON
        boolean reasoningWasIncomplete = false;
        boolean codeWasIncomplete = false;

        if (isPartial) {
            // Check which field was being written when cut off
            PartialFieldInfo partialInfo = detectPartialFields(originalJsonContent);
            reasoningWasIncomplete = partialInfo.reasoningIncomplete;
            codeWasIncomplete = partialInfo.codeIncomplete;
        }

        // Always complete the JSON structure and add "complete" fields
        // If not partial, all fields are complete (true)
        // If partial, use the detected incomplete status
        jsonContent = completePartialJson(originalJsonContent,
                isPartial && reasoningWasIncomplete,
                isPartial && codeWasIncomplete);

        try {
            JSONObject json = new JSONObject(jsonContent);

            String reasoning = null;
            String code = null;
            boolean reasoningComplete = true;
            boolean codeComplete = true;

            // Extract reasoning field
            if (json.has("reasoning")) {
                Object reasoningObj = json.get("reasoning");
                if (reasoningObj instanceof String) {
                    reasoning = (String) reasoningObj;
                } else {
                    Log.w(TAG, "Reasoning field must be a string");
                    return CodeExtractionResult.failure(
                            "Invalid JSON structure: 'reasoning' field must be a string");
                }
                // Determine completion status based on whether response was partial
                reasoningComplete = !isPartial || !reasoningWasIncomplete;
            }

            // Extract code field
            if (json.has("code")) {
                Object codeObj = json.get("code");
                if (codeObj instanceof String) {
                    code = (String) codeObj;
                } else {
                    Log.w(TAG, "Code field must be a string");
                    return CodeExtractionResult
                            .failure("Invalid JSON structure: 'code' field must be a string");
                }
                // Determine completion status based on whether response was partial
                codeComplete = !isPartial || !codeWasIncomplete;
            }

            // Validate that at least one field is present
            if (reasoning == null && code == null) {
                Log.w(TAG, "JSON response has neither reasoning nor code fields");
                return CodeExtractionResult
                        .failure("JSON response must contain at least one of 'reasoning' or 'code' fields");
            }

            Log.d(TAG, "Successfully extracted JSON response. Reasoning length: " +
                    (reasoning != null ? reasoning.length() : 0) +
                    ", Code length: " + (code != null ? code.length() : 0) +
                    ", Reasoning complete: " + reasoningComplete +
                    ", Code complete: " + codeComplete);

            return CodeExtractionResult.success(
                    code != null ? code : "",
                    reasoning != null ? reasoning : null,
                    jsonStart > 0 ? jsonText.substring(0, jsonStart).trim() : "",
                    "",
                    reasoningComplete,
                    codeComplete);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse JSON response", e);
            // If JSON parsing failed and response is partial, try to extract what we can
            if (isPartial) {
                return extractPartialJsonFields(originalJsonContent, isPartial);
            }
            return CodeExtractionResult.failure("Failed to parse JSON response: " + e.getMessage());
        }
    }

    /**
     * Information about which fields were incomplete in a partial JSON response
     */
    private static class PartialFieldInfo {
        boolean reasoningIncomplete = false;
        boolean codeIncomplete = false;
    }

    /**
     * Detects which fields were incomplete in a partial JSON response.
     * Uses a single-pass parser to track JSON structure and detect which field's
     * content string was cut off.
     * 
     * This is more efficient than multiple string searches and handles edge cases
     * better by tracking the actual JSON structure as we parse.
     */
    private static PartialFieldInfo detectPartialFields(String json) {
        PartialFieldInfo info = new PartialFieldInfo();

        if (json == null || json.isEmpty()) {
            return info;
        }

        // Track JSON structure state
        boolean inString = false;
        boolean escaped = false;
        String currentField = null; // "reasoning" or "code"
        boolean inFieldValue = false; // Are we inside a field value?
        boolean expectingValue = false; // Did we just see a colon after a field name?

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"' && !escaped) {
                inString = !inString;
                if (!inString) {
                    // We just closed a string - check if it was a field name
                    // Look backwards to find the field name we just closed
                    int lookbackStart = Math.max(0, i - 20);
                    String recent = json.substring(lookbackStart, i);
                    if (recent.contains("\"reasoning\"")) {
                        currentField = "reasoning";
                        inFieldValue = false;
                        expectingValue = true; // Next value after colon is the field value
                    } else if (recent.contains("\"code\"")) {
                        currentField = "code";
                        inFieldValue = false;
                        expectingValue = true; // Next value after colon is the field value
                    }
                } else {
                    // Opening a string
                    if (expectingValue && currentField != null) {
                        // Direct string value after field name
                        inFieldValue = true;
                        expectingValue = false;
                    }
                }
                continue;
            }

            if (!inString) {
                if (c == ',' || c == '}') {
                    // Field value ended
                    if (inFieldValue && currentField != null) {
                        inFieldValue = false;
                        currentField = null;
                    }
                }
            }
        }

        // If we ended while still in a string and we're in a field value, mark that field incomplete
        if (inString && inFieldValue && currentField != null) {
            if ("reasoning".equals(currentField)) {
                info.reasoningIncomplete = true;
            } else if ("code".equals(currentField)) {
                info.codeIncomplete = true;
            }
        }

        return info;
    }

    /**
     * Attempts to complete a partial JSON string by closing any open structures.
     * This is a best-effort approach to make partial JSON parseable.
     * Handles incomplete string values by closing them properly.
     *
     * @param json                The partial JSON string
     * @param reasoningIncomplete Whether the reasoning field was cut off (unused, kept for compatibility)
     * @param codeIncomplete      Whether the code field was cut off (unused, kept for compatibility)
     * @return Completed JSON string
     */
    private static String completePartialJson(String json, boolean reasoningIncomplete, boolean codeIncomplete) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        StringBuilder completed = new StringBuilder(json);
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        boolean escaped = false;

        // Count open structures and track string state
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"' && !escaped) {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') {
                    openBraces++;
                } else if (c == '}') {
                    openBraces--;
                } else if (c == '[') {
                    openBrackets++;
                } else if (c == ']') {
                    openBrackets--;
                }
            }
        }

        // If we're in the middle of a string, close it
        if (inString) {
            completed.append('"');
        }

        // Close any open brackets first
        while (openBrackets > 0) {
            completed.append(']');
            openBrackets--;
        }

        // Close any open braces
        while (openBraces > 0) {
            completed.append('}');
            openBraces--;
        }

        return completed.toString();
    }

    /**
     * Finds the matching closing brace for an opening brace at the given position.
     * Returns -1 if not found.
     */
    private static int findMatchingBrace(String json, int openBracePos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBracePos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"' && !escaped) {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Attempts to extract fields from a partial JSON response that failed to parse.
     * Uses regex to extract field values even if JSON is incomplete.
     */
    private static CodeExtractionResult extractPartialJsonFields(String json, boolean isPartial) {
        String reasoning = null;
        String code = null;
        boolean reasoningComplete = false;
        boolean codeComplete = false;

        // Try to extract reasoning field using regex
        java.util.regex.Pattern reasoningPattern = java.util.regex.Pattern.compile(
                "\"reasoning\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\"",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher reasoningMatcher = reasoningPattern.matcher(json);
        if (reasoningMatcher.find()) {
            reasoning = reasoningMatcher.group(1).replace("\\\"", "\"").replace("\\n", "\n");
            reasoningComplete = true; // If we found the closing quote, it's complete
        }

        // Try to extract code field using regex
        java.util.regex.Pattern codePattern = java.util.regex.Pattern.compile(
                "\"code\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\"",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher codeMatcher = codePattern.matcher(json);
        if (codeMatcher.find()) {
            code = codeMatcher.group(1).replace("\\\"", "\"").replace("\\n", "\n");
            codeComplete = true; // If we found the closing quote, it's complete
        }

        if (reasoning == null && code == null) {
            return CodeExtractionResult.failure("Could not extract reasoning or code from partial JSON");
        }

        return CodeExtractionResult.success(
                code != null ? code : "",
                reasoning != null ? reasoning : null,
                "",
                "",
                reasoningComplete,
                codeComplete);
    }

    // Chat session implementation
    public static class ChatSession {
        private final String sessionId;
        private final List<Message> messages;
        private String systemPrompt = null;

        public ChatSession(String sessionId) {
            this.sessionId = sessionId;
            this.messages = new ArrayList<>();
            Log.d(TAG, "Created new chat session: " + sessionId);
        }

        /**
         * Resets the chat session by clearing all messages
         */
        public void reset() {
            Log.d(TAG, "Resetting chat session: " + sessionId);
            messages.clear();
            systemPrompt = null;
        }

        /**
         * Queues an assistant response object directly to the chat session
         *
         * @param assistantResponse The AssistantResponse object to queue
         */
        public void queueAssistantResponse(AssistantResponse assistantResponse) {
            Log.d(TAG, "Queuing assistant response object in session: " + sessionId +
                    " (provider: " + assistantResponse.getModelProvider() +
                    ", model: " + assistantResponse.getModelName() + ")");
            messages.add(assistantResponse);
        }

        /**
         * Queues a system prompt object directly to the chat session
         *
         * @param systemPrompt The SystemPrompt object to queue
         */
        public void queueSystemPrompt(SystemPrompt systemPrompt) {
            Log.d(TAG, "Queuing system prompt object in session: " + sessionId);
            this.systemPrompt = systemPrompt.content;
            messages.add(systemPrompt);
        }

        /**
         * Queues a user message object directly to the chat session
         *
         * @param userMessage The UserMessage object to queue
         */
        public void queueUserMessage(UserMessage userMessage) {
            Log.d(TAG, "Queuing user message object in session: " + sessionId +
                    " (has images: " + userMessage.hasImages() + ")");
            messages.add(userMessage);
        }

        /**
         * Queues a marker object directly to the chat session
         *
         * @param marker The Marker object to queue
         */
        public void queueMarker(Marker marker) {
            Log.d(TAG, "Queuing marker object in session: " + sessionId);
            messages.add(marker);
        }

        /**
         * Inserts a START AutoIterationMarker object.
         *
         * Needs to be done before the last-queued message in the history,
         * which must exist and be an AssistantResponse with code that errored
         * out.
         */
        public void queueAutoIterationStartMarker() {
            assert !messages.isEmpty() : "Chat session must have at least one message when auto-iteration starts";
            LLMClient.Message lastMessage = messages.get(messages.size() - 1);
            assert lastMessage.role == LLMClient.MessageRole.ASSISTANT
                    : "Last message must be an AssistantResponse when auto-iteration starts";
            int insertPosition = messages.size() - 1;
            Log.d(TAG, "Inserting marker object at index " + insertPosition + " in session: " + sessionId);
            messages.add(insertPosition,
                    new LLMClient.AutoIterationMarker(LLMClient.AutoIterationMarker.AutoIterationEvent.START));
        }

        /**
         * Retrieves the current list of messages in the chat session
         *
         * @return A list of Message objects representing the chat history
         */
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }

        /**
         * Get the session ID
         *
         * @return The session ID
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * Get the system prompt
         *
         * @return The system prompt, or null if not set
         */
        public String getSystemPrompt() {
            return systemPrompt;
        }

        /**
         * Check if system prompt is available
         *
         * @return true if system prompt is set and not empty
         */
        public boolean hasSystemPrompt() {
            return systemPrompt != null && !systemPrompt.trim().isEmpty();
        }

        /**
         * Removes the last N messages from the chat session
         * Used for rollback in case of failures after adding multiple messages
         *
         * @param count Number of messages to remove from the end
         * @return List of removed messages (in reverse order - last removed first)
         */
        public List<Message> removeLastMessages(int count) {
            List<Message> removed = new ArrayList<>();
            for (int i = 0; i < count && !messages.isEmpty(); i++) {
                Message msg = messages.remove(messages.size() - 1);
                removed.add(msg);
                Log.d(TAG, "Removed message " + (i + 1) + "/" + count + " from session: " + sessionId
                        + ", message role: " + msg.role);
            }
            return removed;
        }
    }

    /**
     * Functional interface for filtering and transforming messages before sending
     * to the LLM
     */
    @FunctionalInterface
    public interface MessageFilter {
        /**
         * Determines whether a message should be sent to the LLM and optionally
         * transforms it
         *
         * @param message The message to check
         * @param index   The index of the message in the message list
         * @return The message to send (may be the original or a transformed version),
         *         or null to filter it out
         */
        Message shouldSend(Message message, int index);
    }

    /**
     * Filters messages using the provided filter and logs the result.
     * This is a helper method to centralize filtering logic and logging.
     *
     * @param session       The chat session containing messages to filter
     * @param messageFilter A filter that determines which messages to send. If
     *                      null, all messages are returned.
     * @return The filtered list of messages to send
     */
    protected List<Message> filterMessages(ChatSession session, MessageFilter messageFilter) {
        if (messageFilter == null) {
            return session.getMessages();
        }

        List<Message> messagesToSend = new ArrayList<>();
        List<Message> allMessages = session.getMessages();
        for (int i = 0; i < allMessages.size(); i++) {
            Message msg = allMessages.get(i);
            Message filteredMsg = messageFilter.shouldSend(msg, i);
            if (filteredMsg != null) {
                messagesToSend.add(filteredMsg);
            }
        }

        Log.d(TAG, "Filtered messages: " + allMessages.size() + " -> " + messagesToSend.size());
        return messagesToSend;
    }

    /**
     * Sends messages from a chat session to the appropriate API
     * This method must be implemented by each LLMClient subclass
     *
     * @param session       The chat session containing messages to send
     * @param messageFilter A filter that determines which messages to send. If
     *                      null, all messages are sent.
     * @return A CancellableCompletableFuture containing the API response as an
     *         AssistantResponse
     */
    protected abstract CancellableCompletableFuture<AssistantResponse> sendMessages(ChatSession session,
            MessageFilter messageFilter);

    /**
     * Clears the API key for this client type
     *
     * @return true if key was successfully cleared, false otherwise
     */
    public abstract boolean clearApiKey();

    /**
     * Cancels the current API request if one is in progress
     * 
     * @return true if a request was cancelled, false if no request was in progress
     */
    public abstract boolean cancelCurrentRequest();

    // ============================================================================
    // Image Processing Utility Methods
    // ============================================================================

    /**
     * Determines the MIME type of an image file based on its extension
     *
     * @param imageFile The image file
     * @return The MIME type string
     */
    protected static String determineMimeType(File imageFile) {
        if (imageFile == null) {
            return "image/png"; // Default fallback
        }

        String fileName = imageFile.getName().toLowerCase();
        if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        } else if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        } else {
            // Default to PNG if we can't determine the type
            Log.w(TAG, "Unknown image format for file: " + fileName + ", defaulting to image/png");
            return "image/png";
        }
    }

    /**
     * Encodes an image file to base64 string, with automatic resizing to meet API
     * requirements
     *
     * @param imageFile The image file to encode
     * @return Base64 encoded string of the image
     * @throws IOException If there's an error reading the file
     */
    protected String encodeImageToBase64(File imageFile) throws IOException {
        // First, resize the image if needed to meet API requirements
        File resizedImage = resizeImageForAPI(imageFile);

        try (FileInputStream fis = new FileInputStream(resizedImage)) {
            byte[] imageBytes = new byte[(int) resizedImage.length()];
            fis.read(imageBytes);
            return Base64.getEncoder().encodeToString(imageBytes);
        } finally {
            // Clean up the temporary resized file if it's different from the original
            if (!resizedImage.equals(imageFile) && resizedImage.exists()) {
                resizedImage.delete();
            }
        }
    }

    /**
     * Resizes an image to meet API requirements:
     * - Maximum 8000x8000 pixels
     * - Optimal: no more than 1.15 megapixels and within 1568 pixels in both
     * dimensions
     * - Maximum file size: 5MB
     *
     * @param imageFile The original image file
     * @return The resized image file (or original if no resizing needed)
     * @throws IOException If there's an error processing the image
     */
    protected File resizeImageForAPI(File imageFile) throws IOException {
        // Decode image dimensions without loading the full image into memory
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

        int originalWidth = options.outWidth;
        int originalHeight = options.outHeight;

        Log.d(TAG, "Original image dimensions: " + originalWidth + "x" + originalHeight);

        // Check if resizing is needed
        boolean needsResizing = false;
        int targetWidth = originalWidth;
        int targetHeight = originalHeight;

        // API limits: max 8000x8000, optimal 1568x1568, max 1.15 megapixels
        final int MAX_DIMENSION = 8000;
        final int OPTIMAL_DIMENSION = 1568;
        final int MAX_MEGAPIXELS = 1150000; // 1.15 megapixels

        // Check if image exceeds maximum dimensions
        if (originalWidth > MAX_DIMENSION || originalHeight > MAX_DIMENSION) {
            needsResizing = true;
            if (originalWidth > originalHeight) {
                targetWidth = MAX_DIMENSION;
                targetHeight = (int) ((double) originalHeight * MAX_DIMENSION / originalWidth);
            } else {
                targetHeight = MAX_DIMENSION;
                targetWidth = (int) ((double) originalWidth * MAX_DIMENSION / originalHeight);
            }
        }

        // Check if image exceeds optimal dimensions
        if (targetWidth > OPTIMAL_DIMENSION || targetHeight > OPTIMAL_DIMENSION) {
            needsResizing = true;
            if (targetWidth > targetHeight) {
                targetWidth = OPTIMAL_DIMENSION;
                targetHeight = (int) ((double) targetHeight * OPTIMAL_DIMENSION / targetWidth);
            } else {
                targetHeight = OPTIMAL_DIMENSION;
                targetWidth = (int) ((double) targetWidth * OPTIMAL_DIMENSION / targetHeight);
            }
        }

        // Check if image exceeds maximum megapixels
        if (targetWidth * targetHeight > MAX_MEGAPIXELS) {
            needsResizing = true;
            double scale = Math.sqrt((double) MAX_MEGAPIXELS / (targetWidth * targetHeight));
            targetWidth = (int) (targetWidth * scale);
            targetHeight = (int) (targetHeight * scale);
        }

        if (!needsResizing) {
            Log.d(TAG, "Image does not need resizing");
            return imageFile;
        }

        Log.d(TAG, "Resizing image to: " + targetWidth + "x" + targetHeight);

        // Load and resize the image
        options.inJustDecodeBounds = false;
        options.inSampleSize = calculateInSampleSize(originalWidth, originalHeight, targetWidth, targetHeight);

        Bitmap originalBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
        if (originalBitmap == null) {
            throw new IOException("Failed to decode image: " + imageFile.getAbsolutePath());
        }

        // Create resized bitmap
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);
        originalBitmap.recycle();

        // Save resized image to temporary file
        File tempFile = File.createTempFile("llm_resized_", ".png", context.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        resizedBitmap.recycle();

        Log.d(TAG, "Resized image saved to: " + tempFile.getAbsolutePath() +
                ", size: " + tempFile.length() + " bytes");

        return tempFile;
    }

    /**
     * Calculates the inSampleSize for efficient bitmap loading
     */
    private int calculateInSampleSize(int originalWidth, int originalHeight, int targetWidth, int targetHeight) {
        int inSampleSize = 1;

        if (originalHeight > targetHeight || originalWidth > targetWidth) {
            int halfHeight = originalHeight / 2;
            int halfWidth = originalWidth / 2;

            while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
