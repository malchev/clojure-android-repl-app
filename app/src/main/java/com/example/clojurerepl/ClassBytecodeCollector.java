package com.example.clojurerepl;

/**
 * Interface for class loaders that can collect bytecode
 */
public interface ClassBytecodeCollector {
    /**
     * Checks if the loader is collecting classes
     * @return true if collecting classes
     */
    boolean isCollectingClasses();
    
    /**
     * Collects a class bytecode definition
     * @param className the name of the class
     * @param bytecode the class bytecode
     */
    void collectClass(String className, byte[] bytecode);
} 