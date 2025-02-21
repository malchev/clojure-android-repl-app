#!/bin/bash

# Exit on any error
set -e

# Remove only Clojure-specific build artifacts
rm -rf clojure
rm -rf app/libs/clojure-android-*.jar

# Clone Clojure
git clone https://github.com/clojure/clojure.git
cd clojure
git checkout clojure-1.11.1
cd ..

echo "Clojure source downloaded. Run './gradlew :clojure-android:build' to build it." 