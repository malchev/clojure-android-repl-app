package com.example.clojurerepl;

/**
 * Interface for class loaders that can provide cached bytecode for similar classes
 */
public interface CachedClassProvider {
    /**
     * Find bytecode for a class with similar structure but different name
     * @param className the name of the class being requested
     * @return bytecode if a similar class is found, null otherwise
     */
    byte[] findSimilarClass(String className);
    
    /**
     * Normalize a class name by replacing numeric parts with placeholders
     */
    default String normalizeClassName(String className) {
        return className.replaceAll("\\d+", "X");
    }
} 