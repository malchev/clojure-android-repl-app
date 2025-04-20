package com.example.clojurerepl.session;

import android.content.Context;
import android.util.Log;

import com.example.clojurerepl.LLMClient;
import com.example.clojurerepl.LLMClientFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
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

    private String id;
    private String description;
    private Date createdAt;
    private Date updatedAt;
    private String currentCode;
    private int iterationCount;
    private LLMClientFactory.LLMType llmType;
    private String llmModel;
    private List<LLMClient.Message> chatHistory;
    private String lastLogcat;
    private List<String> screenshotPaths;
    private String lastErrorFeedback;
    private boolean hasError;

    public DesignSession() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = new Date();
        this.updatedAt = new Date();
        this.iterationCount = 0;
        this.chatHistory = new ArrayList<>();
        this.screenshotPaths = new ArrayList<>();
        this.hasError = false;
    }

    public DesignSession(String description, LLMClientFactory.LLMType llmType, String llmModel) {
        this();
        this.description = description;
        this.llmType = llmType;
        this.llmModel = llmModel;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.updatedAt = new Date();
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCurrentCode() {
        return currentCode;
    }

    public void setCurrentCode(String currentCode) {
        this.currentCode = currentCode;
        this.updatedAt = new Date();
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public void setIterationCount(int iterationCount) {
        this.iterationCount = iterationCount;
    }

    public void incrementIterationCount() {
        this.iterationCount++;
        this.updatedAt = new Date();
    }

    public LLMClientFactory.LLMType getLlmType() {
        return llmType;
    }

    public void setLlmType(LLMClientFactory.LLMType llmType) {
        this.llmType = llmType;
        this.updatedAt = new Date();
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
        this.updatedAt = new Date();
    }

    public List<LLMClient.Message> getChatHistory() {
        return chatHistory;
    }

    public void setChatHistory(List<LLMClient.Message> chatHistory) {
        this.chatHistory = chatHistory;
        this.updatedAt = new Date();
    }

    public void addMessage(LLMClient.Message message) {
        this.chatHistory.add(message);
        this.updatedAt = new Date();
    }
    
    public String getLastLogcat() {
        return lastLogcat;
    }
    
    public void setLastLogcat(String lastLogcat) {
        this.lastLogcat = lastLogcat;
        this.updatedAt = new Date();
    }
    
    public List<String> getScreenshotPaths() {
        return screenshotPaths;
    }
    
    public void setScreenshotPaths(List<String> screenshotPaths) {
        this.screenshotPaths = screenshotPaths;
        this.updatedAt = new Date();
    }
    
    public void addScreenshotPath(String path) {
        if (this.screenshotPaths == null) {
            this.screenshotPaths = new ArrayList<>();
        }
        this.screenshotPaths.add(path);
        this.updatedAt = new Date();
    }
    
    public String getLastErrorFeedback() {
        return lastErrorFeedback;
    }
    
    public void setLastErrorFeedback(String lastErrorFeedback) {
        this.lastErrorFeedback = lastErrorFeedback;
        this.updatedAt = new Date();
    }
    
    public boolean hasError() {
        return hasError;
    }
    
    public void setHasError(boolean hasError) {
        this.hasError = hasError;
        this.updatedAt = new Date();
    }

    /**
     * Converts this session to a JSONObject
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("description", description);
        json.put("createdAt", createdAt.getTime());
        json.put("updatedAt", updatedAt.getTime());
        json.put("currentCode", currentCode);
        json.put("iterationCount", iterationCount);
        json.put("llmType", llmType.name());
        json.put("llmModel", llmModel);

        JSONArray messagesJson = new JSONArray();
        for (LLMClient.Message message : chatHistory) {
            JSONObject messageJson = new JSONObject();
            messageJson.put("role", message.role);
            messageJson.put("content", message.content);
            messagesJson.put(messageJson);
        }
        json.put("chatHistory", messagesJson);
        
        // Add new fields
        if (lastLogcat != null) {
            json.put("lastLogcat", lastLogcat);
        }
        
        if (screenshotPaths != null && !screenshotPaths.isEmpty()) {
            JSONArray screenshotsJson = new JSONArray();
            for (String path : screenshotPaths) {
                screenshotsJson.put(path);
            }
            json.put("screenshotPaths", screenshotsJson);
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
        session.id = json.getString("id");
        session.description = json.getString("description");
        session.createdAt = new Date(json.getLong("createdAt"));
        session.updatedAt = new Date(json.getLong("updatedAt"));
        session.currentCode = json.getString("currentCode");
        session.iterationCount = json.getInt("iterationCount");
        session.llmType = LLMClientFactory.LLMType.valueOf(json.getString("llmType"));
        session.llmModel = json.getString("llmModel");

        session.chatHistory = new ArrayList<>();
        if (json.has("chatHistory")) {
            JSONArray messagesJson = json.getJSONArray("chatHistory");
            for (int i = 0; i < messagesJson.length(); i++) {
                JSONObject messageJson = messagesJson.getJSONObject(i);
                String role = messageJson.getString("role");
                String content = messageJson.getString("content");
                session.chatHistory.add(new LLMClient.Message(role, content));
            }
        } else if (json.has("messageHistory")) {
            // Handle legacy format for backward compatibility
            JSONArray messagesJson = json.getJSONArray("messageHistory");
            for (int i = 0; i < messagesJson.length(); i++) {
                String messageStr = messagesJson.getString(i);
                String[] parts = messageStr.split(":", 2);
                if (parts.length == 2) {
                    session.chatHistory.add(new LLMClient.Message(parts[0], parts[1]));
                }
            }
        }
        
        // Load new fields
        if (json.has("lastLogcat")) {
            session.lastLogcat = json.getString("lastLogcat");
        }
        
        session.screenshotPaths = new ArrayList<>();
        if (json.has("screenshotPaths")) {
            JSONArray screenshotsJson = json.getJSONArray("screenshotPaths");
            for (int i = 0; i < screenshotsJson.length(); i++) {
                session.screenshotPaths.add(screenshotsJson.getString(i));
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