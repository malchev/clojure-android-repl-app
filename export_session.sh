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

# Create a directory for attachments
ATTACHMENTS_BASE_DIR="$EXPORT_DIR/attachments"
mkdir -p "$ATTACHMENTS_BASE_DIR"

# Define temp dir for screenshots
SCREENSHOT_TMP_DIR="$TMP_DIR/screenshots"
mkdir -p "$SCREENSHOT_TMP_DIR"

# Initialize counters
ATTEMPT_COUNTER=1
MSG_INDEX=0
ATTEMPT_FILE=""

echo "Processing chat history..."

# Process chat history messages
while read -r message; do
  MSG_INDEX=$((MSG_INDEX + 1))

  ROLE=$(echo "$message" | jq -r '.role')
  CONTENT=$(echo "$message" | jq -r '.content')
  MODEL_PROVIDER=$(echo "$message" | jq -r '.modelProvider // empty')
  MODEL_NAME=$(echo "$message" | jq -r '.modelName // empty')

  # ---------------------------------------------------------
  # 1. Update Chat History File
  # ---------------------------------------------------------

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

  # ---------------------------------------------------------
  # 2. Handle Assistant Code Extraction
  # ---------------------------------------------------------
  if [ "$ROLE" = "assistant" ]; then
    # Try to get code from extractedCode field first
    EXTRACTED_CODE=$(echo "$message" | jq -r '.extractedCode // empty')

    # If empty, try codeExtractionResult.code
    if [ -z "$EXTRACTED_CODE" ]; then
      EXTRACTED_CODE=$(echo "$message" | jq -r '.codeExtractionResult.code // empty')
    fi

    # If we found extracted code, save it
    CODE_FOUND=false
    if [ -n "$EXTRACTED_CODE" ]; then
         CODE_FOUND=true
    else
        # Fallback to parsing content if no extracted code found
        if echo "$CONTENT" | grep -q '```clojure\|```'; then
          # Extract code block delineated by ```clojure...``` pattern
          CODE_BLOCKS=$(echo "$CONTENT" | sed -n '/```clojure/,/```/p' | sed '1d;$d' | sed '/^```$/d')
          if [ -n "$CODE_BLOCKS" ]; then
            EXTRACTED_CODE="$CODE_BLOCKS"
            CODE_FOUND=true
          fi
        fi
    fi

    if [ "$CODE_FOUND" = true ]; then
         echo "Found extracted code in message $MSG_INDEX (iteration $ATTEMPT_COUNTER)..."

         # Sanitize model provider and name for filename
         SANITIZED_PROVIDER=$(echo "$MODEL_PROVIDER" | sed 's/[^a-zA-Z0-9]/_/g')
         SANITIZED_MODEL=$(echo "$MODEL_NAME" | sed 's/[^a-zA-Z0-9]/_/g')

         # Filename format: attempt-m[msg]-it[iter]-[model].clj
         # We use provider_model for [model] part
         ATTEMPT_FILE="$CODE_ATTEMPTS_DIR/attempt-m${MSG_INDEX}-it${ATTEMPT_COUNTER}-${SANITIZED_PROVIDER}_${SANITIZED_MODEL}.clj"

         echo "$EXTRACTED_CODE" > "$ATTEMPT_FILE"
         echo "Saved code attempt to $ATTEMPT_FILE"

         # Increment iteration counter only when code is found
         ATTEMPT_COUNTER=$((ATTEMPT_COUNTER + 1))
    fi
  fi

  # ---------------------------------------------------------
  # 3. Handle User Attachments (Screenshots & Logcat)
  # ---------------------------------------------------------
  if [ "$ROLE" = "user" ]; then
      IMAGE_FILES=$(echo "$message" | jq -r '.imageFiles[]? // empty')
      LOGCAT_CONTENT=$(echo "$message" | jq -r '.logcat // empty')

      if [ -n "$IMAGE_FILES" ] || [ -n "$LOGCAT_CONTENT" ]; then
          MSG_DIR="$ATTACHMENTS_BASE_DIR/msg_$MSG_INDEX"
          mkdir -p "$MSG_DIR"

          # Save Logcat
          if [ -n "$LOGCAT_CONTENT" ]; then
              echo "$LOGCAT_CONTENT" > "$MSG_DIR/logcat"
              echo "Saved logcat to msg_$MSG_INDEX/logcat"
          fi

          # Save Screenshots
          if [ -n "$IMAGE_FILES" ]; then
              for SCREENSHOT_PATH in $IMAGE_FILES; do
                # Skip empty paths
                if [ -z "$SCREENSHOT_PATH" ] || [ "$SCREENSHOT_PATH" == "null" ]; then
                  continue
                fi

                FILENAME=$(basename "$SCREENSHOT_PATH")
                TEMP_SCREENSHOT="$SCREENSHOT_TMP_DIR/$FILENAME"

                echo "Trying to pull screenshot: $FILENAME"

                # Try to pull with su first
                adb shell "su 0 cat \"$SCREENSHOT_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null < /dev/null

                # If that failed, try without su
                if [ ! -s "$TEMP_SCREENSHOT" ]; then
                    adb shell "cat \"$SCREENSHOT_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null < /dev/null
                fi

                # If that failed, try with run-as
                if [ ! -s "$TEMP_SCREENSHOT" ]; then
                    RELATIVE_PATH=$(echo "$SCREENSHOT_PATH" | sed "s|$ANDROID_DATA_DIR/||")
                    adb shell "run-as $ANDROID_PKG cat \"$RELATIVE_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null < /dev/null
                fi

                # Check if successful
                if [ -s "$TEMP_SCREENSHOT" ]; then
                  cp "$TEMP_SCREENSHOT" "$MSG_DIR/$FILENAME"
                  echo "Copied screenshot: $FILENAME to msg_$MSG_INDEX"
                else
                  echo "Warning: Could not pull screenshot from path: $SCREENSHOT_PATH"
                fi
              done
          fi
      fi
  fi

