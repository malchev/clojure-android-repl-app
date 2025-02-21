package com.example.clojurerepl;

import clojure.lang.IFn;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class AndroidReflector {
    public static boolean isAccessible(Method m) {
        return Modifier.isPublic(m.getModifiers());
    }

    public static boolean isAccessible(Field f) {
        return Modifier.isPublic(f.getModifiers());
    }

    // Simple IFn implementation that always returns true
    private static class AlwaysTrueFunction implements IFn {
        public Object invoke(Object arg) { return true; }
        
        // Required IFn methods
        public Object call() { return true; }
        public void run() {}
        public Object invoke() { return true; }
        public Object invoke(Object arg1, Object arg2) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20) { return true; }
        public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20, Object... args) { return true; }
        public Object applyTo(clojure.lang.ISeq arglist) { return true; }
    }

    public static void installCustomReflector() {
        try {
            // Get the Reflector class
            Class<?> reflectorClass = Class.forName("clojure.lang.Reflector");
            
            // Get the canAccess field
            Field canAccessField = reflectorClass.getDeclaredField("canAccess");
            canAccessField.setAccessible(true);
            
            // Remove final modifier
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(canAccessField, canAccessField.getModifiers() & ~Modifier.FINAL);
            
            // Set our custom function
            canAccessField.set(null, new AlwaysTrueFunction());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
} 