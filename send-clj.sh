#!/bin/bash

# Check if a file was provided
if [ $# -ne 1 ]; then
    echo "Usage: $0 <clojure-file>"
    exit 1
fi

# Check if the file exists
if [ ! -f "$1" ]; then
    echo "Error: File $1 does not exist"
    exit 1
fi

# Create a temporary file for the base64 encoded content
TMP_FILE=$(mktemp)

# Base64 encode the file content to the temporary file
base64 "$1" > "$TMP_FILE"

# Push the temporary file to the device
adb push "$TMP_FILE" /data/local/tmp/clojure_code.b64

# Start the app with the path to the encoded file
adb shell "am start -n com.example.clojurerepl/.MainActivity --es clojure-code-file '/data/local/tmp/clojure_code.b64'"

# Clean up
rm "$TMP_FILE" 