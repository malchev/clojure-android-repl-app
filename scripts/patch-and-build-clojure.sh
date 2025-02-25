#!/bin/bash
set -o errexit

branch=android-clojure-1.11.1-Reflector-and-DynamicClassLoader
pushd clojure>/dev/null
if git rev-parse --verify "$branch" >/dev/null 2>&1; then
	cat<<EOF
Branch $branch exists, looks like this script has already run.  If you want to
rebuild, first run ./scripts/download_clojure.sh again, then run this script
again.
EOF
	popd>/dev/null
	exit 1;
fi
popd>/dev/null

./gradlew :clojure-android:buildClojure

pushd clojure
git am ../patches/*.patch
git checkout -b $branch
popd
./gradlew :clojure-android:buildClojure
