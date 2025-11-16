#!/bin/bash

# Check if a session index was provided
if [ -z "$1" ]; then
  echo "ERROR: Please provide a session index"
  echo "Usage: ./download-design.sh <session_index>"
  echo "Run ./list_sessions.sh to see available sessions"
  exit 1
fi

# Ensure ADB is available
if ! command -v adb &> /dev/null; then
    echo "Error: adb is not installed or not in PATH"
    exit 1
fi

# Check if jq is installed
if ! command -v jq &> /dev/null; then
  echo "ERROR: This script requires jq to be installed"
  exit 1
fi

# Check for connected devices
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "Error: No Android devices connected"
    exit 1
fi

SESSION_INDEX=$1
ANDROID_PKG="com.example.clojurerepl"
ANDROID_DATA_DIR="/data/data/$ANDROID_PKG"
SESSIONS_DIR="$ANDROID_DATA_DIR/files/sessions"
TMP_DIR="/tmp/clojure_session_export"
SESSIONS_JSON="$TMP_DIR/sessions.json"
SESSION_JSON="$TMP_DIR/session_data.json"

# Create temporary directory
mkdir -p "$TMP_DIR"

# Pull all session files from the device
echo "Pulling session files from device..."
SESSIONS_LIST=$(adb shell "su 0 ls $SESSIONS_DIR/*.json 2>/dev/null" | grep -E '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$')

# If that failed, try without su
if [ -z "$SESSIONS_LIST" ]; then
  echo "Trying without superuser privileges..."
  SESSIONS_LIST=$(adb shell "ls $SESSIONS_DIR/*.json 2>/dev/null" | grep -E '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$')
fi

# If that failed, try with run-as
if [ -z "$SESSIONS_LIST" ]; then
  echo "Trying with run-as..."
  SESSIONS_LIST=$(adb shell "run-as $ANDROID_PKG ls files/sessions/*.json 2>/dev/null" | grep -E '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$')
fi

# Check if we found any sessions
if [ -z "$SESSIONS_LIST" ]; then
  echo "ERROR: Failed to find session files on device."
  echo "Make sure the app has created design sessions and that the path is correct."
  echo "Please ensure the device is rooted or the app is debuggable."
  exit 1
fi

# Create a temporary array to store session data
echo "[" > "$SESSIONS_JSON"
FIRST=true

# Pull each session file and combine into a JSON array
for SESSION_FILE in $SESSIONS_LIST; do
  SESSION_TMP="$TMP_DIR/$(basename "$SESSION_FILE")"

  # Try to pull with su first
  adb shell "su 0 cat \"$SESSION_FILE\"" > "$SESSION_TMP" 2>/dev/null

  # If that failed, try without su
  if [ ! -s "$SESSION_TMP" ]; then
    adb shell "cat \"$SESSION_FILE\"" > "$SESSION_TMP" 2>/dev/null
  fi

  # If that failed, try with run-as
  if [ ! -s "$SESSION_TMP" ]; then
    RELATIVE_PATH=$(echo "$SESSION_FILE" | sed "s|$ANDROID_DATA_DIR/||")
    adb shell "run-as $ANDROID_PKG cat \"$RELATIVE_PATH\"" > "$SESSION_TMP" 2>/dev/null
  fi

  # If we successfully pulled the file, add it to the array
  if [ -s "$SESSION_TMP" ]; then
    if [ "$FIRST" = true ]; then
      FIRST=false
    else
      echo "," >> "$SESSIONS_JSON"
    fi
    cat "$SESSION_TMP" >> "$SESSIONS_JSON"
  fi
done

echo "]" >> "$SESSIONS_JSON"

# Sort sessions by createdAt (newest first) and get the session at the specified index
echo "Extracting session at index $SESSION_INDEX..."
jq -r "sort_by(-.createdAt)[$SESSION_INDEX]" "$SESSIONS_JSON" > "$SESSION_JSON"

# Check if session exists at the specified index
if [[ $(cat "$SESSION_JSON") == "null" ]]; then
  echo "ERROR: No session found at index $SESSION_INDEX"
  exit 1
fi

# Extract session info
SESSION_ID=$(jq -r '.id' "$SESSION_JSON")
DESCRIPTION=$(jq -r '.description' "$SESSION_JSON")
CREATED_AT=$(jq -r '.createdAt' "$SESSION_JSON")

# Extract the current code from the session
# Get the last assistant message with extracted code
# First try codeExtractionResult.code, then extractedCode, then try to extract from content
CURRENT_CODE=$(jq -r '[.chatHistory[] | select(.role == "assistant")] | reverse | .[] | (.codeExtractionResult.code // .extractedCode // empty) | select(. != null and . != "")' "$SESSION_JSON" | head -n 1)

# If no code found in codeExtractionResult or extractedCode, try to extract from content
if [ -z "$CURRENT_CODE" ] || [ "$CURRENT_CODE" == "null" ]; then
  echo "No extracted code found, attempting to extract from last assistant message content..."
  LAST_ASSISTANT_CONTENT=$(jq -r '[.chatHistory[] | select(.role == "assistant")] | last | .content // empty' "$SESSION_JSON")
  if [ -n "$LAST_ASSISTANT_CONTENT" ] && [ "$LAST_ASSISTANT_CONTENT" != "null" ]; then
    # Try to extract code block from content (between ```clojure and ```)
    CURRENT_CODE=$(echo "$LAST_ASSISTANT_CONTENT" | sed -n '/```clojure/,/```/p' | sed '1d;$d' | sed '/^```$/d')
  fi
fi

# Check if we found any code
if [ -z "$CURRENT_CODE" ] || [ "$CURRENT_CODE" == "null" ]; then
  echo "ERROR: No code found in session $SESSION_ID"
  echo "Description: $DESCRIPTION"
  exit 1
fi

# Create the output directory if it doesn't exist
mkdir -p ./downloaded-code

# Generate timestamp for filename
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="./downloaded-code/design_${TIMESTAMP}.clj"

# Write the code to the output file
echo "$CURRENT_CODE" > "$OUTPUT_FILE"

if [ $? -eq 0 ]; then
    echo "Success! Code saved to $OUTPUT_FILE"
    echo "Session: $DESCRIPTION"
    echo "Content preview:"
    echo "----------------------------------------"
    head -n 10 "$OUTPUT_FILE"
    echo "... (truncated)"
    echo "----------------------------------------"
else
    echo "Error: Failed to save code"
    exit 1
fi

# Clean up temp directory
rm -rf "$TMP_DIR"
