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
import java.nio.file.Files;
import java.nio.file.Path;

public class AndroidClassLoaderDelegate {
    private static final String TAG = "ClojureREPL";
    private final Context context;
    private final ClassLoader parent;
    private final Map<String, Class<?>> classCache;
    private final List<ByteBuffer> dexBuffers;
    private ClassLoader currentLoader;
    
    // Reference to current dex file being built - for caching purposes
    private static byte[] currentDexBytes = null;
    private static boolean captureDex = false;
    
    // Add fields to track all DEX files
    private static List<byte[]> capturedDexFiles = new ArrayList<>();
    
    // Add a map to track which DEX file contains which classes
    private static Map<String, Integer> dexClassMap = new HashMap<>();
    
    public AndroidClassLoaderDelegate(Context context, ClassLoader parent) {
        this.context = context;
        this.parent = parent;
        this.classCache = new ConcurrentHashMap<>();
        this.dexBuffers = new ArrayList<>();
        this.currentLoader = parent;
    }
    
    public static void startCapturingDex() {
        captureDex = true;
        currentDexBytes = null;
        capturedDexFiles.clear();
        dexClassMap.clear();
    }
    
    public static List<byte[]> getAllCapturedDex() {
        if (capturedDexFiles.isEmpty()) {
            Log.w(TAG, "No DEX files were captured!");
            return null;
        }
        
        Log.d(TAG, "Returning " + capturedDexFiles.size() + " separate DEX files");
        List<byte[]> result = new ArrayList<>(capturedDexFiles);
        
        // Clear for next time
        capturedDexFiles.clear();
        dexClassMap.clear();
        return result;
    }

    // Keep old method for backward compatibility but make it call the new one
    public static byte[] getAndClearCapturedDex() {
        List<byte[]> allDexes = getAllCapturedDex();
        if (allDexes == null || allDexes.isEmpty()) {
            return null;
        }
        
        // For backward compatibility, return the one containing -main or the largest
        for (int i = 0; i < allDexes.size(); i++) {
            byte[] dex = allDexes.get(i);
            String className = null;
            
            // Look for the class name in our map
            for (Map.Entry<String, Integer> entry : dexClassMap.entrySet()) {
                if (entry.getValue() == i && entry.getKey().contains("-main")) {
                    return dex;
                }
            }
        }
        
        // Return the largest if -main not found
        byte[] largest = null;
        int maxSize = 0;
        for (byte[] dex : allDexes) {
            if (dex.length > maxSize) {
                maxSize = dex.length;
                largest = dex;
            }
        }
        return largest;
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
                // Expected - will proceed with defining the class
            }
            
            // If not found, collect this class if we're in collection mode
            if (contextLoader instanceof ClassBytecodeCollector) {
                Log.d(TAG, "Collector is a ClassBytecodeCollector, class is " + contextLoader.getClass().getName());
                ClassBytecodeCollector collector = (ClassBytecodeCollector)contextLoader;
                if (collector.isCollectingClasses()) {
                    collector.collectClass(name, bytes);
                    Log.d(TAG, "Collected class bytecode for: " + name);
                }
                else {
                    Log.d(TAG, "Not collecting class: " + name + " because collector is not collecting classes");
                }
            }
            else {
                Log.d(TAG, "Not collecting class: " + name + " because collector is not a ClassBytecodeCollector, it is a " + contextLoader.getClass().getName());
            }
            
            // Proceed with normal class definition...
            // Convert JVM bytecode to DEX using D8
            D8Command command = D8Command.builder()
                .addClassProgramData(bytes, Origin.unknown())
                .setMode(CompilationMode.DEBUG)
                .setOutput(context.getCacheDir().toPath(), OutputMode.DexIndexed)
                .build();
            
            D8.run(command);

            // Read the generated DEX file
            Path dexPath = context.getCacheDir().toPath().resolve("classes.dex");
            byte[] dexBytes = Files.readAllBytes(dexPath);
            
            // If we're capturing DEX for caching, save this
            if (captureDex) {
                Log.d(TAG, "Capturing DEX for class: " + name + ", size: " + dexBytes.length + " bytes");
                int dexIndex = capturedDexFiles.size();
                capturedDexFiles.add(dexBytes);
                // Record which DEX file contains this class
                dexClassMap.put(name, dexIndex);
                Log.d(TAG, "Added DEX file to collection, total files: " + capturedDexFiles.size());
            }
            
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
            Files.delete(dexPath);
            
            return clazz;
        } catch (Exception e) {
            Log.e(TAG, "Error defining class: " + name, e);
            throw new RuntimeException("Failed to define class: " + name, e);
        }
    }

    // Helper method to define a class from bytes
    private Class<?> fromByteArray(String name, byte[] bytes) {
        try {
            // Convert JVM bytecode to DEX using D8
            D8Command command = D8Command.builder()
                .addClassProgramData(bytes, Origin.unknown())
                .setMode(CompilationMode.DEBUG)
                .setOutput(context.getCacheDir().toPath(), OutputMode.DexIndexed)
                .build();
            
            D8.run(command);

            // Read the generated DEX file
            Path dexPath = context.getCacheDir().toPath().resolve("classes.dex");
            byte[] dexBytes = Files.readAllBytes(dexPath);
            
            // Create a ByteBuffer containing the DEX bytes
            ByteBuffer buffer = ByteBuffer.allocate(dexBytes.length);
            buffer.put(dexBytes);
            buffer.position(0);

            // Update class loader with new DEX
            dexBuffers.add(buffer);
            ByteBuffer[] buffers = dexBuffers.toArray(new ByteBuffer[0]);
            currentLoader = new InMemoryDexClassLoader(buffers, parent);
            Thread.currentThread().setContextClassLoader(currentLoader);
            
            // Load the class from the updated loader
            Class<?> clazz = currentLoader.loadClass(name);
            
            // Clean up
            Files.delete(dexPath);
            
            return clazz;
        } catch (Exception e) {
            Log.e(TAG, "Error defining class from byte array: " + name, e);
            throw new RuntimeException("Failed to define class from byte array: " + name, e);
        }
    }

    // Add a cleanup method that doesn't log warnings
    public static void cleanup() {
        captureDex = false;
        capturedDexFiles.clear();
        dexClassMap.clear();
    }
}
