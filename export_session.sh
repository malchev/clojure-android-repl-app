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
SESSIONS_DIR="$ANDROID_DATA_DIR/files/sessions"
EXPORT_BASE_DIR="./exported_sessions"
TMP_DIR="/tmp/clojure_session_export"

# Create temporary directory
mkdir -p "$TMP_DIR"
SESSION_JSON="$TMP_DIR/session_data.json"
SESSIONS_JSON="$TMP_DIR/sessions.json"

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

# Create a directory for code attempts
CODE_ATTEMPTS_DIR="$EXPORT_DIR/code_attempts"
mkdir -p "$CODE_ATTEMPTS_DIR"

# Initialize attempt counter
ATTEMPT_COUNTER=1

# Process chat history messages
jq -c '.chatHistory[]' "$SESSION_JSON" | while read -r message; do
  ROLE=$(echo "$message" | jq -r '.role')
  CONTENT=$(echo "$message" | jq -r '.content')
  MODEL_PROVIDER=$(echo "$message" | jq -r '.modelProvider // empty')
  MODEL_NAME=$(echo "$message" | jq -r '.modelName // empty')

  # Format based on role
  case $ROLE in
    system)
      echo "🤖 SYSTEM:" >> "$CHAT_FILE"
      ;;
    user)
      echo "👤 USER:" >> "$CHAT_FILE"
      ;;
    assistant)
      if [ -n "$MODEL_PROVIDER" ] && [ -n "$MODEL_NAME" ]; then
        echo "🧠 ASSISTANT ($MODEL_PROVIDER/$MODEL_NAME):" >> "$CHAT_FILE"
      else
        echo "🧠 ASSISTANT:" >> "$CHAT_FILE"
      fi

      # Extract code blocks from assistant messages
      if [ "$ROLE" = "assistant" ]; then
        # Look for code blocks marked with ```clojure or ``` (generic code blocks)
        if echo "$CONTENT" | grep -q '```clojure\|```'; then
          echo "Extracting code from assistant response (attempt $ATTEMPT_COUNTER)..."

          # Extract code block delineated by ```clojure...``` pattern
          CODE_BLOCKS=$(echo "$CONTENT" | sed -n '/```clojure/,/```/p' | sed '1d;$d' | sed '/^```$/d')

          # Save code if we found any
          if [ -n "$CODE_BLOCKS" ]; then
            # Sanitize model provider and name for filename (replace non-alphanumeric with underscore)
            SANITIZED_PROVIDER=$(echo "$MODEL_PROVIDER" | sed 's/[^a-zA-Z0-9]/_/g')
            SANITIZED_MODEL=$(echo "$MODEL_NAME" | sed 's/[^a-zA-Z0-9]/_/g')

            ATTEMPT_FILE="$CODE_ATTEMPTS_DIR/attempt-$ATTEMPT_COUNTER-$SANITIZED_PROVIDER-$SANITIZED_MODEL.clj"
            echo "$CODE_BLOCKS" > "$ATTEMPT_FILE"
            echo "Saved code attempt $ATTEMPT_COUNTER to $ATTEMPT_FILE"
            ATTEMPT_COUNTER=$((ATTEMPT_COUNTER + 1))
          fi
        fi
      fi
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
SCREENSHOT_TMP_DIR="$TMP_DIR/screenshots"
mkdir -p "$SCREENSHOT_TMP_DIR"

echo "Copying screenshots..."

# Save the number of sets to a variable
SET_COUNT=$(jq -r '.screenshotSets | length' "$SESSION_JSON")
echo "Found $SET_COUNT screenshot sets"

# Extract screenshots using a manual, one-by-one approach
for ((set_idx=0; set_idx<$SET_COUNT; set_idx++)); do
  SET_NUM=$((set_idx+1))
  SET_DIR="$SCREENSHOTS_DIR/set_${SET_NUM}"
  mkdir -p "$SET_DIR"

  echo "Processing screenshot set $SET_NUM"

  # Count how many screenshots are in this set
  PATH_COUNT=$(jq -r ".screenshotSets[$set_idx] | length" "$SESSION_JSON")
  echo "Set $SET_NUM has $PATH_COUNT screenshots"

  # Process screenshots one by one
  for ((path_idx=0; path_idx<$PATH_COUNT; path_idx++)); do
    # Get a single path
    SCREENSHOT_PATH=$(jq -r ".screenshotSets[$set_idx][$path_idx]" "$SESSION_JSON")

    # Skip empty paths
    if [ -z "$SCREENSHOT_PATH" ] || [ "$SCREENSHOT_PATH" == "null" ]; then
      continue
    fi

    FILENAME=$(basename "$SCREENSHOT_PATH")
    TEMP_SCREENSHOT="$SCREENSHOT_TMP_DIR/$FILENAME"

    echo "Trying to pull screenshot: $FILENAME"

    # UNCOMMENT the line you want to use
    adb shell "su 0 cat \"$SCREENSHOT_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null
    # echo "Test data" > "$TEMP_SCREENSHOT"  # For testing

    # Check if successful
    if [ -s "$TEMP_SCREENSHOT" ]; then
      cp "$TEMP_SCREENSHOT" "$SET_DIR/$FILENAME"
      echo "Copied screenshot: $FILENAME to set $SET_NUM"
    else
      echo "Warning: Could not pull screenshot from path: $SCREENSHOT_PATH"
    fi

    # Add a small delay to prevent overwhelming the device
    # sleep 0.1
  done
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
echo "Code attempts: $CODE_ATTEMPTS_DIR"
echo "Final code: $EXPORT_DIR/current_code.clj"
echo "=============================================================="

# Clean up temp directory
rm -rf "$TMP_DIR"
