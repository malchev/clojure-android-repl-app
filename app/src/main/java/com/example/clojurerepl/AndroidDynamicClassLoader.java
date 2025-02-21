package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import clojure.lang.DynamicClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class AndroidDynamicClassLoader extends DynamicClassLoader {
    private final Context context;
    private final Map<String, SoftReference<Class<?>>> classCache = new HashMap<>();
    private final ReferenceQueue<Class<?>> rq = new ReferenceQueue<>();
    private int counter = 0;

    public AndroidDynamicClassLoader(Context context, ClassLoader parent) {
        super(parent);
        Log.i("ClojureREPL", "AndroidDynamicClassLoader constructor");
        this.context = context;
    }

    public Class<?> defineClass(String name, byte[] bytes) throws ClassFormatError {
        Log.d("ClojureREPL", "AndroidDynamicClassLoader.defineClass called for " + name);
        try {
            // Create a temporary directory for our work
            File cacheDir = context.getCacheDir();
            File tempDir = new File(cacheDir, "clojure_" + counter);
            tempDir.mkdirs();

            Log.d("ClojureREPL", "Converting class bytes to DEX for " + name);
            
            // Create a DEX file directly from the class bytes
            ByteBuffer dexBuffer = ByteBuffer.allocate(bytes.length * 2);
            for (byte b : bytes) {
                dexBuffer.put(b);
            }
            dexBuffer.flip();

            Log.d("ClojureREPL", "Creating InMemoryDexClassLoader for " + name);
            
            // Use InMemoryDexClassLoader to load the bytes directly
            InMemoryDexClassLoader dexLoader = new InMemoryDexClassLoader(
                dexBuffer,
                getParent()
            );

            // Load the class
            Class<?> c = dexLoader.loadClass(name);
            Log.d("ClojureREPL", "Successfully loaded class " + name);
            
            classCache.put(name, new SoftReference<>(c, rq));

            // Cleanup
            deleteDir(tempDir);
            counter++;

            return c;
        } catch (Exception e) {
            Log.e("ClojureREPL", "Error defining class: " + name, e);
            e.printStackTrace();
            throw new ClassFormatError("Failed to define class: " + name + " - " + e.getMessage());
        }
    }

    private void deleteDir(File dir) {
        Log.i("ClojureREPL", "deleteDir: " + dir.getAbsolutePath());
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDir(file);
                }
            }
        }
        dir.delete();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Log.i("ClojureREPL", "findClass: " + name);
        SoftReference<Class<?>> ref = classCache.get(name);
        Class<?> c = (ref != null) ? ref.get() : null;
        if (c != null) {
            return c;
        }
        return super.findClass(name);
    }
} 