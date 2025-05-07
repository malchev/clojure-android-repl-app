# Clojure App Design Session Export Tools

These scripts allow you to export design sessions from the Clojure Android REPL app, including chat history, screenshots, and code. The scripts run on your computer and pull data from a connected Android device using ADB.

## Requirements

- `adb` (Android Debug Bridge) tool installed on your computer
- `jq` command-line JSON processor
- `bc` command-line calculator
- Android device connected via USB with USB debugging enabled
- The app must be either:
  - On a rooted device (preferred)
  - A debug build of the app
  - Have proper storage permissions

## Setup

1. Connect your Android device via USB
2. Enable USB debugging on the device
3. Verify the connection by running `adb devices` in your terminal

## Available Scripts

### 1. List Sessions

The `list_sessions.sh` script displays all available design sessions with their indices:

```bash
./list_sessions.sh
```

This will display a table with:
- Index number (needed for export)
- Session ID
- Creation timestamp
- LLM model used
- Description snippet

### 2. Export Session

The `export_session.sh` script exports a complete session by its index number:

```bash
./export_session.sh <index>
```

For example, to export the first session:
```bash
./export_session.sh 0
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

## Access Methods

The scripts attempt to access the app data in multiple ways (in order):

1. Using `su` (root method)
2. Direct access (may work on some devices)
3. Using `run-as` (works for debug builds)

If all methods fail, you'll get an error message.

## Troubleshooting

- If you get a permissions error, make sure both scripts are executable: 
  ```bash
  chmod +x list_sessions.sh export_session.sh
  ```

- If the sessions file cannot be pulled from the device:
  1. Check that your device is properly connected with `adb devices`
  2. Ensure your device is rooted, or the app is a debug build
  3. Verify the path to the sessions file is correct

- If screenshot access fails, the script will try alternative methods to pull the files.

- If you need to change the app package name, edit the `ANDROID_PKG` variable in both scripts. 