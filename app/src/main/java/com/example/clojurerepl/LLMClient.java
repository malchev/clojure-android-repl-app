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
import java.util.List;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayList;

public abstract class LLMClient {
    private static final String TAG = "LLMClient";
    private static final String PROMPT_TEMPLATE_PATH = "prompt.txt";

    protected final Context context;
    private String promptTemplate;
    public final ChatSession chatSession;

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
        DEVELOPER("developer");

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

    public ChatSession preparePromptForInitialCode(UUID sessionId, String description) {
        return preparePromptForInitialCode(sessionId, description, null);
    }

    /**
     * Prepares a prompt for initial code generation with optional starting code
     * 
     * @param sessionId   The session UUID
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return The prepared ChatSession
     */
    public ChatSession preparePromptForInitialCode(UUID sessionId, String description, String initialCode) {
        // Reset session to start fresh
        chatSession.reset();

        // Make sure we have a system message at the beginning
        chatSession.queueSystemPrompt(getSystemPrompt());

        // Format the prompt using the helper from LLMClient with initial code
        String prompt = formatInitialPrompt(description, initialCode);

        // Queue the user message and send all messages
        chatSession.queueUserMessage(prompt);
        return chatSession;
    }

    public abstract CompletableFuture<String> generateInitialCode(UUID sessionId, String description);

    /**
     * Generate initial code with optional starting code
     * 
     * @param sessionId   The session UUID
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return A CompletableFuture containing the generated code
     */
    public abstract CompletableFuture<String> generateInitialCode(UUID sessionId, String description,
            String initialCode);

    protected String formatIterationPrompt(String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback, boolean hasImage) {
        Log.d(TAG, "Formatting iteration prompt with description: " + description +
                ", screenshot: " + (screenshot != null ? screenshot.getPath() : "null") +
                ", feedback: " + feedback +
                ", hasImage: " + hasImage);
        return String.format(
                "The app needs work. Provide an improved version addressing the user feedback%s logcat output%s.\n" +
                        "User feedback: %s\n" +
                        "Logcat output:\n```\n%s\n```\n\n",
                hasImage ? "," : " and",
                hasImage ? ", and attached image" : "",
                feedback,
                logcat);
    }

    public abstract CompletableFuture<String> generateNextIteration(
            UUID sessionId,
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback,
            File image);

    // Message class for chat history (supports both text and images)
    public static class Message {
        public final MessageRole role;
        public final String content;
        public final File imageFile;
        public final String mimeType;

        public Message(MessageRole role, String content) {
            this.role = role;
            this.content = content;
            this.imageFile = null;
            this.mimeType = null;
        }

        public Message(MessageRole role, String content, File imageFile, String mimeType) {
            this.role = role;
            this.content = content;
            this.imageFile = imageFile;
            this.mimeType = mimeType;
        }

        public boolean hasImage() {
            return imageFile != null && imageFile.exists() && imageFile.canRead();
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
            messages.add(new Message(MessageRole.SYSTEM, content));
        }

        /**
         * Queues a user message to be sent in the next API call
         *
         * @param content The user message content
         */
        public void queueUserMessage(String content) {
            queueUserMessageWithImage(content, null);
        }

        /**
         * Queues a user message with an attached image to be sent in the next API call
         *
         * @param content   The user message content
         * @param imageFile The image file to attach (can be null for text-only
         *                  messages)
         */
        public void queueUserMessageWithImage(String content, File imageFile) {
            Log.d(TAG, "Queuing user message with image in session: " + sessionId);

            if (imageFile != null) {
                // Determine MIME type based on file extension
                String mimeType = determineMimeType(imageFile);

                // Create message with image
                Message message = new Message(MessageRole.USER, content, imageFile, mimeType);
                messages.add(message);

                Log.d(TAG, "Added message with image, MIME type: " + mimeType);
            } else {
                // Regular text-only message
                messages.add(new Message(MessageRole.USER, content));
            }
        }

        /**
         * Queues an assistant (model) response to be sent in the next API call
         * Typically used when loading previous conversations
         *
         * @param content The assistant response content
         */
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in session: " + sessionId);
            messages.add(new Message(MessageRole.ASSISTANT, content));
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
    }

    /**
     * Sends messages from a chat session to the appropriate API
     * This method must be implemented by each LLMClient subclass
     *
     * @param session The chat session containing messages to send
     * @return A CompletableFuture containing the API response
     */
    protected abstract CompletableFuture<String> sendMessages(ChatSession session);

    /**
     * Clears the API key for this client type
     *
     * @return true if key was successfully cleared, false otherwise
     */
    public abstract boolean clearApiKey();

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
