# Build Instructions

This document describes how to set up your development environment and build the app on macOS or Linux.

## Prerequisites

### For macOS

1. **Android Studio**
   - Download and install from https://developer.android.com/studio
   - Add to your shell profile:
     ```bash
     echo 'export ANDROID_HOME=~/Library/Android/sdk' >> ~/.zshrc
     ```

2. **Gradle**
   ```bash
   brew install gradle
   ```

3. **Android Platform Tools**
   - Download from https://developer.android.com/tools/releases/platform-tools
   - This includes essential tools like `adb`

4. **Java 17**
   ```bash
   brew install openjdk@17
   echo 'export JAVA_HOME="$(brew --prefix openjdk@17)"' >> ~/.zshrc
   echo 'export PATH="$JAVA_HOME:$PATH"' >> ~/.zshrc
   source ~/.zshrc  # Apply changes
   ```
   - Add to `gradle.properties` or `~/.gradle/gradle.properties`:
     ```properties
     org.gradle.java.home=/opt/homebrew/opt/openjdk@17
     ```

5. **Maven**
   ```bash
   brew install maven
   ```

### For Linux/ChromeOS

1. **Android Studio**
   - Download from https://developer.android.com/studio
   - ChromeOS:
        - download and install Studio:
        ```
        sudo dpkg -i ~/android-studio-2024.2.2.14-cros.deb # or whatever the download is
        /opt/android-studio/bin/studio.sh
        # install the Android SDK, accept the licenses
        echo 'export ANDROID_HOME=~/Android/Sdk' >> ~/.bashrc # or wherever it's installed
        ```
   - Linux:
        ```
        tar xzvf ~/Downloads/android-studio-2024.2.2.14-linux.tar.gz
        ~/Downloads/android-studio-2024.2.2.14-linux/android-studio/bin/studio.sh
        # install standard configuration and accept the licenses
        echo 'export ANDROID_HOME=~/Android/Sdk' >> ~/.bashrc # or wherever it's installed
        source ~/.bashrc  # Apply changes

        ```

2. **Gradle**
   ```bash
   sudo apt install gradle
   ```

3. **Android Platform Tools**
   ```bash
   sudo apt install adb # just need adb for this
   ```
   - Alternatively, download from https://developer.android.com/tools/releases/platform-tools

4. **Java 17**
   ```bash
   sudo apt install openjdk-17-jdk
   ```

   - Now either override calls to java/javac in your shell for everyone:

   ```bash
   echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
   echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
   source ~/.bashrc  # Apply changes
   ```

   Or use (on Ubuntu) update-java-alternatives:

   ```bash
   sudo update-java-alternatives -s java-1.17.0-openjdk-amd64
   ```

   - Add to `gradle.properties` or `~/.gradle/gradle.properties`:
   ```properties
   org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
   ```

5. **Maven**
   ```bash
   sudo apt install maven
   ```

## Building the App

After installing all prerequisites, follow these steps to build the app:

1. Download Clojure dependencies:
   ```bash
   ./scripts/download_clojure.sh
   ```

2. Patch and build Clojure:
   ```bash
   ./scripts/patch-and-build-clojure.sh
   ```

3. Build the debug APK:
   ```bash
   ./gradlew clean assembleDebug
   ```

The built APK will be available in the `app/build/outputs/apk/debug/` directory.
