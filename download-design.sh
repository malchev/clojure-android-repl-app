#!/bin/bash

# Ensure ADB is available
if ! command -v adb &> /dev/null; then
    echo "Error: adb is not installed or not in PATH"
    exit 1
fi

# Check for connected devices
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "Error: No Android devices connected"
    exit 1
fi

# Define target directory on device
TARGET_DIR="/storage/emulated/0/Android/data/com.example.clojurerepl/files/code"

# Check if the file exists
if ! adb shell "[ -f $TARGET_DIR/latest_design.clj ]"; then
    echo "Error: No design code found. Please save code from the app first."
    echo "Open the app, go to the menu and select 'Save Code to File'"
    exit 1
fi

# Create the output directory if it doesn't exist
mkdir -p ./downloaded-code

# Generate timestamp for filename
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="./downloaded-code/design_${TIMESTAMP}.clj"

# Pull the file
echo "Downloading latest design code..."
adb pull "$TARGET_DIR/latest_design.clj" "$OUTPUT_FILE"

if [ $? -eq 0 ]; then
    echo "Success! Code saved to $OUTPUT_FILE"
    echo "Content preview:"
    echo "----------------------------------------"
    head -n 10 "$OUTPUT_FILE"
    echo "... (truncated)"
    echo "----------------------------------------"
else
    echo "Error: Failed to download code"
    exit 1
fi
