package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.origin.Origin;
import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

public class AndroidClassLoaderDelegate {
    private static final String TAG = "ClojureREPL";
    private final Context context;
    private final ClassLoader parent;
    private final Map<String, Class<?>> classCache;
    private final List<ByteBuffer> dexBuffers;
    private ClassLoader currentLoader;
    
    public AndroidClassLoaderDelegate(Context context, ClassLoader parent) {
        this.context = context;
        this.parent = parent;
        this.classCache = new ConcurrentHashMap<>();
        this.dexBuffers = new ArrayList<>();
        this.currentLoader = parent;
    }

    private void updateClassLoader(ByteBuffer newDex) {
        dexBuffers.add(newDex);
        ByteBuffer[] buffers = dexBuffers.toArray(new ByteBuffer[0]);
        currentLoader = new InMemoryDexClassLoader(buffers, parent);
        Thread.currentThread().setContextClassLoader(currentLoader);
    }

    public Class<?> defineClass(String name, byte[] bytes) {
        Log.d(TAG, "AndroidClassLoaderDelegate.defineClass called for " + name);
        
        try {
            // Check cache first
            Class<?> cached = classCache.get(name);
            if (cached != null) {
                Log.d(TAG, "Returning cached class for: " + name);
                return cached;
            }

            // Try loading from current loader first
            try {
                Class<?> existing = currentLoader.loadClass(name);
                if (existing != null) {
                    Log.d(TAG, "Found class in current loader: " + name);
                    classCache.put(name, existing);
                    return existing;
                }
            } catch (ClassNotFoundException ignored) {
                // Expected - continue with defining the class
            }

            // Convert JVM bytecode to DEX using D8
            D8Command command = D8Command.builder()
                .addClassProgramData(bytes, Origin.unknown())
                .setMode(CompilationMode.DEBUG)
                .setOutput(context.getCacheDir().toPath(), OutputMode.DexIndexed)
                .build();
            
            D8.run(command);

            // Read the generated DEX file
            java.nio.file.Path dexPath = context.getCacheDir().toPath().resolve("classes.dex");
            byte[] dexBytes = java.nio.file.Files.readAllBytes(dexPath);
            
            // Create a ByteBuffer containing the DEX bytes
            ByteBuffer buffer = ByteBuffer.allocate(dexBytes.length);
            buffer.put(dexBytes);
            buffer.position(0);

            // Update class loader with new DEX
            updateClassLoader(buffer);
            
            // Load the class from the updated loader
            Class<?> clazz = currentLoader.loadClass(name);
            
            // Cache the class
            classCache.put(name, clazz);
            Log.d(TAG, "Successfully defined class: " + name);
            
            // Clean up
            java.nio.file.Files.delete(dexPath);
            
            return clazz;
        } catch (Exception e) {
            Log.e(TAG, "Error defining class: " + name, e);
            throw new RuntimeException("Failed to define class: " + name, e);
        }
    }
} 