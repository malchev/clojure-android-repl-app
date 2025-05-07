#!/bin/bash

# Script to export a design session's chat history, screenshots, and initial description from a connected Android device
# Usage: ./export_session.sh <session_index>

# Check if a session index was provided
if [ -z "$1" ]; then
  echo "ERROR: Please provide a session index"
  echo "Usage: ./export_session.sh <session_index>"
  exit 1
fi

# Check if adb is installed
if ! command -v adb &> /dev/null; then
  echo "ERROR: This script requires adb to be installed"
  exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No Android device connected. Please connect a device and try again."
  exit 1
fi

SESSION_INDEX=$1
ANDROID_PKG="com.example.clojurerepl"
ANDROID_DATA_DIR="/data/data/$ANDROID_PKG"
SESSIONS_FILE="$ANDROID_DATA_DIR/files/sessions/design_sessions.json"
EXPORT_BASE_DIR="./exported_sessions"
TMP_DIR="/tmp/clojure_session_export"

# Create temporary directory
mkdir -p "$TMP_DIR"
SESSION_JSON="$TMP_DIR/session_data.json"
SESSIONS_JSON="$TMP_DIR/sessions.json"

# Pull the sessions file from the device
echo "Pulling sessions data from device..."
adb shell "su 0 cat $SESSIONS_FILE" > "$SESSIONS_JSON"

# Check if the sessions file was successfully pulled
if [ ! -s "$SESSIONS_JSON" ]; then
  echo "ERROR: Failed to pull sessions file from device or file is empty."
  echo "Make sure the app has created design sessions and that the path is correct."
  echo "Attempted to read: $SESSIONS_FILE"

  # Try without su as fallback
  echo "Trying without superuser privileges..."
  adb shell "cat $SESSIONS_FILE" > "$SESSIONS_JSON"

  if [ ! -s "$SESSIONS_JSON" ]; then
    echo "ERROR: Failed to pull sessions file without superuser privileges."
    echo "Checking if app has storage permissions..."

    # Try with run-as if available (works for debuggable apps)
    adb shell "run-as $ANDROID_PKG cat files/sessions/design_sessions.json" > "$SESSIONS_JSON"

    if [ ! -s "$SESSIONS_JSON" ]; then
      echo "ERROR: All attempts to access session data failed."
      echo "Please ensure the device is rooted or the app is debuggable."
      exit 1
    fi
  fi
fi

# Use jq to extract all sessions and get the session at the specified index
echo "Extracting session at index $SESSION_INDEX..."
jq -r ".[$SESSION_INDEX]" "$SESSIONS_JSON" > "$SESSION_JSON"

# Check if session exists at the specified index
if [[ $(cat "$SESSION_JSON") == "null" ]]; then
  echo "ERROR: No session found at index $SESSION_INDEX"
  exit 1
fi

# Extract session ID and description
SESSION_ID=$(jq -r '.id' "$SESSION_JSON")
DESCRIPTION=$(jq -r '.description' "$SESSION_JSON")
CREATED_AT=$(jq -r '.createdAt' "$SESSION_JSON")
LLM_TYPE=$(jq -r '.llmType' "$SESSION_JSON")
LLM_MODEL=$(jq -r '.llmModel' "$SESSION_JSON")

# Create timestamp from createdAt (which is in milliseconds since epoch)
TIMESTAMP=$(date -d "@$(echo $CREATED_AT/1000 | bc)" "+%Y-%m-%d_%H-%M-%S")

# Create a directory for this session
EXPORT_DIR="$EXPORT_BASE_DIR/${SESSION_ID}_${TIMESTAMP}"
mkdir -p "$EXPORT_DIR"

echo "Exporting session $SESSION_ID to $EXPORT_DIR"
echo "Description: $DESCRIPTION"
echo "Model: $LLM_TYPE ($LLM_MODEL)"

# Save the description to prompt.txt
echo "$DESCRIPTION" > "$EXPORT_DIR/prompt.txt"

# Extract chat history and format it nicely
echo "Extracting chat history..."
CHAT_FILE="$EXPORT_DIR/chat_history.txt"

echo "===============================================================" > "$CHAT_FILE"
echo "CHAT HISTORY FOR SESSION: $SESSION_ID" >> "$CHAT_FILE"
echo "Created: $(date -d "@$(echo $CREATED_AT/1000 | bc)" "+%Y-%m-%d %H:%M:%S")" >> "$CHAT_FILE"
echo "Model: $LLM_TYPE ($LLM_MODEL)" >> "$CHAT_FILE"
echo "===============================================================" >> "$CHAT_FILE"
echo "" >> "$CHAT_FILE"

# Process chat history messages
jq -c '.chatHistory[]' "$SESSION_JSON" | while read -r message; do
  ROLE=$(echo "$message" | jq -r '.role')
  CONTENT=$(echo "$message" | jq -r '.content')

  # Format based on role
  case $ROLE in
    system)
      echo "🤖 SYSTEM:" >> "$CHAT_FILE"
      ;;
    user)
      echo "👤 USER:" >> "$CHAT_FILE"
      ;;
    assistant)
      echo "🧠 ASSISTANT:" >> "$CHAT_FILE"
      ;;
    *)
      echo "[$ROLE]:" >> "$CHAT_FILE"
      ;;
  esac

  # Add the content with proper indentation
  echo "$CONTENT" | sed 's/^/    /' >> "$CHAT_FILE"
  echo "" >> "$CHAT_FILE"
  echo "---------------------------------------------------------------" >> "$CHAT_FILE"
  echo "" >> "$CHAT_FILE"
