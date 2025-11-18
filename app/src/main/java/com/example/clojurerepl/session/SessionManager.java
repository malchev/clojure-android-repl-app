package com.example.clojurerepl.session;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

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
     * Returns all saved sessions, sorted by createdAt (newest to oldest)
     */
    public List<DesignSession> getAllSessions() {
        List<DesignSession> sortedSessions = new ArrayList<>(sessions);
        Collections.sort(sortedSessions, new Comparator<DesignSession>() {
            @Override
            public int compare(DesignSession s1, DesignSession s2) {
                // Sort by createdAt descending (newest first)
                if (s1.getCreatedAt() == null && s2.getCreatedAt() == null) {
                    return 0;
                }
                if (s1.getCreatedAt() == null) {
                    return 1;
                }
                if (s2.getCreatedAt() == null) {
                    return -1;
                }
                return s2.getCreatedAt().compareTo(s1.getCreatedAt());
            }
        });
        return sortedSessions;
    }

    /**
     * Gets a session by ID. If not found in memory, attempts to load from disk.
     */
    public DesignSession getSessionById(UUID sessionId) {
        // First check in-memory list
        for (DesignSession session : sessions) {
            if (session.getId().equals(sessionId)) {
                return session;
            }
        }

        // Not found in memory, try loading from disk
        File sessionFile = getSessionFile(sessionId);
        if (sessionFile.exists()) {
            try {
                try (BufferedReader reader = new BufferedReader(new FileReader(sessionFile))) {
                    StringBuilder jsonString = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonString.append(line);
                    }

                    JSONObject sessionJson = new JSONObject(jsonString.toString());
                    DesignSession session = DesignSession.fromJson(sessionJson, context);

                    // Add to in-memory list if not already present (prevent duplicates)
                    boolean alreadyExists = false;
                    for (DesignSession existingSession : sessions) {
                        if (existingSession.getId().equals(sessionId)) {
                            alreadyExists = true;
                            break;
                        }
                    }
                    if (!alreadyExists) {
                        sessions.add(session);
                        Log.d(TAG, "Loaded session " + sessionId + " from disk");
                    }

                    return session;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading session from disk: " + sessionFile.getName(), e);
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

        saveSession(session);
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

        saveSession(updatedSession);
    }

    /**
     * Deletes a session
     */
    public void deleteSession(UUID sessionId) {
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

            // Delete the session file
            File sessionFile = getSessionFile(sessionId);
            if (sessionFile.exists()) {
                boolean fileDeleted = sessionFile.delete();
                if (fileDeleted) {
                    Log.d(TAG, "Deleted session file: " + sessionFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Failed to delete session file: " + sessionFile.getAbsolutePath());
                }
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
    }

    /**
     * Loads sessions from storage.
     * First checks for old format (design_sessions.json) and migrates if found.
     * Then loads all individual session files from the sessions directory.
     */
    private void loadSessions() {
        File sessionsDir = getSessionsDirectory();

        // Ensure directory exists
        if (!sessionsDir.exists()) {
            if (!sessionsDir.mkdirs()) {
                Log.e(TAG, "Could not create sessions directory: " + sessionsDir.getAbsolutePath());
                return;
            }
        }

        // Check for old format file and migrate if it exists
        File oldSessionsFile = new File(sessionsDir, SESSIONS_FILE);
        if (oldSessionsFile.exists()) {
            Log.d(TAG, "Found old format file, migrating to new format...");
            migrateOldFormat(oldSessionsFile);
        }

        sessions.clear();

        // Load all individual session files
        File[] sessionFiles = sessionsDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                // Accept only JSON files that look like UUIDs (36 characters + .json extension)
                String name = file.getName();
                return name.endsWith(".json") && name.length() == 41 &&
                       name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json");
            }
        });

        if (sessionFiles == null) {
            Log.d(TAG, "No session files found in directory: " + sessionsDir.getAbsolutePath());
            return;
        }

        Log.d(TAG, "Loading " + sessionFiles.length + " session files from directory");

        int successCount = 0;
        for (File sessionFile : sessionFiles) {
            try {
                try (BufferedReader reader = new BufferedReader(new FileReader(sessionFile))) {
                    StringBuilder jsonString = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonString.append(line);
                    }

                    JSONObject sessionJson = new JSONObject(jsonString.toString());
                    DesignSession session = DesignSession.fromJson(sessionJson, context);

                    // Check for duplicates before adding (shouldn't happen after clear, but be safe)
                    boolean alreadyExists = false;
                    for (DesignSession existingSession : sessions) {
                        if (existingSession.getId().equals(session.getId())) {
                            alreadyExists = true;
                            Log.w(TAG, "Duplicate session found when loading: " + session.getId() + ", skipping");
                            break;
                        }
                    }

                    if (!alreadyExists) {
                        sessions.add(session);
                        successCount++;
                        Log.v(TAG, "Loaded session ID: " + session.getId() + ", Description: " +
                                (session.getDescription() != null ? session.getDescription().substring(0,
                                        Math.min(30, session.getDescription().length())) + "..." : "null"));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading session file: " + sessionFile.getName(), e);
            }
        }

        Log.d(TAG, "Successfully loaded " + successCount + " out of " + sessionFiles.length + " session files");
    }

    /**
     * Saves a single session to its own file
     */
    private void saveSession(DesignSession session) {
        if (!isValidSession(session)) {
            Log.w(TAG, "Not saving invalid session");
            return;
        }

        File sessionFile = getSessionFile(session.getId());
        File sessionsDir = getSessionsDirectory();

        try {
            // Ensure directory exists
            if (!sessionsDir.exists()) {
                if (!sessionsDir.mkdirs()) {
                    Log.e(TAG, "Could not create sessions directory: " + sessionsDir.getAbsolutePath());
                    return;
                }
            }

            JSONObject sessionJson = session.toJson();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(sessionFile))) {
                writer.write(sessionJson.toString());
            }

            Log.d(TAG, "Saved session " + session.getId() + " to " + sessionFile.getAbsolutePath());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error saving session to " + sessionFile.getAbsolutePath(), e);
        }
    }

    /**
     * Migrates from old format (single design_sessions.json file) to new format (individual files)
     */
    private void migrateOldFormat(File oldSessionsFile) {
        try {
            Log.d(TAG, "Starting migration from old format: " + oldSessionsFile.getAbsolutePath());

            try (BufferedReader reader = new BufferedReader(new FileReader(oldSessionsFile))) {
                StringBuilder jsonString = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonString.append(line);
                }

                JSONArray jsonArray = new JSONArray(jsonString.toString());
                int migratedCount = 0;
                int failedCount = 0;

                for (int i = 0; i < jsonArray.length(); i++) {
                    try {
                        JSONObject sessionJson = jsonArray.getJSONObject(i);
                        DesignSession session = DesignSession.fromJson(sessionJson, context);

                        // Save to individual file
                        File sessionFile = getSessionFile(session.getId());
                        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sessionFile))) {
                            writer.write(sessionJson.toString());
                        }

                        migratedCount++;
                        Log.d(TAG, "Migrated session " + session.getId() + " to individual file");
                    } catch (Exception e) {
                        Log.e(TAG, "Error migrating session at index " + i, e);
                        failedCount++;
                    }
                }

                Log.d(TAG, "Migration complete: " + migratedCount + " sessions migrated, " + failedCount + " failed");

                // Delete the old file after successful migration
                if (oldSessionsFile.delete()) {
                    Log.d(TAG, "Deleted old format file: " + oldSessionsFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Failed to delete old format file: " + oldSessionsFile.getAbsolutePath());
                }
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error migrating from old format: " + oldSessionsFile.getAbsolutePath(), e);
        }
    }

    /**
     * Gets the directory where sessions are stored
     */
    private File getSessionsDirectory() {
        return new File(context.getFilesDir(), SESSIONS_DIR);
    }

    /**
     * Gets the file for a specific session (by UUID)
     */
    private File getSessionFile(UUID sessionId) {
        File sessionsDir = getSessionsDirectory();
        return new File(sessionsDir, sessionId.toString() + ".json");
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
