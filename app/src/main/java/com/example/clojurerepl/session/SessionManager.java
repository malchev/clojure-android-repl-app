package com.example.clojurerepl.session;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages design sessions by providing methods to save, load, and delete
 * sessions.
 */
public class SessionManager {
    private static final String TAG = "ClojureSessionManager";
    private static final String SESSIONS_DIR = "sessions";
    private static final String SESSIONS_FILE = "design_sessions.json";

    private static SessionManager instance;
    private Context context;
    private List<DesignSession> sessions;

    private SessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessions = new ArrayList<>();
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    /**
     * Returns all saved sessions
     */
    public List<DesignSession> getAllSessions() {
        return new ArrayList<>(sessions);
    }

    /**
     * Gets a session by ID
     */
    public DesignSession getSessionById(String sessionId) {
        for (DesignSession session : sessions) {
            if (session.getId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Validates a session is ready to be serialized
     */
    private boolean isValidSession(DesignSession session) {
        if (session == null) {
            Log.w(TAG, "Attempted to save null session");
            return false;
        }

        if (session.getId() == null) {
            Log.w(TAG, "Session has null ID");
            return false;
        }

        if (session.getDescription() == null || session.getDescription().isEmpty()) {
            Log.w(TAG, "Session has null or empty description");
            return false;
        }

        return true;
    }

    /**
     * Adds a new session and saves it to storage.
     * If a session with the same ID already exists, it will be updated instead.
     */
    public void addSession(DesignSession session) {
        if (!isValidSession(session)) {
            Log.w(TAG, "Not adding invalid session");
            return;
        }

        // Check if session with same ID already exists
        boolean sessionExists = false;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId().equals(session.getId())) {
                // Update existing session instead of adding a duplicate
                Log.d(TAG, "Session with ID " + session.getId() + " already exists, updating instead of adding");
                sessions.set(i, session);
                sessionExists = true;
                break;
            }
        }

        // Only add if it's a new session
        if (!sessionExists) {
            Log.d(TAG, "Adding new session with ID: " + session.getId());
            sessions.add(session);
        }

        saveSessions();
    }

    /**
     * Updates an existing session and saves it to storage
     */
    public void updateSession(DesignSession updatedSession) {
        if (!isValidSession(updatedSession)) {
            Log.w(TAG, "Not updating invalid session");
            return;
        }

        boolean sessionFound = false;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId().equals(updatedSession.getId())) {
                sessions.set(i, updatedSession);
                sessionFound = true;
                Log.d(TAG, "Updated session with ID: " + updatedSession.getId());
                break;
            }
        }

        if (!sessionFound) {
            Log.w(TAG, "Session with ID " + updatedSession.getId() + " not found, adding as new session");
            sessions.add(updatedSession);
        }

        saveSessions();
    }

    /**
     * Deletes a session
     */
    public void deleteSession(String sessionId) {
        // First, find the session to delete
        DesignSession sessionToDelete = null;
        for (DesignSession session : sessions) {
            if (session.getId().equals(sessionId)) {
                sessionToDelete = session;
                break;
            }
        }

        // If we found the session, delete all associated screenshot files
        if (sessionToDelete != null) {
            // Delete screenshot files from all sets
            List<List<String>> screenshotSets = sessionToDelete.getScreenshotSets();
            if (screenshotSets != null) {
                int filesDeleted = 0;
                for (List<String> set : screenshotSets) {
                    for (String path : set) {
                        File screenshotFile = new File(path);
                        if (screenshotFile.exists()) {
                            boolean deleted = screenshotFile.delete();
                            if (deleted) {
                                filesDeleted++;
                                Log.d(TAG, "Deleted screenshot file: " + path);
                            } else {
                                Log.w(TAG, "Failed to delete screenshot file: " + path);
                            }
                        }
                    }
                }
                Log.d(TAG, "Deleted " + filesDeleted + " screenshot files for session: " + sessionId);
            }

            // Remove the session directly since we already have the reference
            boolean removed = sessions.remove(sessionToDelete);
            if (removed) {
                Log.d(TAG, "Deleted session with ID: " + sessionId);
            } else {
                Log.w(TAG, "Failed to remove session with ID: " + sessionId);
            }
        } else {
            Log.w(TAG, "Session with ID " + sessionId + " not found for deletion");
        }

        saveSessions();
    }

    /**
     * Loads sessions from storage
     */
    private void loadSessions() {
        File sessionsFile = getSessionsFile();

        if (!sessionsFile.exists()) {
            Log.d(TAG, "Sessions file does not exist yet: " + sessionsFile.getAbsolutePath());
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(sessionsFile))) {
            StringBuilder jsonString = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonString.append(line);
            }

            JSONArray jsonArray = new JSONArray(jsonString.toString());
            sessions.clear();

            Log.d(TAG, "Loading " + jsonArray.length() + " sessions from file");

            int successCount = 0;
            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    JSONObject sessionJson = jsonArray.getJSONObject(i);
                    DesignSession session = DesignSession.fromJson(sessionJson);
                    sessions.add(session);
                    successCount++;
                    Log.v(TAG, "Loaded session ID: " + session.getId() + ", Description: " +
                            (session.getDescription() != null ? session.getDescription().substring(0,
                                    Math.min(30, session.getDescription().length())) + "..." : "null"));
                } catch (Exception e) {
                    Log.e(TAG, "Error loading individual session at index " + i, e);
                }
            }

            Log.d(TAG, "Successfully loaded " + successCount + " out of " + jsonArray.length() + " sessions");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error loading sessions from " + sessionsFile.getAbsolutePath(), e);
        }
    }

    /**
     * Saves sessions to storage
     */
    private void saveSessions() {
        File sessionsFile = getSessionsFile();

        try {
            // Ensure directory exists
            File parent = sessionsFile.getParentFile();
            if (!parent.exists()) {
                if (!parent.mkdirs()) {
                    Log.e(TAG, "Could not create sessions directory: " + parent.getAbsolutePath());
                    return;
                }
            }

            JSONArray jsonArray = new JSONArray();
            int sessionCount = 0;
            for (DesignSession session : sessions) {
                try {
                    jsonArray.put(session.toJson());
                    sessionCount++;
                } catch (JSONException e) {
                    Log.e(TAG, "Error converting session to JSON: " + session.getId(), e);
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(sessionsFile))) {
                writer.write(jsonArray.toString());
            }

            Log.d(TAG, "Saved " + sessionCount + " sessions to " + sessionsFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving sessions to " + sessionsFile.getAbsolutePath(), e);
        }
    }

    /**
     * Gets the file where sessions are stored
     */
    private File getSessionsFile() {
        File dir = new File(context.getFilesDir(), SESSIONS_DIR);
        return new File(dir, SESSIONS_FILE);
    }

    /**
     * Asynchronously loads sessions in the background
     */
    public CompletableFuture<List<DesignSession>> loadSessionsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            loadSessions();
            return getAllSessions();
        });
    }
}
