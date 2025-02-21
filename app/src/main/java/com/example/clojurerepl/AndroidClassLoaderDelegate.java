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

public class AndroidClassLoaderDelegate {
    private static final String TAG = "ClojureREPL";
    private final Context context;
    private final ClassLoader parent;
    private final Map<String, Class<?>> classCache = new HashMap<>();
    
    public AndroidClassLoaderDelegate(Context context, ClassLoader parent) {
        this.context = context;
        this.parent = parent;
    }

    public Class<?> defineClass(String name, byte[] bytes) {
        Log.d(TAG, "AndroidClassLoaderDelegate.defineClass called for " + name);
        
        try {
            // Check cache first
            Class<?> cached = classCache.get(name);
            if (cached != null) {
                return cached;
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

            // Create class loader and load the class
            ClassLoader loader = new InMemoryDexClassLoader(buffer, parent);
            Class<?> clazz = loader.loadClass(name);
            
            // Cache the class
            classCache.put(name, clazz);
            
            // Clean up
            java.nio.file.Files.delete(dexPath);
            
            return clazz;
        } catch (Exception e) {
            Log.e(TAG, "Error defining class: " + name, e);
            throw new RuntimeException("Failed to define class: " + name, e);
        }
    }
} 