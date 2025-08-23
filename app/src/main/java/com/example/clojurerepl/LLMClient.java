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
        ASSISTANT("assistant");

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
        try {
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
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not load prompt template from assets: " + e.getMessage());
            // Provide a default prompt if loading fails
            promptTemplate = "Create a Clojure app for Android. Use the following Android APIs as needed: " +
                    "android.widget, android.view, android.graphics, com.google.android.material.";
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
    protected String formatInitialPrompt(String description, String initialCode) {
        Log.d(TAG, "Formatting initial prompt with description: " + description +
                ", initial code provided: " + (initialCode != null && !initialCode.isEmpty()));

        if (initialCode != null && !initialCode.isEmpty()) {
            return "Implement the following app: " + description +
                    "\n\nUse the following code as a starting point. Improve and expand upon it to meet all requirements:\n```clojure\n"
                    +
                    initialCode + "\n```";
        } else {
            return "Implement the following app: " + description;
        }
    }

    /**
     * Returns the system prompt to be used when initializing a conversation.
     * This prompt provides instruction on how to generate Clojure code.
     */
    protected String getSystemPrompt() {
        return promptTemplate + "\n\nAlways respond with Clojure code in a markdown code block.";
    }

    public abstract CancellableCompletableFuture<AssistantMessage> generateInitialCode(UUID sessionId,
            String description);

    /**
     * Generate initial code with optional starting code
     * 
     * @param sessionId   The session UUID
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return A CancellableCompletableFuture containing the generated
     *         AssistantMessage
     */
    public abstract CancellableCompletableFuture<AssistantMessage> generateInitialCode(UUID sessionId,
            String description,
            String initialCode);

    protected String formatIterationPrompt(String description,
            String currentCode,
            String logcat,
            String feedback, boolean hasImages) {
        Log.d(TAG, "Formatting iteration prompt with description: " + description +
                ", feedback: " + feedback +
                ", hasImages: " + hasImages);
        boolean hasLogcat = logcat != null && !logcat.isEmpty();
        if (hasLogcat) {
            return String.format(
                    "The app needs work. Provide an improved version addressing the feedback%s logcat output%s.\n" +
                            "User feedback: %s\n" +
                            "Logcat output:\n```\n%s\n```\n\n",
                    hasImages ? "," : " and",
                    hasImages ? ", and attached images" : "",
                    feedback,
                    logcat);
        } else {
            return String.format(
                    "The app needs work. Provide an improved version addressing the feedback%s.\n" +
                            "User feedback: %s\n",
                    hasImages ? " and attached images" : "",
                    feedback);
        }
    }

    public abstract CancellableCompletableFuture<AssistantMessage> generateNextIteration(
            UUID sessionId,
            String description,
            String currentCode,
            String logcat,
            String feedback,
            List<File> images);

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

    // Assistant message - can have model provider info
    public static class AssistantMessage extends Message {
        public final LLMClientFactory.LLMType modelProvider;
        public final String modelName;

        public AssistantMessage(String content) {
            super(MessageRole.ASSISTANT, content);
            this.modelProvider = null;
            this.modelName = null;
        }

        public AssistantMessage(String content, LLMClientFactory.LLMType modelProvider, String modelName) {
            super(MessageRole.ASSISTANT, content);
            this.modelProvider = modelProvider;
            this.modelName = modelName;
        }

        /**
         * Get the model provider for this assistant message
         * 
         * @return The model provider, or null if not set
         */
        public LLMClientFactory.LLMType getModelProvider() {
            return modelProvider;
        }

        /**
         * Get the model name for this assistant message
         * 
         * @return The model name, or null if not set
         */
        public String getModelName() {
            return modelName;
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
         * Queues a system prompt message to be sent in the next API call
         *
         * @param content The system prompt content
         */
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Queuing system prompt in session: " + sessionId);
            this.systemPrompt = content;
            messages.add(new SystemPrompt(content));
        }

        /**
         * Queues a user message to be sent in the next API call
         *
         * @param content The user message content
         */
        public void queueUserMessage(String content) {
            queueUserMessageWithImages(content, null);
        }

        /**
         * Queues a user message with additional fields to be sent in the next API call
         *
         * @param content     The user message content
         * @param logcat      The logcat output (can be null)
         * @param feedback    The user feedback (can be null)
         * @param initialCode The initial code (can be null)
         */
        public void queueUserMessage(String content, String logcat, String feedback, String initialCode) {
            queueUserMessageWithImages(content, null, logcat, feedback, initialCode);
        }

        /**
         * Queues a user message with attached images to be sent in the next API call
         *
         * @param content    The user message content
         * @param imageFiles The image files to attach (can be null or empty for
         *                   text-only
         *                   messages)
         */
        public void queueUserMessageWithImages(String content, List<File> imageFiles) {
            queueUserMessageWithImages(content, imageFiles, null, null, null);
        }

        /**
         * Queues a user message with attached images and additional fields to be sent
         * in the next API call
         *
         * @param content     The user message content
         * @param imageFiles  The image files to attach (can be null or empty for
         *                    text-only
         *                    messages)
         * @param logcat      The logcat output (can be null)
         * @param feedback    The user feedback (can be null)
         * @param initialCode The initial code (can be null)
         */
        public void queueUserMessageWithImages(String content, List<File> imageFiles, String logcat, String feedback,
                String initialCode) {
            Log.d(TAG, "Queuing user message with images and fields in session: " + sessionId);

            if (imageFiles != null && !imageFiles.isEmpty()) {
                // Determine MIME types for all images
                List<String> mimeTypes = new ArrayList<>();
                for (File imageFile : imageFiles) {
                    if (imageFile != null) {
                        mimeTypes.add(determineMimeType(imageFile));
                    }
                }

                // Create message with images and fields
                UserMessage message = new UserMessage(content, imageFiles, mimeTypes, logcat, feedback, initialCode);
                messages.add(message);

                Log.d(TAG, "Added message with " + imageFiles.size() + " images and fields");
            } else {
                // Regular text-only message with fields
                messages.add(new UserMessage(content, logcat, feedback, initialCode));
            }
        }

        /**
         * Queues an assistant message object directly to the chat session
         *
         * @param assistantMessage The AssistantMessage object to queue
         */
        public void queueAssistantResponse(AssistantMessage assistantMessage) {
            Log.d(TAG, "Queuing assistant message object in session: " + sessionId +
                    " (provider: " + assistantMessage.getModelProvider() +
                    ", model: " + assistantMessage.getModelName() + ")");
            messages.add(assistantMessage);
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
     * Sends messages from a chat session to the appropriate API
     * This method must be implemented by each LLMClient subclass
     *
     * @param session The chat session containing messages to send
     * @return A CancellableCompletableFuture containing the API response as an
     *         AssistantMessage
     */
    protected abstract CancellableCompletableFuture<AssistantMessage> sendMessages(ChatSession session);

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
