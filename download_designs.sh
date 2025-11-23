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
# Check if the directory exists and has files
if ! adb shell "ls $TARGET_DIR > /dev/null 2>&1"; then
    echo "Error: No design code directory found at $TARGET_DIR"
    exit 1
fi

# Create the output directory if it doesn't exist
OUTPUT_DIR="./downloaded_designs"
mkdir -p "$OUTPUT_DIR"

# Pull the directory
echo "Downloading all designs from $TARGET_DIR to $OUTPUT_DIR..."
adb pull "$TARGET_DIR/." "$OUTPUT_DIR/"

if [ $? -eq 0 ]; then
    echo "Success! All designs saved to $OUTPUT_DIR"
    echo "Files downloaded:"
    ls -1 "$OUTPUT_DIR"
else
    echo "Error: Failed to download designs"
    exit 1
fi
