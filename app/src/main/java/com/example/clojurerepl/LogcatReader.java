package com.example.clojurerepl;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class LogcatReader {
    private static final String TAG = "LogcatReader";

    /**
     * Reads logcat entries for a specific process ID
     *
     * @param pid The process ID to filter logs for
     * @return A list of logcat entries as strings
     */
    public static List<String> getLogsForProcess(int pid) {
        List<String> logLines = new ArrayList<>();

        try {
            // Clear the log buffer first to avoid getting old logs
            // Runtime.getRuntime().exec("logcat -c");

            // Execute logcat command to get logs for the specific process
            Process process = Runtime.getRuntime().exec("logcat *:E *:W -d --pid=" + pid);
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                logLines.add(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading logcat", e);
        }

        return logLines;
    }

    /**
     * Reads logcat entries for the current process
     *
     * @return A list of logcat entries as strings
     */
    public static List<String> getLogsForCurrentProcess() {
        return getLogsForProcess(android.os.Process.myPid());
    }
}