done

# Create a directory for screenshots
SCREENSHOTS_DIR="$EXPORT_DIR/screenshots"
mkdir -p "$SCREENSHOTS_DIR"

# Extract and copy screenshots from all sets
echo "Copying screenshots..."

# Create counter for screenshot set numbering
SET_COUNTER=1

# Create a temporary directory for screenshots
SCREENSHOT_TMP_DIR="$TMP_DIR/screenshots"
mkdir -p "$SCREENSHOT_TMP_DIR"

# Get all screenshot sets and process them
jq -c '.screenshotSets[]' "$SESSION_JSON" 2>/dev/null | while read -r screenshot_set; do
  # Create a directory for this set
  SET_DIR="$SCREENSHOTS_DIR/set_${SET_COUNTER}"
  mkdir -p "$SET_DIR"

  # Extract and process each screenshot path in the set
  echo "$screenshot_set" | jq -r '.[]' | while read -r screenshot_path; do
    # Get just the filename part
    FILENAME=$(basename "$screenshot_path")
    TEMP_SCREENSHOT="$SCREENSHOT_TMP_DIR/$FILENAME"

    # Try to pull screenshot from device
    adb shell "su 0 cat \"$screenshot_path\"" > "$TEMP_SCREENSHOT" 2>/dev/null

    # If that failed, try without su
    if [ ! -s "$TEMP_SCREENSHOT" ]; then
      adb shell "cat \"$screenshot_path\"" > "$TEMP_SCREENSHOT" 2>/dev/null
    fi

    # If it still failed, try run-as
    if [ ! -s "$TEMP_SCREENSHOT" ]; then
      # Get path relative to package data
      if [[ "$screenshot_path" == *"/data/data/$ANDROID_PKG/"* ]]; then
        REL_PATH=${screenshot_path#*"/data/data/$ANDROID_PKG/"}
        adb shell "run-as $ANDROID_PKG cat \"$REL_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null
      fi
    fi

    # Check if we successfully pulled the screenshot
    if [ -s "$TEMP_SCREENSHOT" ]; then
      # Copy the screenshot to the export directory
      cp "$TEMP_SCREENSHOT" "$SET_DIR/$FILENAME"
      echo "Copied screenshot: $FILENAME to set $SET_COUNTER"
    else
      echo "Warning: Could not pull screenshot from path: $screenshot_path"

      # If the file is in external storage, we might need to try a different approach
      if [[ "$screenshot_path" == *"/storage/"* ]]; then
        echo "Attempting to pull from external storage..."
        # Pull to a temp location on device first, if using external storage
        DEVICE_TMP="/data/local/tmp/$FILENAME"
        adb shell "cp \"$screenshot_path\" \"$DEVICE_TMP\"" 2>/dev/null
        adb shell "chmod 644 \"$DEVICE_TMP\"" 2>/dev/null
        adb pull "$DEVICE_TMP" "$SET_DIR/$FILENAME" 2>/dev/null
        adb shell "rm \"$DEVICE_TMP\"" 2>/dev/null

        if [ -f "$SET_DIR/$FILENAME" ]; then
          echo "Successfully pulled screenshot via external storage: $FILENAME"
        else
          echo "Failed to pull screenshot from external storage: $screenshot_path"
        fi
      fi
    fi
  done

  # Increment set counter for next iteration
  SET_COUNTER=$((SET_COUNTER + 1))
done

# Extract and save the current code
CODE=$(jq -r '.currentCode' "$SESSION_JSON")
echo "$CODE" > "$EXPORT_DIR/current_code.clj"

# Extract and save logcat output if it exists
LOGCAT=$(jq -r '.lastLogcat' "$SESSION_JSON")
if [ "$LOGCAT" != "null" ]; then
  echo "$LOGCAT" > "$EXPORT_DIR/logcat.txt"
  echo "Saved logcat output"
fi

# Save error feedback if it exists
ERROR_FEEDBACK=$(jq -r '.lastErrorFeedback' "$SESSION_JSON")
if [ "$ERROR_FEEDBACK" != "null" ]; then
  echo "$ERROR_FEEDBACK" > "$EXPORT_DIR/error_feedback.txt"
  echo "Saved error feedback"
fi

# Save the original session JSON for reference
cp "$SESSION_JSON" "$EXPORT_DIR/session.json"

echo "=============================================================="
echo "Session export complete!"
echo "Exported to: $EXPORT_DIR"
echo "Chat history: $CHAT_FILE"
echo "Screenshots: $SCREENSHOTS_DIR"
echo "Code: $EXPORT_DIR/current_code.clj"
echo "=============================================================="

# Clean up temp directory
rm -rf "$TMP_DIR"
