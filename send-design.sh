#!/bin/bash

# Check if a file was provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <clojure-design-file>"
    exit 1
fi

file="$1"
if [ ! -f "$file" ]; then
    echo "File not found: $file"
    exit 1
fi

# Read the file content and base64 encode it
content=$(base64 -w 0 "$file")

# First, start the app (it will be brought to front if already running)
echo "Starting or resuming the design activity..."
adb shell am start -n com.example.clojurerepl/.ClojureAppDesignActivity

# Give the app a moment to start
sleep 1

# Now send the intent directly to the activity
echo "Sending code to Design Activity..."
adb shell am start -n com.example.clojurerepl/.ClojureAppDesignActivity \
    --es design_code "$content" \
    --es encoding "base64"

echo "Design code sent successfully!"
