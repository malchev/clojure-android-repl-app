#!/bin/bash

# Script to list all available design sessions with their indices from a connected Android device
# Usage: ./list_sessions.sh

# Check if adb is installed
if ! command -v adb &> /dev/null; then
  echo "ERROR: This script requires adb to be installed"
  exit 1
fi

# Check if jq is installed
if ! command -v jq &> /dev/null; then
  echo "ERROR: This script requires jq to be installed"
  exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No Android device connected. Please connect a device and try again."
  exit 1
fi

ANDROID_PKG="com.example.clojurerepl"
ANDROID_DATA_DIR="/data/data/$ANDROID_PKG"
SESSIONS_DIR="$ANDROID_DATA_DIR/files/sessions"
TMP_DIR="/tmp/clojure_session_export"
SESSIONS_JSON="$TMP_DIR/sessions.json"

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

# Get number of sessions
NUM_SESSIONS=$(jq '. | length' "$SESSIONS_JSON")

echo "==================================================================================="
echo "Available Design Sessions (total: $NUM_SESSIONS)"
echo "==================================================================================="
printf "%-5s %-36s %-20s %-15s %-20s %s\n" "IDX" "SESSION ID" "CREATED" "MODEL" "MODELS USED" "DESCRIPTION"
echo "-----------------------------------------------------------------------------------"

# Sort sessions by createdAt (newest first) and create sorted array
SORTED_SESSIONS=$(jq -c 'sort_by(-.createdAt)' "$SESSIONS_JSON")

# Loop through each session and display information
SESSION_INDEX=0
echo "$SORTED_SESSIONS" | jq -c '.[]' | while read -r session; do
  # Extract info for this session
  SESSION_ID=$(echo "$session" | jq -r '.id')
  DESCRIPTION=$(echo "$session" | jq -r '.description' | cut -c 1-40)
  CREATED_AT=$(echo "$session" | jq -r '.createdAt')
  LLM_TYPE=$(echo "$session" | jq -r '.llmType')
  LLM_MODEL=$(echo "$session" | jq -r '.llmModel')

  # Format timestamp (convert from milliseconds to seconds first)
  FORMATTED_DATE=$(date -d "@$(echo $CREATED_AT/1000 | bc)" "+%Y-%m-%d %H:%M:%S")

  # Truncate description if too long
  if [ ${#DESCRIPTION} -gt 40 ]; then
    DESCRIPTION="${DESCRIPTION}..."
  fi

  # Format model info
  MODEL_INFO="${LLM_TYPE:0:6}"

  # Extract models used from chat history
  MODELS_USED=""
  CHAT_HISTORY=$(echo "$session" | jq -r '.chatHistory')
  if [ "$CHAT_HISTORY" != "null" ]; then
    # Get unique model providers from assistant messages
    MODELS_USED=$(echo "$session" | jq -r '.chatHistory[] | select(.role == "assistant") | .modelProvider // empty' | sort | uniq | tr '\n' ',' | sed 's/,$//')

    # If no model providers found in chat history, use the session's model info
    if [ -z "$MODELS_USED" ]; then
      if [ "$LLM_TYPE" != "null" ]; then
        MODELS_USED="$LLM_TYPE"
      else
        MODELS_USED="Unknown"
      fi
    fi
  else
    MODELS_USED="None"
  fi

  # Truncate models used if too long
  if [ ${#MODELS_USED} -gt 18 ]; then
    MODELS_USED="${MODELS_USED:0:15}..."
  fi

  # Print the information
  printf "%-5s %-36s %-20s %-15s %-20s %s\n" "[$SESSION_INDEX]" "$SESSION_ID" "$FORMATTED_DATE" "$MODEL_INFO" "$MODELS_USED" "$DESCRIPTION"
  SESSION_INDEX=$((SESSION_INDEX + 1))
done

echo "==================================================================================="
echo "To export a session: ./export_session.sh <index>"
echo "Example: ./export_session.sh 0"
echo "==================================================================================="

# Clean up temp directory
rm -rf "$TMP_DIR"
