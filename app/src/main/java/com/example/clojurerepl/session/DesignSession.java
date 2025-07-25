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

/**
 * Represents a Clojure app design session.
 * Stores information about the app design, the LLM used, and iterations.
 */
public class DesignSession {
    private static final String TAG = "DesignSession";

    private UUID id;
    private String description;
    private Date createdAt;
    private String initialCode;
    private LLMClientFactory.LLMType llmType;
    private String llmModel;
    private LLMClient.ChatSession chatSession;
    private String lastLogcat;
    private String lastErrorFeedback;
    private boolean hasError;
    private List<List<String>> screenshotSets;

    public DesignSession() {
        this.id = UUID.randomUUID();
        this.createdAt = new Date();
        this.screenshotSets = new ArrayList<>();
        this.hasError = false;
        this.chatSession = new LLMClient.ChatSession(this.id.toString());
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public void setInitialCode(String initialCode) {
        this.initialCode = initialCode;
    }

    public String getInitialCode() {
        return initialCode;
    }

    public List<String> getAllCode() {
        List<String> code = new ArrayList<>();
        for (LLMClient.Message message : chatSession.getMessages()) {
            if (LLMClient.MessageRole.ASSISTANT.equals(message.role)) {
                ClojureIterationManager.CodeExtractionResult extractionResult = ClojureIterationManager
                        .extractClojureCode(message.content);
                if (extractionResult.success) {
                    code.add(extractionResult.code);
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
     * Adds a set of screenshots to the session.
     *
     * @param screenshotSet A list of paths to screenshot files.
     */
    public void addScreenshotSet(List<String> screenshotSet) {
        if (this.screenshotSets == null) {
            this.screenshotSets = new ArrayList<>();
        }
        this.screenshotSets.add(new ArrayList<>(screenshotSet));
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
     * Converts this session to a JSONObject
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id.toString());
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

            // Add model information for assistant messages
            if (message.role == LLMClient.MessageRole.ASSISTANT && message.getModelProvider() != null
                    && message.getModelName() != null) {
                messageJson.put("modelProvider", message.getModelProvider().name());
                messageJson.put("modelName", message.getModelName());
            }

            // Add image file path and MIME type if the message has an image
            if (message.hasImage()) {
                messageJson.put("imageFile", message.imageFile.getPath());
                messageJson.put("mimeType", message.mimeType);
            }

            messagesJson.put(messageJson);
        }
        json.put("chatHistory", messagesJson);

        // Add new fields
        if (lastLogcat != null) {
            json.put("lastLogcat", lastLogcat);
        }

        // Add screenshot sets to JSON
        if (screenshotSets != null && !screenshotSets.isEmpty()) {
            JSONArray setsJson = new JSONArray();
            for (List<String> set : screenshotSets) {
                JSONArray setJson = new JSONArray();
                for (String path : set) {
                    setJson.put(path);
                }
                setsJson.put(setJson);
            }
            json.put("screenshotSets", setsJson);
        }

        if (lastErrorFeedback != null) {
            json.put("lastErrorFeedback", lastErrorFeedback);
        }

        json.put("hasError", hasError);

        return json;
    }

    /**
     * Creates a DesignSession from a JSONObject
     */
    public static DesignSession fromJson(JSONObject json) throws JSONException {
        DesignSession session = new DesignSession();
        session.id = UUID.fromString(json.getString("id"));
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

                // Get model information for assistant messages
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

                // Check if the message has an image
                if (messageJson.has("imageFile") && messageJson.has("mimeType")) {
                    String imagePath = messageJson.getString("imageFile");
                    String mimeType = messageJson.getString("mimeType");
                    File imageFile = new File(imagePath);

                    // Only create message with image if the file exists
                    if (imageFile.exists()) {
                        if (modelProvider != null && modelName != null) {
                            chatHistory.add(new LLMClient.Message(role, content, imageFile, mimeType, modelProvider,
                                    modelName));
                        } else {
                            chatHistory.add(new LLMClient.Message(role, content, imageFile, mimeType));
                        }
                    } else {
                        // File doesn't exist, create regular message
                        Log.w(TAG, "Image file not found during deserialization: " + imagePath);
                        if (modelProvider != null && modelName != null) {
                            chatHistory.add(new LLMClient.Message(role, content, modelProvider, modelName));
                        } else {
                            chatHistory.add(new LLMClient.Message(role, content));
                        }
                    }
                } else {
                    // Regular message without image
                    if (modelProvider != null && modelName != null) {
                        chatHistory.add(new LLMClient.Message(role, content, modelProvider, modelName));
                    } else {
                        chatHistory.add(new LLMClient.Message(role, content));
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
                session.chatSession.queueSystemPrompt(message.content);
            } else if (LLMClient.MessageRole.USER.equals(message.role)) {
                if (message.hasImage()) {
                    session.chatSession.queueUserMessageWithImage(message.content, message.imageFile);
                } else {
                    session.chatSession.queueUserMessage(message.content);
                }
            } else if (LLMClient.MessageRole.ASSISTANT.equals(message.role)) {
                // Use the model information if available, otherwise use the default method
                if (message.getModelProvider() != null && message.getModelName() != null) {
                    session.chatSession.queueAssistantResponse(message.content, message.getModelProvider(),
                            message.getModelName());
                } else {
                    session.chatSession.queueAssistantResponse(message.content);
                }
                ClojureIterationManager.CodeExtractionResult extractionResult = ClojureIterationManager
                        .extractClojureCode(message.content);
                if (extractionResult.success) {
                    lastCode = extractionResult.code;
                }
            } else {
                throw new JSONException("Unknown message type " + message.role);
            }
        }

        // Load new fields
        if (json.has("lastLogcat")) {
            session.lastLogcat = json.getString("lastLogcat");
        }

        // Load screenshot sets
        session.screenshotSets = new ArrayList<>();
        if (json.has("screenshotSets")) {
            JSONArray setsJson = json.getJSONArray("screenshotSets");
            for (int i = 0; i < setsJson.length(); i++) {
                JSONArray setJson = setsJson.getJSONArray(i);
                List<String> set = new ArrayList<>();
                for (int j = 0; j < setJson.length(); j++) {
                    set.add(setJson.getString(j));
                }
                session.screenshotSets.add(set);
            }
        }

        if (json.has("lastErrorFeedback")) {
            session.lastErrorFeedback = json.getString("lastErrorFeedback");
        }

        if (json.has("hasError")) {
            session.hasError = json.getBoolean("hasError");
        }

        return session;
    }

    /**
     * Returns a short summary of the session for display
     */
    public String getDisplaySummary() {
        return description.length() > 50
                ? description.substring(0, 47) + "..."
                : description;
    }
}
