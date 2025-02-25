# Clojure Android REPL App

A powerful Android application that provides a fully functional Clojure REPL environment directly on Android devices, allowing for interactive Clojure development and experimentation on mobile platforms with full access to the Android SDK APIs.

## Overview

This application enables running Clojure code directly on Android devices by implementing a custom REPL (Read-Eval-Print Loop) environment. It features:

- Live Clojure code evaluation on Android
- Full access to Android SDK APIs (sensors, networking, UI, etc.)
- Custom class loading system for Android compatibility
- Example applications demonstrating various Android features and APIs

### Why Clojure on Android?

Unlike other mobile development approaches like ReactNative, this app leverages Clojure's native JVM integration for seamless Android development:

- **Direct SDK Access**: As a JVM language, Clojure can directly call Android SDK APIs without requiring bridge adapters or JavaScript interfaces. This is in contrast to ReactNative, which needs to maintain a complex bridge layer between JavaScript and native code.

- **Native Performance**: Clojure code is compiled to Dalvik bytecode, just like Java code on Android, allowing it to benefit from all the optimizations provided by ART (Android Runtime). This includes ahead-of-time compilation, profile-guided optimizations, and efficient garbage collection. Unlike ReactNative's JavaScript bridge, Clojure code runs natively with the same performance characteristics as Java applications.

- **Elegant Code**: Clojure's functional programming paradigm and rich standard library allow developers to write more expressive and concise code compared to Java. What might take dozens of lines in Java can often be expressed in just a few lines of elegant Clojure code.

- **Live Development**: The REPL environment enables real-time code evaluation and modification, similar to ReactNative's hot reloading but with the added power of direct SDK access.

## Architecture

### Core Components

1. **MainActivity** (`MainActivity.java`)
   - The central component that manages the REPL environment and UI
   - Initializes the Clojure runtime environment with custom class loading
   - Handles code evaluation and result display
   - Manages dynamic UI updates and Android context integration
   - Provides thread-safe bindings for Clojure vars and namespace management
   - Sets up the custom class loader and Android integration
   - Handles intent-based code evaluation requests

2. **AndroidClassLoaderDelegate** (`AndroidClassLoaderDelegate.java`)
   - Handles dynamic class loading for Clojure on Android
   - Converts JVM bytecode to DEX format using D8 compiler
   - Manages an in-memory DEX class loader for runtime-generated classes
   - Implements caching for improved performance
   - Ensures proper class loading hierarchy and context
   - Provides real-time class definition capabilities

3. **ClojureCodeReceiver** (`ClojureCodeReceiver.java`)
   - Broadcast receiver for external Clojure code evaluation
   - Enables inter-process communication for code execution
   - Routes evaluation requests to MainActivity via intents
   - Facilitates integration with external development tools
   - Handles the `com.example.clojurerepl.EVAL_CODE` action

4. **ClojureClassLoader** (`ClojureClassLoader.java`)
   - Custom class loader specifically for handling Clojure classes
   - Intercepts loading of critical Clojure classes (e.g., clojure.lang.Reflector)
   - Provides special handling for reflection-related classes
   - Enables seamless integration between Clojure and Android's class loading system
   - Maintains proper parent-child class loader relationships

5. **REPL Environment**
   - Provides a complete Clojure runtime environment
   - Enables dynamic code evaluation
   - Supports both synchronous and asynchronous code execution
   - Maintains session state for continuous development

6. **Android Integration**
   - Direct access to all Android SDK APIs through Clojure
   - Hardware sensor access (GPS, accelerometer, compass, etc.)
   - Network and system services integration
   - UI component manipulation in real-time
   - Event handling and lifecycle management

## Example Applications

The project includes several example applications in the `test/clojure/` directory:

- `compass_app.clj`: Demonstrates sensor integration and real-time UI updates
- `weather_app.clj`: Shows network requests and data visualization
- `vertical_layout_with_buttons.clj`: Basic UI layout example
- `button_test.clj`: Event handling demonstration
- `layout_test.clj`: Advanced layout manipulation
- `feature_test.clj`: Various Android feature integrations

To run the examples:

1. Launch the application on your Android device
2. Use the provided `send-clj.sh` script to send and evaluate Clojure code:
   ```bash
   ./send-clj.sh test/clojure/compass_app.clj
   ```

The script will:
- Start the app if it's not running
- Base64 encode the Clojure file contents
- Send the code to the app via an Android broadcast intent
- The app will evaluate the code and display the results

## Building the Application

For detailed build instructions, including prerequisites and step-by-step procedures for both macOS and Linux, please refer to [BUILD.md](BUILD.md).

Key build steps include:
1. Installing prerequisites (Java 17, Android Studio, Gradle, etc.)
2. Downloading Clojure dependencies
3. Patching and building Clojure for Android
4. Building the debug APK

## Development Workflow

1. **Setup**
   - Follow the build instructions in BUILD.md
   - Connect your Android device or start an emulator
   - Install the debug APK using:
     ```bash
     ./gradlew installDebug
     ```
     This will build and install the app directly to your connected device or emulator

2. **REPL Connection**
   - Use the provided `send-clj.sh` script to connect to the REPL
   - Start experimenting with Clojure code directly on the device

3. **Android Development**
   - Access any Android SDK API directly from Clojure
   - Create and modify UI components in real-time
   - Interact with device sensors and system services
   - Test changes instantly without rebuilding the app
   - Use the example files as references for common patterns

## Contributing

Contributions are welcome! Please feel free to submit pull requests or create issues for bugs and feature requests.

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details. 