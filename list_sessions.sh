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
SESSIONS_FILE="$ANDROID_DATA_DIR/files/sessions/design_sessions.json"
TMP_DIR="/tmp/clojure_session_export"
SESSIONS_JSON="$TMP_DIR/sessions.json"

# Create temporary directory
mkdir -p "$TMP_DIR"

# Pull the sessions file from the device
echo "Pulling sessions data from device..."
adb shell "su 0 cat $SESSIONS_FILE" > "$SESSIONS_JSON"

# Check if the sessions file was successfully pulled
if [ ! -s "$SESSIONS_JSON" ]; then
  echo "ERROR: Failed to pull sessions file from device or file is empty."
  echo "Make sure the app has created design sessions and that the path is correct."

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

# Get number of sessions
NUM_SESSIONS=$(jq '. | length' "$SESSIONS_JSON")

echo "==================================================================================="
echo "Available Design Sessions (total: $NUM_SESSIONS)"
echo "==================================================================================="
printf "%-5s %-36s %-20s %-15s %s\n" "IDX" "SESSION ID" "CREATED" "MODEL" "DESCRIPTION"
echo "-----------------------------------------------------------------------------------"

# Loop through each session and display information
for i in $(seq 0 $(($NUM_SESSIONS - 1))); do
  # Extract info for this session
  SESSION_ID=$(jq -r ".[$i].id" "$SESSIONS_JSON")
  DESCRIPTION=$(jq -r ".[$i].description" "$SESSIONS_JSON" | cut -c 1-40)
  CREATED_AT=$(jq -r ".[$i].createdAt" "$SESSIONS_JSON")
  LLM_TYPE=$(jq -r ".[$i].llmType" "$SESSIONS_JSON")
  LLM_MODEL=$(jq -r ".[$i].llmModel" "$SESSIONS_JSON")

  # Format timestamp (convert from milliseconds to seconds first)
  FORMATTED_DATE=$(date -d "@$(echo $CREATED_AT/1000 | bc)" "+%Y-%m-%d %H:%M:%S")

  # Truncate description if too long
  if [ ${#DESCRIPTION} -gt 40 ]; then
    DESCRIPTION="${DESCRIPTION}..."
  fi

  # Format model info
  MODEL_INFO="${LLM_TYPE:0:6}"

  # Print the information
  printf "%-5s %-36s %-20s %-15s %s\n" "[$i]" "$SESSION_ID" "$FORMATTED_DATE" "$MODEL_INFO" "$DESCRIPTION"
done

echo "==================================================================================="
echo "To export a session: ./export_session.sh <index>"
echo "Example: ./export_session.sh 0"
echo "==================================================================================="

# Clean up temp directory
rm -rf "$TMP_DIR"
