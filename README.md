# Clojure Android REPL & App Designer

A powerful Android application that combines a fully functional Clojure REPL with an LLM-powered app designer. It allows you to interactively develop Clojure code on Android and use AI to generate, run, and iterate on full Android UI applications directly on your device.

## Overview

This application transforms your Android device into a self-contained development environment. It features:

- **AI-Powered App Generation**: Describe an app or feature in plain English, and the built-in LLM client (supporting Claude, Gemini, OpenAI) will generate the Clojure code for it.
- **Live Execution**: Instantly run generated code in a sandboxed environment (`RenderActivity`) that supports full Android UI capabilities.
- **Interactive REPL**: A traditional Read-Eval-Print Loop for manual coding and experimentation.
- **Full Android SDK Access**: Access sensors, UI components, networking, and system services directly from Clojure.
- **Iterative Design**: Refine your apps through conversation with the AI, with instant visual feedback.

## Architecture

The application is built around a few core components that separate the design interface from the execution environment.

### Core Components

1. **ClojureAppDesignActivity** (`ClojureAppDesignActivity.java`)
   - The primary interface for the "App Designer" mode.
   - Manages the chat interface with the LLM.
   - Handles user prompts, sends them to the selected LLM provider, and processes the generated code.
   - Launches `RenderActivity` to display the result.

2. **RenderActivity** (`RenderActivity.java`)
   - The execution engine for generated Clojure code.
   - Runs in a separate process to ensure stability and isolation.
   - Dynamically compiles and loads Clojure code using a custom class loader.
   - Provides a `UiSafeViewGroup` to allow safe UI manipulation from background threads.
   - Captures screenshots of the running app to send back to the LLM for visual context.
   - Handles errors and crashes gracefully, reporting them back to the design activity.

3. **LLM Client Infrastructure** (`LLMClient.java`, `ClaudeLLMClient.java`, `GeminiLLMClient.java`, `OpenAIChatClient.java`)
   - An abstraction layer for communicating with various AI models.
   - Supports streaming responses for a responsive UI.
   - Handles API authentication and request formatting.

4. **MainActivity** (`MainActivity.java`)
   - The entry point for the traditional REPL mode.
   - Manages the basic Clojure runtime environment.

5. **AndroidClassLoaderDelegate** (`AndroidClassLoaderDelegate.java`)
   - The bridge between Clojure's dynamic bytecode generation and Android's ART runtime.
   - Converts JVM bytecode to Android DEX format on the fly using the D8 compiler.
   - Caches compiled classes to improve performance on subsequent runs.

## Features

### App Designer
- **Natural Language Interface**: Just say "Make a calculator app" or "Draw a fractal tree".
- **Multi-Model Support**: Switch between Claude, Gemini, and OpenAI models.
- **Visual Feedback**: The AI sees what you see via automated screenshots, allowing it to fix UI glitches and improve layout.
- **Session Management**: Save and resume design sessions (`DesignSessionsActivity`).

### REPL
- **Direct Evaluation**: Execute Clojure forms immediately.
- **History**: Access previous commands.
- **Output Display**: View results and standard output.

## Example Applications

The project includes example applications in the `examples/` directory that demonstrate what's possible:

- `compass_app.clj`: Sensor integration.
- `weather_app.clj`: Networking and JSON parsing.
- `vertical_layout_with_buttons.clj`: Basic UI construction.

## Building the Application

See [BUILD.md](BUILD.md) for detailed instructions on setting up the development environment and building the APK.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or create issues for bugs and feature requests.

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.
 
