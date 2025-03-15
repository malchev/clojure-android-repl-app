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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import clojure.lang.IFn;
import clojure.lang.RT;

public class BytecodeCache {
    private static final String TAG = "BytecodeCache";
    private static final String CACHE_DIR = "clojure_bytecode";
    private static BytecodeCache instance;
    
    private final Context context;
    private final File cacheDir;
    
    // Private constructor
    private BytecodeCache(Context context) {
        this.context = context.getApplicationContext(); // Use application context
        this.cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        Log.d(TAG, "BytecodeCache initialized at: " + cacheDir.getAbsolutePath());
    }
    
    // Singleton getter
    public static synchronized BytecodeCache getInstance(Context context) {
        if (instance == null) {
            instance = new BytecodeCache(context.getApplicationContext());
        }
        return instance;
    }
    
    public String getCodeHash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error generating hash", e);
            return String.valueOf(code.hashCode());
        }
    }
    
    // Check if a DEX cache exists for this code hash
    public boolean hasDexCache(String codeHash) {
        // Check for the manifest file first - that's the indicator we have saved multiple DEX files
        File manifestFile = new File(cacheDir, codeHash + ".manifest");
        if (manifestFile.exists()) {
            Log.d(TAG, "DEX manifest exists for " + codeHash);
            
            // Also check if the directory exists
            File dexDir = new File(cacheDir, codeHash + "_dex");
            boolean dirExists = dexDir.exists() && dexDir.isDirectory();
            if (dirExists) {
                // Count the DEX files to verify integrity
                File[] dexFiles = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
                int dexCount = dexFiles != null ? dexFiles.length : 0;
                Log.d(TAG, "Found " + dexCount + " DEX files in directory");
                return dexCount > 0;
            }
            
            Log.w(TAG, "DEX manifest exists but directory is missing: " + dexDir.getAbsolutePath());
            return false;
        }
        
        // Fall back to checking for single DEX file (for backward compatibility)
        File dexFile = new File(cacheDir, codeHash + ".dex");
        boolean exists = dexFile.exists();
        Log.d(TAG, "Single DEX cache file for " + codeHash + " exists: " + exists);
        return exists;
    }
    
    // Save compiled DEX to cache
    public void saveDexCache(String codeHash, byte[] dexBytes) {
        if (dexBytes == null || dexBytes.length == 0) {
            Log.e(TAG, "Attempted to save empty or null DEX for hash: " + codeHash);
            return;
        }
        
        Log.d(TAG, "Saving DEX cache with " + dexBytes.length + " bytes for " + codeHash);
        File dexFile = new File(cacheDir, codeHash + ".dex");
        
        try (FileOutputStream fos = new FileOutputStream(dexFile)) {
            fos.write(dexBytes);
            fos.flush();
            
            // Verify the file was written correctly
            Log.d(TAG, "Saved DEX file, size on disk: " + dexFile.length() + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Error saving DEX cache", e);
        }
    }
    
    // Load DEX from cache
    public ByteBuffer[] loadDexCaches(String codeHash) {
        // Check if we have the directory with multiple DEX files
        File manifestFile = new File(cacheDir, codeHash + ".manifest");
        if (manifestFile.exists()) {
            File dexDir = new File(cacheDir, codeHash + "_dex");
            if (dexDir.exists() && dexDir.isDirectory()) {
                File[] files = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
                if (files != null && files.length > 0) {
                    Log.d(TAG, "Loading " + files.length + " DEX files from directory");
                    
                    // Sort by filename (which is numeric index)
                    java.util.Arrays.sort(files, (a, b) -> {
                        try {
                            int idxA = Integer.parseInt(a.getName().replace(".dex", ""));
                            int idxB = Integer.parseInt(b.getName().replace(".dex", ""));
                            return Integer.compare(idxA, idxB);
                        } catch (NumberFormatException e) {
                            return a.getName().compareTo(b.getName());
                        }
                    });
                    
                    // Load all DEX files into ByteBuffers
                    ByteBuffer[] buffers = new ByteBuffer[files.length];
                    for (int i = 0; i < files.length; i++) {
                        try (FileInputStream fis = new FileInputStream(files[i])) {
                            byte[] buffer = new byte[(int) files[i].length()];
                            fis.read(buffer);
                            
                            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(buffer.length);
                            byteBuffer.put(buffer);
                            byteBuffer.rewind();
                            
                            buffers[i] = byteBuffer;
                            Log.d(TAG, "Loaded DEX file " + i + ": " + files[i].getName() + ", size: " + buffer.length);
                        } catch (IOException e) {
                            Log.e(TAG, "Error loading DEX file: " + files[i].getName(), e);
                            return null;
                        }
                    }
                    
                    return buffers;
                }
            }
        }
        
        // Fall back to checking for single DEX file
        File dexFile = new File(cacheDir, codeHash + ".dex");
        if (dexFile.exists()) {
            try (FileInputStream fis = new FileInputStream(dexFile)) {
                byte[] buffer = new byte[(int) dexFile.length()];
                fis.read(buffer);
                
                ByteBuffer byteBuffer = ByteBuffer.allocateDirect(buffer.length);
                byteBuffer.put(buffer);
                byteBuffer.rewind();
                
                Log.d(TAG, "Loaded single DEX cache (" + buffer.length + " bytes) for hash: " + codeHash);
                return new ByteBuffer[] { byteBuffer };
            } catch (IOException e) {
                Log.e(TAG, "Error loading DEX cache", e);
                return null;
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
            dexBuffers,  // Pass as array
            parent    // Just parent ClassLoader
        );
    }
    
    // Clear all cache files
    public void clearCache() {
        int count = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) {
                    count++;
                }
            }
        }
        Log.d(TAG, "Cleared " + count + " files from cache");
    }
    
    public void saveEntryPointClass(String codeHash, String entryPointClassName) {
        if (entryPointClassName == null || entryPointClassName.isEmpty()) {
            Log.e(TAG, "Attempted to save null or empty entry point class name for hash: " + codeHash);
            return;
        }
        
        Log.d(TAG, "Saving entry point class: " + entryPointClassName + " for hash: " + codeHash);
        File entryPointFile = new File(cacheDir, codeHash + ".entry");
        
        try (FileOutputStream fos = new FileOutputStream(entryPointFile)) {
            byte[] bytes = entryPointClassName.getBytes();
            fos.write(bytes);
            fos.flush();
            Log.d(TAG, "Entry point saved, file size: " + entryPointFile.length() + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Error saving entry point class", e);
        }
    }
    
    public String loadEntryPointClass(String codeHash) {
        File entryPointFile = new File(cacheDir, codeHash + ".entry");
        if (!entryPointFile.exists()) {
            return null;
        }
        
        try (FileInputStream fis = new FileInputStream(entryPointFile)) {
            byte[] buffer = new byte[(int) entryPointFile.length()];
            int bytesRead = fis.read(buffer);
            String entryPointClassName = new String(buffer, 0, bytesRead);
            Log.d(TAG, "Loaded entry point class: " + entryPointClassName + " for hash: " + codeHash);
            
            // For proper debugging, also extract the function name
            String functionName = extractFunctionName(entryPointClassName);
            Log.d(TAG, "Extracted function name: " + functionName);
            
            return entryPointClassName;
        } catch (IOException e) {
            Log.e(TAG, "Error loading entry point class", e);
            return null;
        }
    }
    
    /**
     * Save a compiled program that can be loaded and executed directly
     */
    public void saveCompiledProgram(String codeHash, CompiledProgram program) {
        Log.d(TAG, "Saving compiled program for hash: " + codeHash);
        File programFile = new File(cacheDir, codeHash + ".program");
        
        try (FileOutputStream fos = new FileOutputStream(programFile);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(program);
            Log.d(TAG, "Saved compiled program for hash: " + codeHash);
        } catch (IOException e) {
            Log.e(TAG, "Error saving compiled program", e);
        }
    }
    
    /**
     * Load a compiled program that can be executed directly
     */
    public CompiledProgram loadCompiledProgram(String codeHash, ClassLoader loader) {
        File programFile = new File(cacheDir, codeHash + ".program");
        
        if (!programFile.exists()) {
            Log.d(TAG, "No compiled program found for hash: " + codeHash);
            return null;
        }
        
        try (FileInputStream fis = new FileInputStream(programFile);
             ObjectInputStream ois = new ObjectInputStream(fis) {
                 // Override to use the provided class loader
                 @Override
                 protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                     try {
                         return loader.loadClass(desc.getName());
                     } catch (ClassNotFoundException e) {
                         return super.resolveClass(desc);
                     }
                 }
             }) {
            CompiledProgram program = (CompiledProgram) ois.readObject();
            Log.d(TAG, "Loaded compiled program for hash: " + codeHash);
            
            // We need to load the actual function class and create an instance
            try {
                if (program.getFunctionClassName().equals("clojure.lang.Var")) {
                    // Handle Var case differently - look up the initialize-compass var
                    IFn function = (IFn) RT.var("clojure.core", "initialize-compass").deref();
                    program.setFunction(function);
                    Log.d(TAG, "Successfully initialized function from Var");
                    return program;
                } else {
                    Class<?> fnClass = loader.loadClass(program.getFunctionClassName());
                    IFn function = (IFn) fnClass.newInstance();
                    program.setFunction(function);
                    Log.d(TAG, "Successfully initialized function: " + program.getFunctionClassName());
                    return program;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize function: " + program.getFunctionClassName(), e);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading compiled program", e);
            return null;
        }
    }
    
    /**
     * Check if a compiled program exists for this code hash
     */
    public boolean hasCompiledProgram(String codeHash) {
        File programFile = new File(cacheDir, codeHash + ".program");
        boolean exists = programFile.exists();
        Log.d(TAG, "Compiled program for " + codeHash + " exists: " + exists);
        return exists;
    }
    
    // Add this method to save the DEX + Entry Class in one step
    public void saveDexWithEntry(String codeHash, byte[] dexBytes, String entryClassName) {
        saveDexCache(codeHash, dexBytes);
        saveEntryPointClass(codeHash, entryClassName);
    }
    
    // Add method to check if both DEX and entry point exist
    public boolean hasCompleteCache(String codeHash) {
        boolean hasDex = hasDexCache(codeHash);
        boolean hasEntry = false;
        
        // Check for the entry point file
        File entryPointFile = new File(cacheDir, codeHash + ".entry");
        hasEntry = entryPointFile.exists() && entryPointFile.length() > 0;
        
        if (entryPointFile.exists()) {
            Log.d(TAG, "Entry file exists, size: " + entryPointFile.length() + " bytes");
            
            // Try to read the content to verify
            try (FileInputStream fis = new FileInputStream(entryPointFile)) {
                byte[] buffer = new byte[(int) entryPointFile.length()];
                int bytesRead = fis.read(buffer);
                String entryClassName = new String(buffer, 0, bytesRead);
                Log.d(TAG, "Entry point class from file: " + entryClassName);
            } catch (IOException e) {
                Log.e(TAG, "Error reading entry point file", e);
            }
        } else {
            Log.d(TAG, "Entry file does not exist: " + entryPointFile.getAbsolutePath());
        }
        
        Log.d(TAG, "Cache check - DEX: " + hasDex + ", Entry: " + hasEntry);
        return hasDex && hasEntry;
    }
    
    // Add a helper method to force save an entry point for testing
    public void forceEntryPoint(String codeHash, String className) {
        if (hasDexCache(codeHash)) {
            Log.d(TAG, "Forcing entry point for existing DEX: " + className);
            saveEntryPointClass(codeHash, className);
            Log.d(TAG, "hasCompleteCache after force: " + hasCompleteCache(codeHash));
        }
    }
    
    // Add this helper method to extract the function name from a class name
    public String extractFunctionName(String className) {
        if (className.startsWith("clojure.core$")) {
            return className.substring("clojure.core$".length());
        }
        return className;
    }
    
    // Update to save multiple DEX files
    public void saveMultipleDexCaches(String codeHash, List<byte[]> dexFiles) {
        if (dexFiles == null || dexFiles.isEmpty()) {
            Log.e(TAG, "Attempted to save empty DEX list for hash: " + codeHash);
            return;
        }
        
        // Create a directory for the DEX files
        File dexDir = new File(cacheDir, codeHash + "_dex");
        if (!dexDir.exists()) {
            dexDir.mkdirs();
        }
        
        // Save each DEX file separately
        for (int i = 0; i < dexFiles.size(); i++) {
            byte[] dexBytes = dexFiles.get(i);
            if (dexBytes == null || dexBytes.length == 0) {
                continue;
            }
            
            File dexFile = new File(dexDir, i + ".dex");
            try (FileOutputStream fos = new FileOutputStream(dexFile)) {
                fos.write(dexBytes);
                fos.flush();
                Log.d(TAG, "Saved DEX file " + i + ", size: " + dexFile.length() + " bytes");
            } catch (IOException e) {
                Log.e(TAG, "Error saving DEX file " + i, e);
            }
        }
        
        // Also save a manifest file with count
        try {
            File manifestFile = new File(cacheDir, codeHash + ".manifest");
            try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
                String manifest = "dex_count=" + dexFiles.size();
                fos.write(manifest.getBytes());
            }
            Log.d(TAG, "Saved manifest for " + dexFiles.size() + " DEX files");
        } catch (IOException e) {
            Log.e(TAG, "Error saving DEX manifest", e);
        }
    }
    
    // Add a method to clear the cache for a specific hash
    public void clearCacheForHash(String codeHash) {
        Log.d(TAG, "Clearing cache for hash: " + codeHash);
        
        // Remove the DEX file
        File dexFile = new File(cacheDir, codeHash + ".dex");
        if (dexFile.exists() && dexFile.delete()) {
            Log.d(TAG, "Deleted DEX file: " + dexFile.getPath());
        }
        
        // Remove the DEX directory if it exists
        File dexDir = new File(cacheDir, codeHash + "_dex");
        if (dexDir.exists() && dexDir.isDirectory()) {
            File[] files = dexDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted file: " + file.getPath());
                    }
                }
            }
            if (dexDir.delete()) {
                Log.d(TAG, "Deleted DEX directory: " + dexDir.getPath());
            }
        }
        
        // Remove the manifest file
        File manifestFile = new File(cacheDir, codeHash + ".manifest");
        if (manifestFile.exists() && manifestFile.delete()) {
            Log.d(TAG, "Deleted manifest file: " + manifestFile.getPath());
        }
        
        // Remove the entry point file
        File entryPointFile = new File(cacheDir, codeHash + ".entry");
        if (entryPointFile.exists() && entryPointFile.delete()) {
            Log.d(TAG, "Deleted entry point file: " + entryPointFile.getPath());
        }
        
        // Remove the program file
        File programFile = new File(cacheDir, codeHash + ".program");
        if (programFile.exists() && programFile.delete()) {
            Log.d(TAG, "Deleted program file: " + programFile.getPath());
        }
    }
    
    public void saveClassDependencies(String codeHash, Set<String> classNames) {
        File depFile = new File(cacheDir, codeHash + ".deps");
        try (FileOutputStream fos = new FileOutputStream(depFile);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(new ArrayList<>(classNames));
            Log.d(TAG, "Saved " + classNames.size() + " class dependencies for " + codeHash);
        } catch (IOException e) {
            Log.e(TAG, "Error saving class dependencies", e);
        }
    }
    
    // Add method to load class dependency info
    @SuppressWarnings("unchecked")
    public List<String> loadClassDependencies(String codeHash) {
        File depFile = new File(cacheDir, codeHash + ".deps");
        if (!depFile.exists()) {
            Log.d(TAG, "No dependency file found for " + codeHash);
            return Collections.emptyList();
        }
        
        try (FileInputStream fis = new FileInputStream(depFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            List<String> deps = (List<String>) ois.readObject();
            Log.d(TAG, "Loaded " + deps.size() + " class dependencies for " + codeHash);
            return deps;
        } catch (Exception e) {
            Log.e(TAG, "Error loading class dependencies", e);
            return Collections.emptyList();
        }
    }
    
    // Add a lookup method to find code hash by entry point class
    public String findCodeHashByEntryPoint(String entryPointClassName) {
        // Search through all .entry files to find matching class name
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".entry"));
        if (files != null) {
            for (File file : files) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[(int) file.length()];
                    int bytesRead = fis.read(buffer);
                    String storedEntry = new String(buffer, 0, bytesRead);
                    
                    if (storedEntry.equals(entryPointClassName)) {
                        // Found matching entry, extract hash from filename
                        String filename = file.getName();
                        String hash = filename.substring(0, filename.length() - 6); // remove .entry
                        return hash;
                    }
                } catch (IOException e) {
                    // Just continue to next file
                }
            }
        }
        return null;
    }
}
