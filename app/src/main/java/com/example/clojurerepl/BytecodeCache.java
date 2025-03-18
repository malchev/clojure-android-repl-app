package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import dalvik.system.InMemoryDexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import clojure.lang.IFn;
import clojure.lang.RT;
import java.util.HashSet;
import java.io.BufferedReader;
import java.io.FileReader;

public class BytecodeCache {
    private static final String TAG = "BytecodeCache";
    private static final String CACHE_DIR = "clojure_bytecode";
    // This name must not change. It's the output of the D8 tool that we
    // configure to generate classes directly into the cache.
    private static final String DEX_FILENAME = "classes.dex";
    private static final String MANIFEST_FILENAME = "classes.manifest";
    private static final Map<String, BytecodeCache> instances = new HashMap<>();

    private final Context context;
    private final File cacheDir;
    private final String codeHash;

    // Private constructor
    private BytecodeCache(Context context, String codeHash) {
        this.context = context.getApplicationContext(); // Use application context
        this.cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        this.codeHash = codeHash;
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        Log.d(TAG, "BytecodeCache initialized at: " + cacheDir.getAbsolutePath());
    }

    // Updated getInstance to return different instances for different codeHashes
    public static synchronized BytecodeCache getInstance(Context context, String codeHash) {
        if (!instances.containsKey(codeHash)) {
            instances.put(codeHash, new BytecodeCache(context.getApplicationContext(), codeHash));
        }
        return instances.get(codeHash);
    }

    public File createPathToDexFile(String className) {
        File hashDir = new File(cacheDir, codeHash);
        File classDir = new File(hashDir, className);
        File dexFile = new File(classDir, DEX_FILENAME);

        if (dexFile.exists()) {
            throw new IllegalStateException("DEX file already exists at: " + dexFile.getAbsolutePath());
        }

        if (!classDir.exists()) {
            classDir.mkdirs();
        }
        return classDir;
    }

