package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.origin.Origin;
import dalvik.system.InMemoryDexClassLoader;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;

public class AndroidClassLoaderDelegate {
    private static final String TAG = "ClojureREPLClassCallback";

    private final Context context;
    private final ClassLoader parent;
    private final BytecodeCache bytecodeCache;
    private boolean hasCompleteCache;
    private String codeHash;

    private final List<ByteBuffer> dexBuffers;
    private ClassLoader currentLoader;
    private List<String> generatedClasses = new ArrayList<>();

    public AndroidClassLoaderDelegate(Context context, ClassLoader parent,
            BytecodeCache bytecodeCache,
            boolean hasCompleteCache,
            String codeHash) {
        super(); // Explicitly invoke Object constructor
        this.context = context;
        this.parent = parent;
        this.dexBuffers = new ArrayList<>();
        this.currentLoader = parent;
        this.bytecodeCache = bytecodeCache;
        this.hasCompleteCache = hasCompleteCache;
        this.codeHash = codeHash;
    }

    private void updateClassLoader(ByteBuffer newDex) {
        dexBuffers.add(newDex);
        ByteBuffer[] buffers = dexBuffers.toArray(new ByteBuffer[0]);
        currentLoader = new InMemoryDexClassLoader(buffers, parent);
        Thread.currentThread().setContextClassLoader(currentLoader);
    }

    public Class<?> defineClass(String name, byte[] bytes) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();

        try {
            // First check if the class is already defined in current classloader
            try {
                Class<?> existing = contextLoader.loadClass(name);
                if (existing != null) {
                    Log.d(TAG, "Found class in current loader: " + name);
                    return existing;
                }
            } catch (ClassNotFoundException ignored) {
                if (hasCompleteCache) {
                    Log.w(TAG, "Class not found in current loader (type " +
                            contextLoader.getClass().getName() + "): " + name);
                }
                // Expected - will proceed with defining the class if we do not have a
                // complete cache already.
            }

            File dexPath = bytecodeCache.createPathToDexFile(name);

            // Proceed with normal class definition...
            // Convert JVM bytecode to DEX using D8
            D8Command.Builder builder = D8Command.builder()
                    .addClassProgramData(bytes, Origin.unknown())
                    .setMode(CompilationMode.DEBUG)
                    .setOutput(dexPath.toPath(), OutputMode.DexIndexed)
                    .setEnableDesugaring(false);

            D8Command command = builder.build();

            D8.run(command);

            // Read the generated DEX file
            byte[] dexBytes = Files.readAllBytes(new File(dexPath, "classes.dex").toPath());

            // Save the class name. We will use the list of generated classes in the
            // .manifest file later.
            generatedClasses.add(name);

            Log.d(TAG, "Captured DEX for class: " + name + ", size: " +
                    dexBytes.length + " bytes (total classes: " +
                    generatedClasses.size() + ")");

            // Create a ByteBuffer containing the DEX bytes
            ByteBuffer buffer = ByteBuffer.allocate(dexBytes.length);
            buffer.put(dexBytes);
            buffer.position(0);

            // Update class loader with new DEX
            updateClassLoader(buffer);

            // Load the class from the updated loader
            Class<?> clazz = currentLoader.loadClass(name);

            // Cache the class
            Log.d(TAG, "Successfully defined class: " + name);

            return clazz;
        } catch (Exception e) {
            Log.e(TAG, "Error defining class: " + name, e);
            throw new RuntimeException("Failed to define class: " + name, e);
        }
    }

    // Add new method to retrieve generated classes
    public List<String> getGeneratedClasses() {
        return generatedClasses;
    }
}
