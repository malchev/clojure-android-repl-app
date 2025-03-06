package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import clojure.lang.RT;
import clojure.lang.Var;
import clojure.lang.Symbol;
import clojure.lang.IFn;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Directly executes compiled DEX files without using Clojure
 */
public class CompiledDexRunner {
    private static final String TAG = "CompiledDexRunner";
    
    private final Context context;
    private final ViewGroup contentLayout;
    
    public CompiledDexRunner(Context context, ViewGroup contentLayout) {
        this.context = context;
        this.contentLayout = contentLayout;
    }
    
    /**
     * Execute a compiled DEX file directly
     * @param dexBuffers The DEX ByteBuffers to execute
     * @param entryPointClassName The entry point class name
     * @param codeHash The code hash
     * @return Result of execution
     */
    public Object execute(ByteBuffer[] dexBuffers, String entryPointClassName, String codeHash) {
        // Declare dexLoader at a wider scope so it's available in the catch block
        InMemoryDexClassLoader dexLoader = null;
        
        try {
            Log.d(TAG, "Creating InMemoryDexClassLoader for direct execution with " + dexBuffers.length + " DEX files");
            
            // Create a ClassLoader that has both our app's classes and the DEX
            ClassLoader parentLoader = getClass().getClassLoader();
            
            // IMPORTANT: Pass all DEX buffers to the InMemoryDexClassLoader
            dexLoader = new InMemoryDexClassLoader(dexBuffers, parentLoader);
            
            // Get the function name from the entry point class name
            String functionName = entryPointClassName;
            if (functionName.startsWith("clojure.core$")) {
                functionName = functionName.substring("clojure.core$".length());
            }
            Log.d(TAG, "Function name extracted from class: " + functionName);
            
            // Important: Set this as the thread's context ClassLoader 
            Thread currentThread = Thread.currentThread();
            ClassLoader originalLoader = currentThread.getContextClassLoader();
            
            try {
                // First set our DEX loader as the context classloader
                currentThread.setContextClassLoader(dexLoader);
                
                // Setup proper bindings
                Var.pushThreadBindings(RT.map(
                    RT.CURRENT_NS, RT.CLOJURE_NS,
                    Var.intern(RT.CLOJURE_NS, Symbol.intern("*context*")), context,
                    Var.intern(RT.CLOJURE_NS, Symbol.intern("*content-layout*")), contentLayout
                ));
                
                try {
                    // Load the dependency information using entry point as key
                    List<String> dependencies = BytecodeCache.getInstance(context)
                        .loadClassDependencies(entryPointClassName);
                    Log.d(TAG, "Found " + dependencies.size() + " class dependencies");
                    
                    // We don't need to instantiate these classes - just having their bytecode 
                    // loaded in the ClassLoader is enough. Simply log that we found them.
                    for (String className : dependencies) {
                        try {
                            // Just check that the class can be loaded - don't instantiate
                            dexLoader.loadClass(className);
                            // Success - the class is available in the classloader
                            Log.d(TAG, "Verified dependency class is available: " + className);
                        } catch (ClassNotFoundException e) {
                            // Non-critical error, just log
                            Log.w(TAG, "Could not find dependency class: " + className);
                        }
                    }
                    
                    // Now load and instantiate just the entry point function
                    Class<?> fnClass = dexLoader.loadClass(entryPointClassName);
                    Log.d(TAG, "Successfully loaded function class: " + entryPointClassName);
                    
                    // Create instance of the main function
                    Object fnInstance = fnClass.newInstance();
                    Log.d(TAG, "Created function instance: " + fnInstance);
                    
                    // Define the function in the clojure.core namespace
                    Var fnVar = Var.intern(RT.CLOJURE_NS, Symbol.intern(functionName), fnInstance);
                    Log.d(TAG, "Interned function in namespace: " + functionName);
                    
                    // Get the IFn implementation
                    IFn fn = (IFn)fnVar.deref();
                    
                    // Execute the function
                    if (functionName.equals("-main")) {
                        Log.d(TAG, "Invoking -main function with no arguments");
                        return fn.invoke();
                    } else {
                        // For other functions, try different argument combinations
                        Log.d(TAG, "Trying to invoke with different arg combinations");
                        try {
                            return fn.invoke();
                        } catch (clojure.lang.ArityException e0) {
                            try {
                                return fn.invoke(context);
                            } catch (clojure.lang.ArityException e1) {
                                try {
                                    return fn.invoke(context, contentLayout);
                                } catch (clojure.lang.ArityException e2) {
                                    throw new RuntimeException("Function " + functionName + 
                                        " rejects all common arg combinations. Original error: " + e0.getMessage());
                                }
                            }
                        }
                    }
                } finally {
                    Var.popThreadBindings();
                }
            } finally {
                // Restore original class loader
                currentThread.setContextClassLoader(originalLoader);
            }
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Class not found: " + e.getMessage());
            
            // Check if dexLoader is null
            if (dexLoader == null) {
                Log.e(TAG, "DEX loader is null - DEX file could not be loaded");
            } else {
                Log.d(TAG, "Trying to load some known Clojure classes:");
                // Try loading some known Clojure classes through the DEX loader
                String[] classesToCheck = {
                    // Common Clojure core classes
                    "clojure.lang.RT",
                    "clojure.lang.Var",
                    // Try some specific classes with wildcards
                    "clojure.core$eval",
                    "clojure.core$initialize",
                    // Try with number patterns
                    "clojure.core$initialize_compass",
                    "clojure.core$initialize_compass$fn"
                };
                
                for (String className : classesToCheck) {
                    try {
                        Class<?> cls = dexLoader.loadClass(className);
                        Log.d(TAG, "Successfully loaded: " + className);
                    } catch (ClassNotFoundException ex) {
                        Log.d(TAG, "Not found: " + className);
                    }
                }
                
                // Check if the DEX file actually contains any bytes
                if (dexBuffers != null && dexBuffers.length > 0) {
                    Log.d(TAG, "DEX buffer exists, size: " + dexBuffers[0].capacity() + " bytes");
                    // Dump first few bytes for debugging
                    dexBuffers[0].rewind();
                    StringBuilder hexDump = new StringBuilder("DEX header: ");
                    for (int i = 0; i < Math.min(16, dexBuffers[0].capacity()); i++) {
                        hexDump.append(String.format("%02X ", dexBuffers[0].get()));
                    }
                    Log.d(TAG, hexDump.toString());
                } else {
                    Log.e(TAG, "DEX buffer is null");
                }
            }
            
            throw new RuntimeException("Error loading class: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Error executing compiled DEX", e);
            throw new RuntimeException("Error executing compiled DEX: " + e.getMessage(), e);
        }
    }
    
    /**
     * Find an appropriate method to execute in the entry class
     */
    private Method findExecutableMethod(Class<?> clazz) {
        // Try known method names in order of likelihood
        String[] methodNames = {
            "initialize",
            "invoke",
            "run",
            "execute",
            "main",
            "doInvoke"
        };
        
        // Try with context and view as parameters
        try {
            return clazz.getMethod("invoke", Context.class, ViewGroup.class);
        } catch (NoSuchMethodException e) {
            // Expected, try next
        }
        
        // Try with just context
        try {
            return clazz.getMethod("invoke", Context.class);
        } catch (NoSuchMethodException e) {
            // Expected, try next
        }
        
        // Try no-arg versions of other methods
        for (String name : methodNames) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                // Expected, try next
            }
        }
        
        // Last resort: try finding any method named "invoke"
        try {
            // Get all methods
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (method.getName().equals("invoke")) {
                    return method;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding invoke method", e);
        }
        
        return null;
    }
} 