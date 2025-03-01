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
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import android.util.Log;
import android.util.Base64;
import clojure.lang.Symbol;
import clojure.lang.Var;
import clojure.lang.Compiler;
import java.lang.reflect.Field;
import clojure.lang.DynamicClassLoader;
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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "ClojureREPL";
    private EditText replInput;
    private TextView replOutput;
    private TextView statsView;
    private LinearLayout timingsLayout;
    private TextView timingsHeaderView;
    private LinearLayout timingsTableView;
    private int runCount = 0;
    private DynamicClassLoader clojureClassLoader;
    private LinearLayout[] stageRows; // Array to keep track of stage label columns
    private LinearLayout[] dataRows;  // Array to keep track of data columns

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
        
        // Create and add stats view
        statsView = new TextView(this);
        statsView.setTextSize(14);
        statsView.setPadding(16, 16, 16, 16);
        statsView.setBackgroundColor(Color.parseColor("#F5F5F5"));
        statsView.setTextColor(Color.parseColor("#263238"));
        statsView.setTypeface(Typeface.MONOSPACE);
        
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

        // Add the containers to the main layout
        horizontalContainer.addView(labelsColumn);
        horizontalContainer.addView(scrollView);
        scrollView.addView(timingsTableView);
        timingsLayout.addView(horizontalContainer);
        
        LinearLayout root = findViewById(R.id.root_layout);
        root.addView(statsView, 0);
        root.addView(timingsLayout, 1);
        
        // Set up launch button
        Button launchButton = findViewById(R.id.launch_button);
        launchButton.setOnClickListener(v -> launchRenderActivity());
        
        updateStats("Initializing...", null, null);
        
        try {
            // Disable spec checking before any Clojure initialization
            System.setProperty("clojure.spec.skip-macros", "true");
            System.setProperty("clojure.spec.compile-asserts", "false");
            
            long startTime = System.currentTimeMillis();
            setupClojureClassLoader();
            RT.init();
            long initTime = System.currentTimeMillis() - startTime;
            
            updateStats("Ready", 0, initTime);
            
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

    private void setupClojureClassLoader() {
        try {
            // Create a custom class loader that can handle dynamic classes
            clojureClassLoader = new DynamicClassLoader(getClass().getClassLoader());
            
            // Set up the Android delegate
            AndroidClassLoaderDelegate delegate = new AndroidClassLoaderDelegate(
                getApplicationContext(),
                clojureClassLoader
            );
            
            // Set the delegate via reflection since we're using our own implementation
            Field delegateField = DynamicClassLoader.class.getDeclaredField("androidDelegate");
            delegateField.setAccessible(true);
            delegateField.set(null, delegate);
            
            // Set the context class loader
            Thread.currentThread().setContextClassLoader(clojureClassLoader);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up class loader", e);
            throw new RuntimeException(e);
        }
    }

    private void launchRenderActivity() {
        String code = replInput.getText().toString();
        if (code.trim().isEmpty()) {
            replOutput.setText("Please enter some code first");
            updateStats("No code", 0, 0L);
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            String timings = data.getStringExtra("timings");
            if (timings != null) {
                runCount++;
                updateTimingsTable(timings);
            }
        }
    }

    private void updateTimingsTable(String newTimings) {
        // Parse the timings into rows
        String[] rows = newTimings.split("\n");
        
        // Create header row if this is the first run
        if (runCount == 1) {
            // Initialize arrays for stage rows
            stageRows = new LinearLayout[rows.length + 1]; // +1 for header
            dataRows = new LinearLayout[rows.length + 1];
            
            // Create header rows
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
            
            // Add header row to the layouts
            LinearLayout labelsColumn = (LinearLayout) ((ViewGroup) ((ViewGroup) timingsTableView.getParent()).getParent()).getChildAt(0);
            labelsColumn.addView(stageRows[0]);
            timingsTableView.addView(dataRows[0]);
            
            // Create rows for each stage
            int rowIndex = 1;
            for (String row : rows) {
                String[] parts = row.split(":");
                if (parts.length < 2) continue;
                
                String stageName = parts[0].trim();
                
                // Create fixed label column
                stageRows[rowIndex] = new LinearLayout(this);
                stageRows[rowIndex].setOrientation(LinearLayout.HORIZONTAL);
                
                TextView stageLabel = new TextView(this);
                stageLabel.setText(stageName);
                stageLabel.setTypeface(Typeface.MONOSPACE);
                stageLabel.setTextColor(Color.parseColor("#263238"));
                stageLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    250, LinearLayout.LayoutParams.WRAP_CONTENT));
                stageLabel.setPadding(4, 4, 4, 4);
                stageRows[rowIndex].addView(stageLabel);
                
                // Create scrollable data row
                dataRows[rowIndex] = new LinearLayout(this);
                dataRows[rowIndex].setOrientation(LinearLayout.HORIZONTAL);
                dataRows[rowIndex].setTag("row_" + stageName);
                
                // Add rows to their respective containers
                labelsColumn.addView(stageRows[rowIndex]);
                timingsTableView.addView(dataRows[rowIndex]);
                
                rowIndex++;
            }
        }
        
        // Add column header for this run
        TextView runHeader = new TextView(this);
        runHeader.setText("Run " + runCount);
        runHeader.setTypeface(Typeface.DEFAULT_BOLD);
        runHeader.setTextColor(Color.parseColor("#1976D2"));
        runHeader.setLayoutParams(new LinearLayout.LayoutParams(
            150, LinearLayout.LayoutParams.WRAP_CONTENT));
        runHeader.setPadding(4, 4, 4, 4);
        dataRows[0].addView(runHeader);
        
        // Add timing data for each stage
        for (String row : rows) {
            String[] parts = row.split(":");
            if (parts.length < 2) continue;
            
            String stageName = parts[0].trim();
            String timing = parts[1].trim();
            
            // Find the data row for this stage
            LinearLayout dataRow = null;
            for (int i = 1; i < dataRows.length; i++) {
                if (dataRows[i] != null && 
                    dataRows[i].getTag() != null && 
                    dataRows[i].getTag().toString().equals("row_" + stageName)) {
                    dataRow = dataRows[i];
                    break;
                }
            }
            
            // Add the timing cell
            TextView timeCell = new TextView(this);
            timeCell.setText(timing);
            timeCell.setTypeface(Typeface.MONOSPACE);
            timeCell.setTextColor(Color.parseColor("#263238"));
            timeCell.setLayoutParams(new LinearLayout.LayoutParams(
                150, LinearLayout.LayoutParams.WRAP_CONTENT));
            timeCell.setPadding(4, 4, 4, 4);
            if (dataRow != null) {
                dataRow.addView(timeCell);
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
        if (timingsTableView != null) {
            timingsTableView.removeAllViews();
        }
        // Clear the labels column
        LinearLayout labelsColumn = (LinearLayout) ((ViewGroup) ((ViewGroup) timingsTableView.getParent()).getParent()).getChildAt(0);
        if (labelsColumn != null) {
            labelsColumn.removeAllViews();
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

        // Clear timing table for new code
        clearTimingsTable();

        String code = null;
        String encoding = intent.getStringExtra("encoding");

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
            }
        } else {
            code = intent.getStringExtra("code");
        }

        if (code == null || code.trim().isEmpty()) {
            Log.w(TAG, "Empty code provided in intent");
            updateStats("Empty code", 0, 0L);
            return;
        }

        // Show the code in the input field
        final String finalCode = code;
        runOnUiThread(() -> replInput.setText(finalCode));
    }
} 