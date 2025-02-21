#!/bin/bash
set -o errexit

pushd clojure
git am ../patches/*.patch
git checkout -b android-clojure-1.11.1-Reflector-and-DynamicClassLoader
git checkout -b android-clojure-1.11.1-Reflector HEAD~1
popd
./gradlew :clojure-android:buildClojure

pushd clojure
git checkout android-clojure-1.11.1-Reflector-and-DynamicClassLoader
popd
./gradlew :clojure-android:buildClojure
