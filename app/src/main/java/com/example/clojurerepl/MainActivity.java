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
    private LinearLayout[] dataRows;  // Array to keep track of data columns
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
        
        bytecodeCache = BytecodeCache.getInstance(this);
        
        // Add a clear cache button somewhere in your UI, for example:
        Button clearCacheButton = new Button(this);
        clearCacheButton.setText("Clear Bytecode Cache");
        clearCacheButton.setOnClickListener(v -> {
            bytecodeCache.clearCache();
            Toast.makeText(this, "Bytecode cache cleared", Toast.LENGTH_SHORT).show();
        });
        
        // Add to your layout
        // ...

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
            styleLabel(spannableStats, text, "Time:");
            
            statsView.setText(spannableStats);
        });
    }
    
    private void styleLabel(SpannableString spannableString, String fullText, String label) {
        int start = fullText.indexOf(label);
        if (start >= 0) {
            spannableString.setSpan(new StyleSpan(Typeface.BOLD), start, start + label.length(), 0);
            spannableString.setSpan(new ForegroundColorSpan(Color.parseColor("#1976D2")), start, start + label.length(), 0);  // Darker blue
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
            }
        }
    }

    private void updateTimingsTable(String timingsData) {
        // If the timings table hasn't been initialized yet, do nothing
        if (stageRows == null || stageRows.length == 0) {
            // Initialize the table with the first set of timings
            rebuildTimingsTable();
            return;
        }
        
        // Parse timings data
        String[] lines = timingsData.split("\n");
        
        // Add this timing data to the list of runs
        if (!timingRuns.contains(timingsData)) {
            timingRuns.add(timingsData);
        }
        
        // Handle case where we need to create a new column for a new run
        runCount = Math.max(runCount, timingRuns.size());
        
        // Make sure we have enough rows before trying to update
        int rowsToParse = Math.min(lines.length, stageRows.length - 1);
        
        for (int i = 0; i < rowsToParse; i++) {
            String line = lines[i];
            if (line.contains(":")) {
                String[] parts = line.split(":");
                String stage = parts[0].trim();
                String time = parts[1].trim();
                
                // Update the stage label in the first column if it's not already set
                TextView stageLabel = null;
                if (stageRows[i+1].getChildCount() > 0) {
                    View view = stageRows[i+1].getChildAt(0);
                    if (view instanceof TextView) {
                        stageLabel = (TextView) view;
                        if (stageLabel.getText().toString().isEmpty()) {
                            stageLabel.setText(stage);
                        }
                    }
                }
                
                // Add timing to this run's column
                int runIndex = runCount - 1;
                if (dataRows[i+1].getChildCount() <= runIndex) {
                    // Create a new cell for this timing
                    TextView timeCell = new TextView(this);
                    timeCell.setTypeface(Typeface.MONOSPACE);
                    timeCell.setTextColor(Color.parseColor("#263238"));
                    timeCell.setLayoutParams(new LinearLayout.LayoutParams(
                        150, LinearLayout.LayoutParams.WRAP_CONTENT));
                    timeCell.setPadding(4, 4, 4, 4);
                    timeCell.setText(time);
                    dataRows[i+1].addView(timeCell);
                } else {
                    // Update existing cell
                    View view = dataRows[i+1].getChildAt(runIndex);
                    if (view instanceof TextView) {
                        ((TextView) view).setText(time);
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
        timingData.setLength(0);  // Also clear the timing data string
        if (timingsTableView != null) {
            timingsTableView.removeAllViews();
            
            // Safely clear the labels column if it exists
            ViewParent parent = timingsTableView.getParent();
            if (parent != null && parent.getParent() instanceof ViewGroup) {
                ViewGroup container = (ViewGroup) parent.getParent();
                View labelsColumn = container.getChildAt(0);
                if (labelsColumn instanceof LinearLayout) {
                    ((LinearLayout) labelsColumn).removeAllViews();
                }
            }
        }
        // Reset arrays
        stageRows = null;
        dataRows = null;
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("code")) {
            Log.w(TAG, "No code provided in intent");
            updateStats("No code provided", 0, 0L);
            return;
        }

        String encoding = intent.getStringExtra("encoding");
        final String code;  // Make code final

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
                final String finalCode = code;  // Create final copy for lambda
                runOnUiThread(() -> {
                    replInput.setText(finalCode);
                    programSpinner.setSelection(
                        spinnerAdapter.getPosition(existing.getName())
                    );
                });
                saveState();
                return;
            }
        }
        
        // Add new program
        programs.put(newProgram.getName(), newProgram);
        currentProgram = newProgram;
        
        final String finalCode = code;  // Create final copy for lambda
        runOnUiThread(() -> {
            replInput.setText(finalCode);
            updateSpinnerItems();
            programSpinner.setSelection(
                spinnerAdapter.getPosition(newProgram.getName())
            );
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
            Type type = new TypeToken<HashMap<String, ClojureProgram>>(){}.getType();
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
        if (timingRuns.isEmpty()) {
            return;
        }

        // Get the labels column and data container
        ViewParent parent = timingsTableView.getParent();
        if (parent == null || !(parent.getParent() instanceof ViewGroup)) {
            Log.e(TAG, "Invalid timings table view hierarchy");
            return;
        }
        ViewGroup container = (ViewGroup) parent.getParent();
        LinearLayout labelsColumn = (LinearLayout) container.getChildAt(0);
        
        // Clear existing views
        timingsTableView.removeAllViews();
        labelsColumn.removeAllViews();

        // Parse the timings into rows and find the maximum number of stages across all runs
        int maxStages = 0;
        List<String> allStageNames = new ArrayList<>();
        
        // First pass: collect all unique stage names and find max stages
        for (String run : timingRuns) {
            String[] runRows = run.split(TIMING_LINE_SEPARATOR);
            for (String row : runRows) {
                String[] parts = row.split(":");
                if (parts.length >= 1) {
                    String stageName = parts[0].trim();
                    if (!allStageNames.contains(stageName)) {
                        allStageNames.add(stageName);
                    }
                }
            }
            maxStages = Math.max(maxStages, runRows.length);
        }
        
        // Always ensure we have at least these stages
        String[] requiredStages = {"RT init", "ClassLoader", "Vars setup", "Env init", "Parse", "Execute", "Total"};
        for (String stage : requiredStages) {
            if (!allStageNames.contains(stage)) {
                allStageNames.add(stage);
                maxStages = Math.max(maxStages, allStageNames.size());
            }
        }
        
        if (maxStages == 0) {
            Log.w(TAG, "No timing rows to display");
            return;
        }

        // Initialize arrays
        stageRows = new LinearLayout[maxStages + 1];
        dataRows = new LinearLayout[maxStages + 1];

        // Create and populate the table using the same logic as updateTimingsTable
        // Create header row
        stageRows[0] = new LinearLayout(this);
        dataRows[0] = new LinearLayout(this);
        stageRows[0].setOrientation(LinearLayout.HORIZONTAL);
        dataRows[0].setOrientation(LinearLayout.HORIZONTAL);
        
        // Add "Stage" column header to fixed column
        TextView stageHeader = new TextView(this);
        stageHeader.setText("Stage");
        stageHeader.setTypeface(Typeface.DEFAULT_BOLD);
        stageHeader.setTextColor(Color.parseColor("#1976D2"));
        stageHeader.setLayoutParams(new LinearLayout.LayoutParams(
            250, LinearLayout.LayoutParams.WRAP_CONTENT));
        stageHeader.setPadding(4, 4, 4, 4);
        stageRows[0].addView(stageHeader);
        
        // Add run number headers
        for (int run = 0; run < timingRuns.size(); run++) {
            TextView runHeader = new TextView(this);
            runHeader.setText("Run " + (run + 1));
            runHeader.setTypeface(Typeface.DEFAULT_BOLD);
            runHeader.setTextColor(Color.parseColor("#1976D2"));
            runHeader.setLayoutParams(new LinearLayout.LayoutParams(
                150, LinearLayout.LayoutParams.WRAP_CONTENT));
            runHeader.setPadding(4, 4, 4, 4);
            dataRows[0].addView(runHeader);
        }
        
        // Add header rows to the layouts
        labelsColumn.addView(stageRows[0]);
        timingsTableView.addView(dataRows[0]);
        
        // Create data rows
        for (int i = 0; i < allStageNames.size(); i++) {
            String stageName = allStageNames.get(i);
            
            // Create stage label row
            stageRows[i + 1] = new LinearLayout(this);
            stageRows[i + 1].setOrientation(LinearLayout.HORIZONTAL);
            
            TextView stageLabel = new TextView(this);
            stageLabel.setText(stageName);
            stageLabel.setTypeface(Typeface.MONOSPACE);
            stageLabel.setTextColor(Color.parseColor("#263238"));
            stageLabel.setLayoutParams(new LinearLayout.LayoutParams(
                250, LinearLayout.LayoutParams.WRAP_CONTENT));
            stageLabel.setPadding(4, 4, 4, 4);
            stageRows[i + 1].addView(stageLabel);
            
            // Create data row
            dataRows[i + 1] = new LinearLayout(this);
            dataRows[i + 1].setOrientation(LinearLayout.HORIZONTAL);
            
            // Add timing cells for each run
            for (String runData : timingRuns) {
                String[] runRows = runData.split(TIMING_LINE_SEPARATOR);
                TextView timeCell = new TextView(this);
                timeCell.setTypeface(Typeface.MONOSPACE);
                timeCell.setTextColor(Color.parseColor("#263238"));
                timeCell.setLayoutParams(new LinearLayout.LayoutParams(
                    150, LinearLayout.LayoutParams.WRAP_CONTENT));
                timeCell.setPadding(4, 4, 4, 4);
                
                // Find timing for this stage in the current run
                String timing = "N/A";
                for (String row : runRows) {
                    String[] parts = row.split(":");
                    if (parts.length >= 2 && parts[0].trim().equals(stageName)) {
                        timing = parts[1].trim();
                        break;
                    }
                }
                timeCell.setText(timing);
                
                dataRows[i + 1].addView(timeCell);
            }
            
            // Add rows to their containers
            labelsColumn.addView(stageRows[i + 1]);
            timingsTableView.addView(dataRows[i + 1]);
        }
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
        timingsHeaderView.setTextColor(Color.parseColor("#1976D2"));
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

        // Create a horizontal scroll view for the data
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // Add views to container
        horizontalContainer.addView(labelsColumn);
        horizontalContainer.addView(scrollView);
        scrollView.addView(timingsTableView);
        timingsLayout.addView(horizontalContainer);
        
        // Add timings layout to root
        LinearLayout root = findViewById(R.id.root_layout);
        root.addView(statsView, 0);
        root.addView(timingsLayout, 1);
    }

    private void setupProgramSpinner() {
        programSpinner = new Spinner(this);
        
        // Style the spinner itself
        programSpinner.setBackgroundColor(Color.parseColor("#F5F5F5"));
        programSpinner.setPadding(16, 8, 16, 8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
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
            public void onNothingSelected(AdapterView<?> parent) {}
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
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        buttonRow.setPadding(16, 8, 16, 8);
        
        Button clearTimingsButton = new Button(this);
        clearTimingsButton.setText("Clear Timings");
        clearTimingsButton.setOnClickListener(v -> clearCurrentProgramTimings());
        
        Button deleteProgramButton = new Button(this);
        deleteProgramButton.setText("Delete Program");
        deleteProgramButton.setOnClickListener(v -> deleteCurrentProgram());
        
        // Add equal weight to buttons
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        );
        buttonParams.setMargins(0, 0, 8, 0);  // Add margin between buttons
        
        clearTimingsButton.setLayoutParams(buttonParams);
        deleteProgramButton.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        ));
        
        buttonRow.addView(clearTimingsButton);
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
        if (programName == null || !programs.containsKey(programName)) return;
        
        currentProgram = programs.get(programName);
        replInput.setText(currentProgram.getCode());
        
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
        } else {
            // No programs left
            currentProgram = null;
            replInput.setText("");
            clearTimingsTable();
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