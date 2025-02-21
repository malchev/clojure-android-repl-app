package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import dalvik.system.PathClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ReflectorPatcher {
    private static class PatchingClassLoader extends PathClassLoader {
        private final ClassLoader parent;
        
        public PatchingClassLoader(ClassLoader parent) {
            super("", "", parent);
            this.parent = parent;
        }
        
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // First check if the class is already loaded
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass != null) {
                return loadedClass;
            }
            
            // Check if this is the Reflector class
            if (name.equals("clojure.lang.Reflector")) {
                try {
                    Log.i("ClojureREPL", "Attempting to load and patch Reflector class");
                    
                    // Try to get the class bytes from the parent first
                    String resourcePath = name.replace('.', '/') + ".class";
                    InputStream in = parent.getResourceAsStream(resourcePath);
                    if (in == null) {
                        Log.w("ClojureREPL", "Could not find Reflector class in parent loader");
                        return parent.loadClass(name);
                    }
                    
                    // Read the class bytes
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    byte[] classBytes = out.toByteArray();
                    
                    // Replace the canAccess check in the bytecode
                    // Find the sequence that loads the MethodHandle
                    int offset = findMethodHandleSequence(classBytes);
                    if (offset >= 0) {
                        // Replace with code that loads a constant true
                        classBytes[offset] = (byte) 0x04;  // ICONST_1
                        classBytes[offset + 1] = (byte) 0xAC;  // IRETURN
                        Log.i("ClojureREPL", "Found and patched bytecode sequence at offset " + offset);
                    } else {
                        Log.w("ClojureREPL", "Could not find bytecode sequence to patch");
                    }
                    
                    // Define the modified class
                    return defineClass(name, classBytes, 0, classBytes.length);
                    
                } catch (Exception e) {
                    Log.e("ClojureREPL", "Error loading Reflector class", e);
                    return parent.loadClass(name);
                }
            }
            
            // For all other classes, delegate to parent
            return parent.loadClass(name);
        }
    }
    
    public static void patch() throws Exception {
        try {
            // Create our patching class loader
            ClassLoader originalLoader = ReflectorPatcher.class.getClassLoader();
            ClassLoader patchingLoader = new PatchingClassLoader(originalLoader);
            
            // Set it as the context class loader
            Thread.currentThread().setContextClassLoader(patchingLoader);
            
            Log.i("ClojureREPL", "Successfully installed patching class loader");
            
        } catch (Exception e) {
            Log.e("ClojureREPL", "Error patching Reflector", e);
            throw e;
        }
    }
    
    private static int findMethodHandleSequence(byte[] classBytes) {
        // Look for the sequence that loads the MethodHandle
        byte[] pattern = new byte[] {
            (byte) 0xB8, // INVOKESTATIC
            0x00, // index byte 1
            0x00, // index byte 2
            (byte) 0xB5  // PUTFIELD
        };
        
        for (int i = 0; i < classBytes.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (classBytes[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
} 