package com.example.clojurerepl;

import android.util.Log;
import java.io.Serializable;
import java.util.Map;
import java.util.HashMap;
import clojure.lang.IFn;

/**
 * Represents a compiled Clojure program that can be serialized and executed directly.
 */
public class CompiledProgram implements Serializable {
    private static final String TAG = "CompiledProgram";
    private static final long serialVersionUID = 1L;
    
    // The actual function to execute
    private transient IFn function;
    
    // The name of the class containing the function
    private String functionClassName;
    
    // A description of what this program does
    private String description;
    
    // Creation timestamp
    private long timestamp;
    
    // Public constructor for serialization
    public CompiledProgram() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public CompiledProgram(IFn function, String className, String description) {
        this();
        this.function = function;
        this.functionClassName = className;
        this.description = description;
    }
    
    public void setFunction(IFn function) {
        this.function = function;
    }
    
    public IFn getFunction() {
        return function;
    }
    
    public String getFunctionClassName() {
        return functionClassName;
    }
    
    public void setFunctionClassName(String className) {
        this.functionClassName = className;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Execute the compiled function with the specified arguments
     */
    public Object execute(Object... args) {
        if (function == null) {
            throw new IllegalStateException("Function not loaded");
        }
        
        try {
            Log.d(TAG, "Executing compiled function: " + functionClassName);
            
            if (args.length == 0) {
                return function.invoke();
            } else if (args.length == 1) {
                return function.invoke(args[0]);
            } else if (args.length == 2) {
                return function.invoke(args[0], args[1]);
            } else {
                // For more arguments, use reflection
                return function.getClass()
                    .getMethod("invoke", Object[].class)
                    .invoke(function, new Object[] { args });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing compiled function", e);
            throw new RuntimeException("Error executing compiled function: " + e.getMessage(), e);
        }
    }
} 