# RenderActivity Implementation Details

`RenderActivity` is a specialized Android `Activity` designed to dynamically compile and execute Clojure code within an Android environment. It serves as the execution context for code sent from a REPL or a parent activity, providing a bridge between the dynamic nature of Clojure and the Android application lifecycle.

## Core Responsibilities

1.  **Clojure Environment Initialization**: Setting up the necessary Clojure runtime, class loaders, and namespaces.
2.  **Code Execution**: Compiling and evaluating Clojure code strings.
3.  **UI Management**: Providing a container for UI elements created by the Clojure code and handling UI updates safely.
4.  **Result & Error Handling**: Capturing execution results, handling exceptions, and communicating back to the caller.
5.  **Screenshot Capture**: capturing the state of the UI, including OpenGL surfaces.
6.  **Process Lifecycle**: Managing its own process lifecycle to ensure clean state for each execution.

## Detailed Implementation Breakdown

### 1. Initialization & Setup (`onCreate`)

When the activity starts, it performs a sequence of initialization steps:

*   **Crash Handling**: Sets a default uncaught exception handler to catch crashes, log them, and attempt to report the error back to the parent activity before killing the process.
*   **PID Broadcasting**: Broadcasts its process ID via `ACTION_REMOTE_PID` intent, allowing the parent to monitor its lifecycle.
*   **UI Setup**: Initializes a `content_layout` (LinearLayout) which serves as the root for any UI created by the Clojure code. It also adds a `timingView` to display performance metrics.
*   **Intent Parsing**: Extracts parameters from the launching Intent:
    *   `EXTRA_CODE`: The Clojure code to execute.
    *   `EXTRA_SESSION_ID`, `EXTRA_MESSAGE_INDEX`, `EXTRA_ITERATION`: Metadata for the execution session.
    *   `EXTRA_ENABLE_SCREENSHOTS`: Flag to enable/disable automatic screenshots.
    *   `EXTRA_AUTO_RETURN_ON_ERROR`: Flag to determine if errors should close the activity immediately.
*   **Clojure Runtime Init**:
    *   Calculates a hash of the code for caching purposes.
    *   Initializes `clojure.lang.RT`.
    *   Sets up a `DynamicClassLoader`.
    *   Initializes core Vars (`*ns*`, `*context*`, `*content-layout*`, `*cache-dir*`).
    *   Initializes the `user` namespace.

### 2. Clojure Environment

The activity sets up a specific environment for the executing code:

*   **Class Loading**: Uses `DynamicClassLoader` with a custom `AndroidClassLoaderDelegate` and `BytecodeCache`. This allows dynamically generated classes to be cached (as DEX files) and reused, speeding up subsequent executions of the same code.
*   **Vars**:
    *   `*context*`: Bound to the `RenderActivity` instance.
    *   `*content-layout*`: Bound to a `UiSafeViewGroup` wrapper around the actual layout.
    *   `*cache-dir*`: Bound to the application's cache directory.
*   **Namespace**: Code executes in the `user` namespace, with `clojure.core` referred.

### 3. Code Execution (`renderCode` & `compileAndExecute`)

The execution flow is as follows:

1.  **Bytecode Cache Check**: Checks if a compiled DEX cache exists for the code hash. If so, it reuses the class loader from the cache.
2.  **Delegate Setup**: Configures `AndroidClassLoaderDelegate` to handle class definitions.
3.  **Thread Bindings**: Pushes thread bindings for `*context*` and `*content-layout*`.
4.  **Evaluation Loop**:
    *   Reads the code using `LineNumberingPushbackReader`.
    *   Evaluates each form using `Compiler.eval()`.
    *   Captures the result of the last evaluated form.
5.  **Main Function**: Checks if a `-main` function was defined and invokes it if present.
6.  **Manifest Generation**: If running for the first time (no cache), generates a manifest of created classes for the `BytecodeCache`.

### 4. UI Safety (`UiSafeViewGroup`)

To allow Clojure code (which might run on background threads) to manipulate the UI without crashing, `RenderActivity` provides a `UiSafeViewGroup` wrapper. This wrapper intercepts calls like `addView` and `removeAllViews` and ensures they are executed on the main Android UI thread using `runOnUiThread`.

### 5. Screenshot Capability

The activity includes a robust screenshot mechanism:

*   **Triggering**: Screenshots are taken automatically after rendering and on user interactions (touch events).
*   **GLSurfaceView Support**: Standard Android screenshot methods often fail to capture OpenGL content. `RenderActivity` specifically handles this by:
    *   Finding all `GLSurfaceView` instances in the hierarchy.
    *   Using `PixelCopy` (API 26+) to asynchronously capture the GL content.
    *   Compositing the GL content onto the standard view hierarchy bitmap.
*   **Throttling**: Prevents taking screenshots too frequently via `MIN_SCREENSHOT_INTERVAL_MS`.

### 6. Error Handling

Errors are handled at multiple levels:

*   **Compilation/Runtime Errors**: Caught during `compileAndExecute`.
*   **Uncaught Exceptions**: Caught by the global handler.
*   **Reporting**: Errors are formatted (including "Caused by" chains) and either displayed in the UI (using a red error view) or returned to the parent activity via Intent, depending on `EXTRA_AUTO_RETURN_ON_ERROR`.

### 7. Lifecycle & Process Management

*   **Watchdog**: Binds to `RenderActivityWatchdogService` to ensure the process is monitored.
*   **Cleanup**: On `onDestroy` or crash, the activity explicitly kills its own process (`android.os.Process.killProcess`). This is a crucial design choice to ensure that the Clojure runtime is completely reset for the next execution, avoiding state pollution between runs.
*   **Back Press**: Handles the back button to return results (screenshots, timings, errors) to the parent activity before destroying itself.
