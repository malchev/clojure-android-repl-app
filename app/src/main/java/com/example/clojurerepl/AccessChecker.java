package com.example.clojurerepl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class AccessChecker {
    public static final AccessChecker INSTANCE;
    
    // Make this an instance method since that's what Clojure expects
    public boolean canAccess(Object obj) {
        return true;
    }

    static {
        try {
            // Create a singleton instance
            INSTANCE = new AccessChecker();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize AccessChecker", e);
        }
    }
} 