done < <(jq -c '.chatHistory[]' "$SESSION_JSON")

echo "Pulling all raw screenshots for this session..."
# Define the screenshot directory on the device
DEVICE_SCREENSHOT_DIR="$ANDROID_DATA_DIR/cache/screenshots"

# List files matching the session ID pattern
# Pattern: session_[UUID]_*.png
FILE_PATTERN="session_${SESSION_ID}_*.png"

# Try to list files using su
RAW_FILES=$(adb shell "su 0 ls $DEVICE_SCREENSHOT_DIR/$FILE_PATTERN 2>/dev/null")

# If empty, try without su
if [ -z "$RAW_FILES" ]; then
  RAW_FILES=$(adb shell "ls $DEVICE_SCREENSHOT_DIR/$FILE_PATTERN 2>/dev/null")
fi

# If still empty, try run-as
if [ -z "$RAW_FILES" ]; then
  RAW_FILES=$(adb shell "run-as $ANDROID_PKG ls cache/screenshots/$FILE_PATTERN 2>/dev/null")
fi

if [ -n "$RAW_FILES" ]; then
  for FILE_PATH in $RAW_FILES; do
    # Handle potential "No such file" errors in output if ls failed weirdly
    if [[ "$FILE_PATH" == *"No such file"* ]]; then continue; fi

    FILENAME=$(basename "$FILE_PATH")
    TEMP_SCREENSHOT="$SCREENSHOT_TMP_DIR/$FILENAME"

    # Extract iteration number from filename
    # Format: session_[id]_iter_[num]_[timestamp].png
    ITER_NUM=$(echo "$FILENAME" | sed -n 's/.*_iter_\([0-9]*\)_.*\.png/\1/p')

    if [ -n "$ITER_NUM" ]; then
        TARGET_DIR="$EXPORT_DIR/screenshots_iter_$ITER_NUM"
    else
        TARGET_DIR="$EXPORT_DIR/screenshots_not_indexed"
    fi
    mkdir -p "$TARGET_DIR"

    if [ -f "$TEMP_SCREENSHOT" ]; then
       echo "Already have $FILENAME, copying to $(basename "$TARGET_DIR")..."
       cp "$TEMP_SCREENSHOT" "$TARGET_DIR/$FILENAME"
    else
       echo "Pulling screenshot: $FILENAME to $(basename "$TARGET_DIR")"

       # Try su
       adb shell "su 0 cat \"$FILE_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null

       # Try normal
       if [ ! -s "$TEMP_SCREENSHOT" ]; then
         adb shell "cat \"$FILE_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null
       fi

       # Try run-as
       if [ ! -s "$TEMP_SCREENSHOT" ]; then
         # Construct relative path for run-as
         REL_PATH=$(echo "$FILE_PATH" | sed "s|$ANDROID_DATA_DIR/||")
         adb shell "run-as $ANDROID_PKG cat \"$REL_PATH\"" > "$TEMP_SCREENSHOT" 2>/dev/null
       fi

       if [ -s "$TEMP_SCREENSHOT" ]; then
         cp "$TEMP_SCREENSHOT" "$TARGET_DIR/$FILENAME"
       else
         echo "Failed to pull $FILENAME"
       fi
    fi
  done
else
  echo "No raw screenshots found matching pattern."
fi

# Extract and save the current code
if [ -n "$ATTEMPT_FILE" ]; then
    cp "$ATTEMPT_FILE" "$EXPORT_DIR/current_code.clj"
else
    CODE=$(jq -r '.currentCode' "$SESSION_JSON")
    if [ -n "$CODE" -a "$CODE" != "null" ]; then
        echo "$CODE" > "$EXPORT_DIR/current_code.clj"
    fi
fi

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
echo "Attachments: $ATTACHMENTS_BASE_DIR"
if [ -f "$EXPORT_DIR/current_code.clj" ]; then
    echo "Code attempts: $CODE_ATTEMPTS_DIR"
    echo "Final code: $EXPORT_DIR/current_code.clj"
else
    echo "This chat session has not produced any code."
fi
echo "=============================================================="

# Clean up temp directory
rm -rf "$TMP_DIR"
