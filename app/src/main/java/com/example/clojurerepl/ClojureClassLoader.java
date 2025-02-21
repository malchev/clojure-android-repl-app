package com.example.clojurerepl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

public class ClojureClassLoader extends ClassLoader {
    public ClojureClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // If it's the Reflector class, we'll modify it
        if ("clojure.lang.Reflector".equals(name)) {
            try {
                // Load the original class bytes
                String resourceName = name.replace('.', '/') + ".class";
                InputStream in = getParent().getResourceAsStream(resourceName);
                if (in == null) {
                    return super.loadClass(name, resolve);
                }

                // Read the class bytes
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                byte[] classBytes = out.toByteArray();

                // TODO: Modify the class bytes to remove canAccess method
                // For now, just define the class
                return defineClass(name, classBytes, 0, classBytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("Failed to load " + name, e);
            }
        }
        return super.loadClass(name, resolve);
    }
} 