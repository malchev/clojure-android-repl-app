#!/bin/bash
tags=$(git grep "String TAG" app/ | awk -F' = ' '{print $2}' | sed 's/\"\;//g' | sed 's/\"//g')
cmdline=""
for tag in ${tags}; do
	cmdline="${cmdline} -s ${tag}";
done
for arg in "$@"; do
    cmdline="${cmdline} -s ${arg}"
done
echo adb logcat -s DEBUG -s AndroidRuntime -s ClojureApp ${cmdline}
