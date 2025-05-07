# Clojure App Design Session Export Tools (Intent-Based)

These scripts allow you to export design sessions from the Clojure Android REPL app using Android's intent system to communicate with the app directly. The app processes the request and exports the data to a location accessible to ADB.

## How It Works

1. The scripts send intents directly to the app requesting session data
2. The app processes the request and writes the data to a specific file on the device
3. The scripts pull this data from the device and format it appropriately

This method is more reliable than direct file access as it uses the app's own mechanisms to access its data.

## Requirements

- `adb` (Android Debug Bridge) tool installed on your computer
- `jq` command-line JSON processor
- `bc` command-line calculator
- Android device connected via USB with USB debugging enabled
- The Clojure REPL app must be installed on the device with the intent-handling code

## Setup

1. Connect your Android device via USB
2. Enable USB debugging on the device
3. Verify the connection by running `adb devices` in your terminal
4. Ensure the app is installed on the device

## Available Scripts

### 1. List Sessions

The `list_sessions_intent.sh` script queries the app for all available sessions:

```bash
./list_sessions_intent.sh
```

This will display a table with:
- Index number (needed for export)
- Session ID
- Creation timestamp
- LLM model used
- Description snippet

### 2. Export Session

The `export_session_intent.sh` script requests a specific session's data from the app:

```bash
./export_session_intent.sh <index>
```

For example, to export the first session:
```bash
./export_session_intent.sh 0
```

### Exported Data

The script will create a directory under `./exported_sessions/` with:

- `prompt.txt` - The original app description/prompt
- `chat_history.txt` - Formatted chat history between the user and LLM
- `screenshots/` - Directory containing all screenshots organized by iteration
- `current_code.clj` - The latest Clojure code from the session
- `logcat.txt` - Android logcat output (if available)
- `error_feedback.txt` - Any error feedback (if available)
- `session.json` - The raw session data

## Troubleshooting

- If you get a permissions error, make sure both scripts are executable: 
  ```bash
  chmod +x list_sessions_intent.sh export_session_intent.sh
  ```

- If the app doesn't respond to the intent:
  1. Make sure the app is installed
  2. Verify that you're running the version of the app that includes the intent handling code
  3. Check the package name in the scripts matches your app's package name

- If screenshots fail to transfer:
  1. The app uses a BroadcastReceiver to copy screenshots to an accessible location
  2. Make sure the ScreenshotCopyReceiver is properly registered in your app
  3. Check that your app has write permissions for external storage 