    private boolean hasDexCacheInternal(File hashDir, String codeHash) {
        File manifestFile = new File(hashDir, MANIFEST_FILENAME);
        if (!manifestFile.exists()) {
            Log.w(TAG, "Hash directory '" + hashDir.getAbsolutePath() + "' exists but no manifest file found for: "
                    + codeHash);
            return false;
        }
        // Read manifest file and verify all classes exist
        try {
            Set<String> manifestClasses = new HashSet<>();
            BufferedReader reader = new BufferedReader(new FileReader(manifestFile));
            String line;
            while ((line = reader.readLine()) != null) {
                manifestClasses.add(line.trim());
            }
            reader.close();

            // Get actual class directories
            Set<String> actualClasses = new HashSet<>();
            File[] classDirs = hashDir.listFiles(File::isDirectory);
            if (classDirs != null) {
                for (File classDir : classDirs) {
                    // Verify each class directory has exactly one file - DEX_FILENAME
                    File[] files = classDir.listFiles();
                    if (files == null || files.length != 1 || !files[0].getName().equals(DEX_FILENAME)) {
                        Log.w(TAG, "Class directory " + classDir.getName() + " does not contain exactly one "
                                + DEX_FILENAME + " file");
                        return false;
                    }
                    actualClasses.add(classDir.getName());
                }
            }

            // Verify manifest classes exist in directory
            for (String className : manifestClasses) {
                if (!actualClasses.contains(className)) {
                    Log.w(TAG, "Class " + className + " in manifest but missing from directory");
                    return false;
                }
            }

            // Verify directory classes exist in manifest
            for (String className : actualClasses) {
                if (!manifestClasses.contains(className)) {
                    Log.w(TAG, "Class " + className + " in directory but missing from manifest");
                    return false;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading manifest file", e);
            return false;
        }
        return true;
    }

    // Check if a DEX cache exists for this code hash
    boolean hasDexCache(String codeHash) {
        File hashDir = new File(cacheDir, codeHash);
        if (hashDir.exists() && hashDir.isDirectory()) {
            if (hasDexCacheInternal(hashDir, codeHash)) {
                return true;
            }

            // Clear the cache for this hash and return false. This way we will
            // force a re-generation of the cache.
            clearCacheForHash(codeHash);
            return false;
        }
        Log.w(TAG, "Hash directory '" + hashDir.getAbsolutePath() + "' does not exist for: " + codeHash);
        return false;
    }

    // Load DEX from cache
    public ByteBuffer[] loadDexCaches(String codeHash) {
        File hashDir = new File(cacheDir, codeHash);
        if (hashDir.exists() && hashDir.isDirectory()) {
            File[] classDirs = hashDir.listFiles(File::isDirectory);
            if (classDirs != null && classDirs.length > 0) {
                List<ByteBuffer> bufferList = new ArrayList<>();

                for (File classDir : classDirs) {
                    File dexFile = new File(classDir, DEX_FILENAME);
                    if (dexFile.exists()) {
                        try (FileInputStream fis = new FileInputStream(dexFile)) {
                            byte[] buffer = new byte[(int) dexFile.length()];
                            fis.read(buffer);

                            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(buffer.length);
                            byteBuffer.put(buffer);
                            byteBuffer.rewind();

                            bufferList.add(byteBuffer);
                            Log.d(TAG, "Loaded DEX file for class " + classDir.getName() +
                                    ", size: " + buffer.length);
                        } catch (IOException e) {
                            Log.e(TAG, "Error loading DEX file: " + dexFile.getAbsolutePath(), e);
                        }
                    }
                }

                if (!bufferList.isEmpty()) {
                    return bufferList.toArray(new ByteBuffer[0]);
                }
            }
        }

        Log.e(TAG, "No DEX files found for hash: " + codeHash);
        return null;
    }

    // Create a ClassLoader from a cached DEX
    public ClassLoader createClassLoaderFromCache(String codeHash, ClassLoader parent) {
        ByteBuffer[] dexBuffers = loadDexCaches(codeHash);
        if (dexBuffers == null || dexBuffers.length == 0) {
            return null;
        }

        return new InMemoryDexClassLoader(
                dexBuffers, // Pass as array
                parent // Just parent ClassLoader
        );
    }

    // Generate a manifest file for the given code hash and a list of generated
    // classes
    public void generateManifest(String codeHash, List<String> generatedClasses) {
        // The path is cacheDir/codeHash/classes.manifest
        Log.d(TAG, "Generating manifest for code hash: " + codeHash);
        File hashDir = new File(cacheDir, codeHash);
        File manifestFile = new File(hashDir, MANIFEST_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
            for (String className : generatedClasses) {
                fos.write((className + "\n").getBytes());
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing manifest file: " + manifestFile.getAbsolutePath(), e);
        }
    }

    // Helper method to recursively delete directories
    private boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDir.delete();
    }

    // Clear all cache files
    public void clearCache() {
        int count = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (deleteRecursive(file)) {
                    count++;
                }
            }
        }
        Log.d(TAG, "Cleared " + count + " entries from cache");
    }

    // Add a method to clear the cache for a specific hash
    public void clearCacheForHash(String codeHash) {
        Log.d(TAG, "Clearing cache for hash: " + codeHash);

        File hashDir = new File(cacheDir, codeHash);
        if (hashDir.exists() && hashDir.isDirectory()) {
            if (deleteRecursive(hashDir)) {
                Log.d(TAG, "Deleted hash directory: " + hashDir.getPath());
            }
        }
    }

    public int getClassCount() {
        // Check if cache directory exists
        File hashDir = new File(cacheDir, codeHash);
        if (!hashDir.exists()) {
            return 0;
        }

        // Count class directories (each containing a .dex file)
        File[] classDirs = hashDir.listFiles(File::isDirectory);
        return classDirs != null ? classDirs.length : 0;
    }

    public long getCacheSize() {
        File hashDir = new File(cacheDir, codeHash);
        if (!hashDir.exists()) {
            return 0L;
        }

        long totalSize = 0;
        // Calculate size by summing up all files in all subdirectories
        File[] classDirs = hashDir.listFiles(File::isDirectory);
        if (classDirs != null) {
            for (File classDir : classDirs) {
                File[] files = classDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            totalSize += file.length();
                        }
                    }
                }
            }
        }

        // Add manifest file size if it exists
        File manifestFile = new File(hashDir, MANIFEST_FILENAME);
        if (manifestFile.exists()) {
            totalSize += manifestFile.length();
        }

        return totalSize;
    }
}
