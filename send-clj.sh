#!/bin/bash

# Check if a file was provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <clojure-file>"
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
echo "Starting or resuming the app..."
adb shell am start -n com.example.clojurerepl/.MainActivity

# Give the app a moment to start
sleep 1

# Now send the broadcast with the code
echo "Sending code to evaluate..."
adb shell am broadcast \
    -a com.example.clojurerepl.EVAL_CODE \
    -n com.example.clojurerepl/.ClojureCodeReceiver \
    --es code "$content" \
    --es encoding "base64"

# If no running instance, start the activity
if [ $? -ne 0 ]; then
    adb shell "am start -n com.example.clojurerepl/.MainActivity --es clojure-code-file '/data/local/tmp/clojure_code.b64'"
fi 