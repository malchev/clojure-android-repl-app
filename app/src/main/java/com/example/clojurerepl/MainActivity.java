package com.example.clojurerepl;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.multidex.MultiDex;
import android.util.Log;
import android.util.Base64;
import java.lang.reflect.Field;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import dalvik.system.DexFile;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import android.graphics.Typeface;
import android.view.Gravity;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.widget.HorizontalScrollView;
import android.content.SharedPreferences;
import android.view.ViewParent;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private static final String PREFS_NAME = "ClojureReplPrefs";
    private static final String KEY_SAVED_CODE = "saved_code";
    private static final String KEY_SAVED_TIMINGS = "saved_timings";
    private static final String TIMING_RUN_SEPARATOR = "###RUN###";
    private static final String TIMING_LINE_SEPARATOR = "\n";

    private List<String> timingRuns = new ArrayList<>();
    private StringBuilder timingData = new StringBuilder();

    private TextView replInput;
    private TextView replOutput;
    private TextView statsView;
    private LinearLayout timingsLayout;
    private TextView timingsHeaderView;
    private LinearLayout timingsTableView;
    private int runCount = 0;
    private LinearLayout[] stageRows; // Array to keep track of stage label columns
    private LinearLayout[] dataRows; // Array to keep track of data columns
    private Map<String, ClojureProgram> programs = new HashMap<>();
    private ClojureProgram currentProgram;
    private Spinner programSpinner;
    private ArrayAdapter<String> spinnerAdapter;
    private static final String KEY_PROGRAMS = "saved_programs";
    private static final String KEY_CURRENT_PROGRAM = "current_program";
    private BytecodeCache bytecodeCache;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        replInput = findViewById(R.id.repl_input);
        replOutput = findViewById(R.id.repl_output);

        // Configure replInput for scrolling
        replInput.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        replInput.setHorizontallyScrolling(true);
        replInput.setHorizontalScrollBarEnabled(true);
        replInput.setVerticalScrollBarEnabled(true);
        replInput.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // Create and add stats view
        statsView = new TextView(this);
        statsView.setTextSize(14);
        statsView.setPadding(16, 16, 16, 16);
        statsView.setBackgroundColor(Color.parseColor("#F5F5F5"));
        statsView.setTextColor(Color.parseColor("#263238"));
        statsView.setTypeface(Typeface.MONOSPACE);

        setupTimingsUI();
        setupProgramSpinner();

        // Now restore saved state after UI is initialized
        restoreSavedState();

        // Set up launch button
        Button launchButton = findViewById(R.id.launch_button);
        launchButton.setOnClickListener(v -> launchRenderActivity());

        updateStats("Initializing...", null, null);

        try {
            long startTime = System.currentTimeMillis();

            // Only handle intent if it's not the initial launch
            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("code")) {
                handleIntent(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Clojure", e);
            replOutput.setText("Error initializing Clojure: " + e.getMessage());
            updateStats("Error", null, null);
        }

        // Add this to onCreate in MainActivity
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception", throwable);
            // You can also send this to a crash reporting service
        });
    }

    private void updateStats(String status, Integer codeLines, Long timeMs) {
        runOnUiThread(() -> {
            StringBuilder stats = new StringBuilder();
            stats.append("Status: ").append(status).append("\n");

            if (codeLines != null) {
                stats.append("Code size: ").append(codeLines).append(" lines\n");
            }

            // Get cache stats if we have a current program
            if (currentProgram != null) {
                String code = currentProgram.getCode();
                String codeHash = RenderActivity.getCodeHash(code);
                BytecodeCache cache = BytecodeCache.getInstance(this, codeHash);

                int classCount = cache.getClassCount();
                stats.append("Classes in cache: ").append(classCount).append("\n");

                long cacheSize = cache.getCacheSize();
                stats.append("Cache size: ");
                if (cacheSize < 1024) {
                    stats.append(cacheSize).append(" bytes");
                } else if (cacheSize < 1024 * 1024) {
                    stats.append(String.format("%.1f KB", cacheSize / 1024.0));
                } else {
                    stats.append(String.format("%.1f MB", cacheSize / (1024.0 * 1024.0)));
                }
                stats.append("\n");
            }

            if (timeMs != null) {
                stats.append("Time: ");
                if (timeMs < 1000) {
                    stats.append(timeMs).append("ms");
                } else {
                    stats.append(String.format("%.1fs", timeMs / 1000.0));
                }
            }

            SpannableString spannableStats = new SpannableString(stats.toString());

            // Style the labels
            String text = stats.toString();
            styleLabel(spannableStats, text, "Status:");
            styleLabel(spannableStats, text, "Code size:");
            styleLabel(spannableStats, text, "Classes in cache:");
            styleLabel(spannableStats, text, "Cache size:");
            styleLabel(spannableStats, text, "Time:");

            statsView.setText(spannableStats);
        });
    }

    private void styleLabel(SpannableString spannableString, String fullText, String label) {
        int start = fullText.indexOf(label);
        if (start >= 0) {
            spannableString.setSpan(new StyleSpan(Typeface.BOLD), start, start + label.length(), 0);
            spannableString.setSpan(new ForegroundColorSpan(Color.parseColor("#1976D2")), start, start + label.length(),
                    0); // Darker blue
        }
    }

    private void launchRenderActivity() {
        try {
            String code = replInput.getText().toString();
            if (code.trim().isEmpty()) {
                replOutput.setText("Please enter some code first");
                updateStats("No code", 0, 0L);
                return;
            }

            if (code.length() > 500000) {
                Log.e(TAG, "Code too large for intent: " + code.length() + " bytes");
                replOutput.setText("Error: Code too large for intent");
                return;
            }

            final int lineCount = code.split("\n").length;
            final long startTime = System.currentTimeMillis();

            updateStats("Compiling...", lineCount, null);

            try {
                Intent renderIntent = new Intent(MainActivity.this, RenderActivity.class);
                Log.d(TAG, "Creating intent for RenderActivity with code length: " + code.length());
                renderIntent.putExtra("code", code);
                Log.d(TAG, "Starting RenderActivity...");
                startActivityForResult(renderIntent, 1001); // Use request code 1001 for render activity
                Log.d(TAG, "RenderActivity started");
                replOutput.setText("Launching render activity...");

                // Update stats with final timing
                long totalTime = System.currentTimeMillis() - startTime;
                updateStats("Compiled successfully", lineCount, totalTime);
            } catch (Exception e) {
                Log.e(TAG, "Error launching RenderActivity", e);
                replOutput.setText("Error: " + e.getMessage());
                updateStats("Compilation error", lineCount, null);
            }
        } catch (Throwable t) {
            // Catch absolutely everything
            Log.e(TAG, "Critical error in launchRenderActivity", t);
            Toast.makeText(this, "Critical error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            String timings = data.getStringExtra("timings");
            if (timings != null && currentProgram != null) {
                runCount++;
                currentProgram.addTimingRun(timings);
                updateTimingsTable(timings);
                saveState();

                // Update stats to show current cache info after compilation
                String code = currentProgram.getCode();
                int lineCount = code.split("\n").length;
                updateStats("Compilation complete", lineCount, null);
            }
        }
    }

    private void updateTimingsTable(String timingsData) {
        Log.d(TAG, "Updating timings table with data: " + timingsData);

        if (timingsData == null || timingsData.trim().isEmpty()) {
            Log.w(TAG, "Empty timing data received");
            return;
        }

        // Add to timing runs list if not already there
        if (!timingRuns.contains(timingsData)) {
            timingRuns.add(timingsData);
            runCount = timingRuns.size();
        }

        // If table hasn't been created yet, build it from scratch
        if (stageRows == null || stageRows.length == 0) {
            rebuildTimingsTable();
            return;
        }

        // Update the existing table
        String[] lines = timingsData.split("\n");

        // First ensure the headers are updated for the new run count
        if (dataRows[0].getChildCount() < runCount) {
            // Add a new run header
            TextView runHeader = new TextView(this);
            runHeader.setText("Run " + runCount);
            runHeader.setTypeface(Typeface.DEFAULT_BOLD);
            runHeader.setTextColor(Color.parseColor("#1976D2"));
            runHeader.setTextSize(12); // Match RenderActivity font size
            runHeader.setLayoutParams(new LinearLayout.LayoutParams(
                    150, LinearLayout.LayoutParams.WRAP_CONTENT));
            runHeader.setPadding(8, 8, 8, 8);
            dataRows[0].addView(runHeader);
        }

        // Update timings header text
        if (timingsHeaderView != null) {
            timingsHeaderView.setText("Execution Timings");
        }

        // Loop through each line of timing data
        for (String line : lines) {
            if (line.contains(":")) {
                String[] parts = line.split(":");
                if (parts.length >= 2) {
                    String stageName = parts[0].trim();
                    String timeValue = parts[1].trim();

                    // Find or create row for this stage
                    int rowIndex = -1;
                    for (int i = 1; i < stageRows.length; i++) {
                        if (stageRows[i] != null && stageRows[i].getChildCount() > 0) {
                            View view = stageRows[i].getChildAt(0);
                            if (view instanceof TextView &&
                                    ((TextView) view).getText().toString().equals(stageName)) {
                                rowIndex = i;
                                break;
                            }
                        }
                    }

                    // If stage not found, we need to add a new row
                    if (rowIndex == -1) {
                        Log.d(TAG, "Adding new row for stage: " + stageName);
                        // We need to rebuild the table to include this stage
                        rebuildTimingsTable();
                        return;
                    }

                    // Add the time value to the appropriate column
                    int runIndex = runCount - 1;
                    if (dataRows[rowIndex].getChildCount() <= runIndex) {
                        // Create a new cell for this timing
                        TextView timeCell = new TextView(this);
                        timeCell.setTypeface(Typeface.MONOSPACE);
                        timeCell.setTextSize(12); // Match RenderActivity font size
                        timeCell.setTextColor(Color.parseColor("#263238"));
                        timeCell.setLayoutParams(new LinearLayout.LayoutParams(
                                150, LinearLayout.LayoutParams.WRAP_CONTENT));
                        timeCell.setPadding(8, 8, 8, 8);
                        timeCell.setText(timeValue);
                        dataRows[rowIndex].addView(timeCell);
                    } else {
                        // Update existing cell
                        View view = dataRows[rowIndex].getChildAt(runIndex);
                        if (view instanceof TextView) {
                            ((TextView) view).setText(timeValue);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void clearTimingsTable() {
        runCount = 0;
        timingRuns.clear();
        timingData.setLength(0); // Also clear the timing data string
        if (timingsTableView != null) {
            timingsTableView.removeAllViews();

            // Safely clear the labels column if it exists
            ViewParent parent = timingsTableView.getParent();
            if (parent != null && parent.getParent() instanceof ViewGroup) {
                ViewGroup container = (ViewGroup) parent.getParent();
                if (container.getChildCount() > 0) {
                    View labelsColumn = container.getChildAt(0);
                    if (labelsColumn instanceof LinearLayout) {
                        ((LinearLayout) labelsColumn).removeAllViews();
                    }
                }
            }
        }
        // Reset arrays
        stageRows = null;
        dataRows = null;

        // Show a message in the timing section when empty
        if (timingsHeaderView != null) {
            timingsHeaderView.setText("Execution Timings (No data yet)");
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("code")) {
            Log.w(TAG, "No code provided in intent");
            updateStats("No code provided", 0, 0L);
            return;
        }

        String encoding = intent.getStringExtra("encoding");
        final String code; // Make code final

        if ("base64".equals(encoding)) {
            String base64Code = intent.getStringExtra("code");
            if (base64Code != null) {
                try {
                    code = new String(Base64.decode(base64Code, Base64.DEFAULT), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Failed to decode base64 content", e);
                    updateStats("Error decoding", null, null);
                    return;
                }
            } else {
                code = null;
            }
        } else {
            code = intent.getStringExtra("code");
        }

        if (code == null || code.trim().isEmpty()) {
            Log.w(TAG, "Empty code provided in intent");
            updateStats("Empty code", 0, 0L);
            return;
        }

        // Create new program instance
        ClojureProgram newProgram = new ClojureProgram(code);

        // Check if program already exists
        for (ClojureProgram existing : programs.values()) {
            if (existing.equals(newProgram)) {
                // Clear timings for existing program
                existing.getTimingRuns().clear();
                currentProgram = existing;
                clearTimingsTable();
                final String finalCode = code; // Create final copy for lambda
                runOnUiThread(() -> {
                    replInput.setText(finalCode);
                    programSpinner.setSelection(
                            spinnerAdapter.getPosition(existing.getName()));
                });
                saveState();
                return;
            }
        }

        // Add new program
        programs.put(newProgram.getName(), newProgram);
        currentProgram = newProgram;

        final String finalCode = code; // Create final copy for lambda
        runOnUiThread(() -> {
            replInput.setText(finalCode);
            updateSpinnerItems();
            programSpinner.setSelection(
                    spinnerAdapter.getPosition(newProgram.getName()));
        });

        saveState();
    }

    private void saveState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Gson gson = new Gson();
        String programsJson = gson.toJson(programs);
        editor.putString(KEY_PROGRAMS, programsJson);

        if (currentProgram != null) {
            editor.putString(KEY_CURRENT_PROGRAM, currentProgram.getName());
        }

        editor.apply();
    }

    private void restoreSavedState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Gson gson = new Gson();
        String programsJson = prefs.getString(KEY_PROGRAMS, null);
        if (programsJson != null) {
            Type type = new TypeToken<HashMap<String, ClojureProgram>>() {
            }.getType();
            programs = gson.fromJson(programsJson, type);

            String currentProgramName = prefs.getString(KEY_CURRENT_PROGRAM, null);
            if (currentProgramName != null && programs.containsKey(currentProgramName)) {
                currentProgram = programs.get(currentProgramName);
                replInput.setText(currentProgram.getCode());

                // Rebuild timing table
                clearTimingsTable();
                for (String timing : currentProgram.getTimingRuns()) {
                    updateTimingsTable(timing);
                }
            }

            updateSpinnerItems();
            if (currentProgramName != null) {
                programSpinner.setSelection(spinnerAdapter.getPosition(currentProgramName));
            }
        }
    }

    private void rebuildTimingsTable() {
        Log.d(TAG, "Rebuilding timings table with " + timingRuns.size() + " runs");

        if (timingRuns.isEmpty()) {
            Log.w(TAG, "No timing data available to rebuild table");
            clearTimingsTable();
            return;
        }

        // Get the labels column and data container
        ViewParent parent = timingsTableView.getParent();
        if (parent == null || !(parent.getParent() instanceof ViewGroup)) {
            Log.e(TAG, "Invalid timings table view hierarchy");
            return;
        }

        ViewGroup container = (ViewGroup) parent.getParent();
        LinearLayout labelsColumn = null;

        if (container.getChildCount() > 0) {
            View firstChild = container.getChildAt(0);
            if (firstChild instanceof LinearLayout) {
                labelsColumn = (LinearLayout) firstChild;
            }
        }

        if (labelsColumn == null) {
            Log.e(TAG, "Could not find labels column");
            return;
        }

        // Clear existing views
        timingsTableView.removeAllViews();
        labelsColumn.removeAllViews();

        // Collect all stage names from all runs
        List<String> allStageNames = new ArrayList<>();

        for (String run : timingRuns) {
            String[] lines = run.split("\n");
            for (String line : lines) {
                if (line.contains(":")) {
                    String[] parts = line.split(":");
                    String stageName = parts[0].trim();
                    if (!allStageNames.contains(stageName)) {
                        allStageNames.add(stageName);
                    }
                }
            }
        }

        // Always include these common stages in a specific order
        List<String> orderedStages = new ArrayList<>();
        String[] commonStages = { "RT init", "ClassLoader", "Vars setup", "Env init", "Eval" };

        for (String stage : commonStages) {
            orderedStages.add(stage);
            allStageNames.remove(stage); // Remove if present to avoid duplicates
        }

        // Add any remaining stages
        orderedStages.addAll(allStageNames);

        int totalRows = orderedStages.size() + 1; // +1 for header row

        // Initialize arrays
        stageRows = new LinearLayout[totalRows];
        dataRows = new LinearLayout[totalRows];

        // Create header row
        stageRows[0] = new LinearLayout(this);
        dataRows[0] = new LinearLayout(this);
        stageRows[0].setOrientation(LinearLayout.HORIZONTAL);
        dataRows[0].setOrientation(LinearLayout.HORIZONTAL);

        // Add "Stage" column header
        TextView stageHeader = new TextView(this);
        stageHeader.setText("Stage");
        stageHeader.setTypeface(Typeface.DEFAULT_BOLD);
        stageHeader.setTextColor(Color.parseColor("#1976D2")); // Material Blue
        stageHeader.setTextSize(12); // Match RenderActivity font size
        stageHeader.setLayoutParams(new LinearLayout.LayoutParams(
                250, LinearLayout.LayoutParams.WRAP_CONTENT));
        stageHeader.setPadding(8, 8, 8, 8);
        stageRows[0].addView(stageHeader);

        // Add run headers
        for (int i = 0; i < timingRuns.size(); i++) {
            TextView runHeader = new TextView(this);
            runHeader.setText("Run " + (i + 1));
            runHeader.setTypeface(Typeface.DEFAULT_BOLD);
            runHeader.setTextColor(Color.parseColor("#1976D2")); // Material Blue
            runHeader.setTextSize(12); // Match RenderActivity font size
            runHeader.setLayoutParams(new LinearLayout.LayoutParams(
                    150, LinearLayout.LayoutParams.WRAP_CONTENT));
            runHeader.setPadding(8, 8, 8, 8);
            dataRows[0].addView(runHeader);
        }

        // Add header rows
        labelsColumn.addView(stageRows[0]);
        timingsTableView.addView(dataRows[0]);

        // Create data rows for each stage
        for (int i = 0; i < orderedStages.size(); i++) {
            String stageName = orderedStages.get(i);
            int rowIndex = i + 1;

            // Create stage label row
            stageRows[rowIndex] = new LinearLayout(this);
            stageRows[rowIndex].setOrientation(LinearLayout.HORIZONTAL);

            TextView stageLabel = new TextView(this);
            stageLabel.setText(stageName);
            stageLabel.setTypeface(Typeface.MONOSPACE);
            stageLabel.setTextSize(12); // Match RenderActivity font size
            stageLabel.setTextColor(Color.parseColor("#263238"));
            stageLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    250, LinearLayout.LayoutParams.WRAP_CONTENT));
            stageLabel.setPadding(8, 8, 8, 8);
            stageRows[rowIndex].addView(stageLabel);

            // Create data row
            dataRows[rowIndex] = new LinearLayout(this);
            dataRows[rowIndex].setOrientation(LinearLayout.HORIZONTAL);

            // Add timing cells for each run
            for (int j = 0; j < timingRuns.size(); j++) {
                String runData = timingRuns.get(j);

                TextView timeCell = new TextView(this);
                timeCell.setTypeface(Typeface.MONOSPACE);
                timeCell.setTextSize(12); // Match RenderActivity font size
                timeCell.setTextColor(Color.parseColor("#263238"));
                timeCell.setLayoutParams(new LinearLayout.LayoutParams(
                        150, LinearLayout.LayoutParams.WRAP_CONTENT));
                timeCell.setPadding(4, 4, 4, 4);

                // Find this stage's timing in the current run
                String timing = findTimingForStage(runData, stageName);
                timeCell.setText(timing != null ? timing : "N/A");

                dataRows[rowIndex].addView(timeCell);
            }

            // Add rows to containers
            labelsColumn.addView(stageRows[rowIndex]);
            timingsTableView.addView(dataRows[rowIndex]);
        }

        // Update timings header text
        if (timingsHeaderView != null) {
            timingsHeaderView.setText("Execution Timings (" + timingRuns.size() + " runs)");
        }
    }

    // Helper method to find timing for a specific stage in run data
    private String findTimingForStage(String runData, String stageName) {
        String[] lines = runData.split("\n");
        for (String line : lines) {
            if (line.contains(":")) {
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[0].trim().equals(stageName)) {
                    return parts[1].trim();
                }
            }
        }
        return null;
    }

    private void setupTimingsUI() {
        // Create timings section
        timingsLayout = new LinearLayout(this);
        timingsLayout.setOrientation(LinearLayout.VERTICAL);
        timingsLayout.setBackgroundColor(Color.parseColor("#F5F5F5"));
        timingsLayout.setPadding(16, 16, 16, 16);

        timingsHeaderView = new TextView(this);
        timingsHeaderView.setText("Execution Timings");
        timingsHeaderView.setTypeface(Typeface.DEFAULT_BOLD);
        timingsHeaderView.setTextColor(Color.parseColor("#1976D2")); // Material Blue
        timingsHeaderView.setTextSize(12); // Match RenderActivity font size
        timingsHeaderView.setPadding(0, 0, 0, 16);
        timingsLayout.addView(timingsHeaderView);

        // Create table view for timings
        timingsTableView = new LinearLayout(this);
        timingsTableView.setOrientation(LinearLayout.VERTICAL);
        timingsTableView.setPadding(0, 8, 0, 0);

        // Create a horizontal layout to hold the labels column and scrollview
        LinearLayout horizontalContainer = new LinearLayout(this);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);

        // Create a container for stage labels (fixed left column)
        LinearLayout labelsColumn = new LinearLayout(this);
        labelsColumn.setOrientation(LinearLayout.VERTICAL);
        labelsColumn.setLayoutParams(new LinearLayout.LayoutParams(
                250, LinearLayout.LayoutParams.WRAP_CONTENT));
        labelsColumn.setBackgroundColor(Color.parseColor("#EEEEEE"));

        // Create a horizontal scroll view for the data
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scrollView.setHorizontalScrollBarEnabled(true); // Ensure scroll bar is visible

        // Add views to container
        horizontalContainer.addView(labelsColumn);
        horizontalContainer.addView(scrollView);
        scrollView.addView(timingsTableView);
        timingsLayout.addView(horizontalContainer);

        // Add timings layout to root
        LinearLayout root = findViewById(R.id.root_layout);
        root.addView(statsView, 0); // Stats view first
        root.addView(timingsLayout, 1); // Then timings
    }

    private void setupProgramSpinner() {
        programSpinner = new Spinner(this);

        // Style the spinner itself
        programSpinner.setBackgroundColor(Color.parseColor("#F5F5F5"));
        programSpinner.setPadding(16, 8, 16, 8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(16, 16, 16, 16);
        programSpinner.setLayoutParams(params);

        // Create adapter with custom layout for both spinner and dropdown
        spinnerAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#263238")); // Dark text
                view.setTypeface(Typeface.DEFAULT_BOLD);
                view.setPadding(8, 8, 8, 8);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#263238")); // Dark text
                view.setBackgroundColor(Color.parseColor("#F5F5F5")); // Light gray background
                view.setPadding(16, 16, 16, 16);
                return view;
            }
        };

        // Set the dropdown layout style
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        programSpinner.setAdapter(spinnerAdapter);

        programSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String programName = (String) parent.getItemAtPosition(position);
                switchToProgram(programName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Add a title/header above the spinner
        TextView header = new TextView(this);
        header.setText("Select Program");
        header.setTextColor(Color.parseColor("#1976D2")); // Blue color
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(16, 16, 16, 8);

        // Create buttons
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        buttonRow.setPadding(16, 8, 16, 8);

        Button clearTimingsButton = new Button(this);
        clearTimingsButton.setText("Clear Timings");
        clearTimingsButton.setOnClickListener(v -> clearCurrentProgramTimings());

        Button deleteProgramButton = new Button(this);
        deleteProgramButton.setText("Delete Program");
        deleteProgramButton.setOnClickListener(v -> deleteCurrentProgram());

        Button clearClassCacheButton = new Button(this);
        clearClassCacheButton.setText("Clear Classes");
        clearClassCacheButton.setOnClickListener(v -> {
            if (currentProgram != null) {
                String code = currentProgram.getCode();
                String codeHash = RenderActivity.getCodeHash(code);
                BytecodeCache.getInstance(this, codeHash).clearCacheForHash(codeHash);
                Toast.makeText(this, "Class cache cleared for current program", Toast.LENGTH_SHORT).show();

                // Update stats after clearing
                int lineCount = code.split("\n").length;
                updateStats("Cache cleared", lineCount, null);
            } else {
                Toast.makeText(this, "No program selected", Toast.LENGTH_SHORT).show();
            }
        });

        Button clearDataButton = new Button(this);
        clearDataButton.setText("Clear App Data");
        clearDataButton.setOnClickListener(v -> {
            if (currentProgram == null) {
                Toast.makeText(this, "No program selected", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                // Use RenderActivity's static method to clear program data
                String code = currentProgram.getCode();
                String codeHash = RenderActivity.getCodeHash(code);
                RenderActivity.clearProgramData(this, codeHash);
                Toast.makeText(this, "Program data cleared successfully", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing program data", e);
                Toast.makeText(this, "Error clearing data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Create layout params with FIXED height instead of WRAP_CONTENT
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, 150, 1); // Fixed height of 120px and weight of 1
        buttonParams.setMargins(4, 0, 4, 0);

        // Set consistent styles for all buttons
        clearTimingsButton.setLayoutParams(buttonParams);
        clearTimingsButton.setAllCaps(true);
        clearTimingsButton.setTextSize(12);
        clearTimingsButton.setBackgroundColor(Color.parseColor("#1976D2")); // Material Blue
        clearTimingsButton.setTextColor(Color.WHITE);

        clearClassCacheButton.setLayoutParams(buttonParams);
        clearClassCacheButton.setAllCaps(true);
        clearClassCacheButton.setTextSize(12);
        clearClassCacheButton.setBackgroundColor(Color.parseColor("#1976D2")); // Material Blue
        clearClassCacheButton.setTextColor(Color.WHITE);

        clearDataButton.setLayoutParams(buttonParams);
        clearDataButton.setAllCaps(true);
        clearDataButton.setTextSize(12);
        clearDataButton.setBackgroundColor(Color.parseColor("#1976D2")); // Material Blue
        clearDataButton.setTextColor(Color.WHITE);

        deleteProgramButton.setLayoutParams(buttonParams);
        deleteProgramButton.setAllCaps(true);
        deleteProgramButton.setTextSize(12);
        deleteProgramButton.setBackgroundColor(Color.parseColor("#1976D2")); // Material Blue
        deleteProgramButton.setTextColor(Color.WHITE);

        buttonRow.addView(clearTimingsButton);
        buttonRow.addView(clearClassCacheButton);
        buttonRow.addView(clearDataButton);
        buttonRow.addView(deleteProgramButton);

        // Create a container for all elements
        LinearLayout spinnerContainer = new LinearLayout(this);
        spinnerContainer.setOrientation(LinearLayout.VERTICAL);
        spinnerContainer.addView(header);
        spinnerContainer.addView(programSpinner);
        spinnerContainer.addView(buttonRow);

        // Add spinner container at the top of the layout
        LinearLayout root = findViewById(R.id.root_layout);
        root.addView(spinnerContainer, 0);
    }

    private void switchToProgram(String programName) {
        if (programName == null || !programs.containsKey(programName))
            return;

        currentProgram = programs.get(programName);
        replInput.setText(currentProgram.getCode());

        // Update stats when switching programs
        String code = currentProgram.getCode();
        int lineCount = code.split("\n").length;
        updateStats("Program loaded", lineCount, null);

        // Clear and rebuild timing table
        clearTimingsTable();
        for (String timing : currentProgram.getTimingRuns()) {
            updateTimingsTable(timing);
        }

        saveState();
    }

    private void updateSpinnerItems() {
        spinnerAdapter.clear();
        spinnerAdapter.addAll(programs.keySet());
        spinnerAdapter.notifyDataSetChanged();
    }

    private void clearCurrentProgramTimings() {
        if (currentProgram == null) {
            Log.w(TAG, "No program selected to clear timings");
            return;
        }

        // Clear timings from the current program
        currentProgram.getTimingRuns().clear();

        // Clear the UI
        clearTimingsTable();

        // Save the state
        saveState();

        Log.d(TAG, "Cleared timings for program: " + currentProgram.getName());
    }

    private void deleteCurrentProgram() {
        if (currentProgram == null) {
            Log.w(TAG, "No program selected to delete");
            return;
        }

        String programName = currentProgram.getName();

        // Remove from programs map
        programs.remove(programName);

        // Update spinner
        updateSpinnerItems();

        // If we have other programs, switch to the first one
        if (!programs.isEmpty()) {
            String firstProgram = spinnerAdapter.getItem(0);
            currentProgram = programs.get(firstProgram);
            replInput.setText(currentProgram.getCode());
            programSpinner.setSelection(0);

            // Update timings table
            clearTimingsTable();
            for (String timing : currentProgram.getTimingRuns()) {
                updateTimingsTable(timing);
            }

            // Update stats for the new current program
            String code = currentProgram.getCode();
            int lineCount = code.split("\n").length;
            updateStats("Program loaded", lineCount, null);
        } else {
            // No programs left
            currentProgram = null;
            replInput.setText("");
            clearTimingsTable();

            // Reset stats view to initial state
            updateStats("Initializing...", null, null);
        }

        // Save the state
        saveState();

        Log.d(TAG, "Deleted program: " + programName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }
}
