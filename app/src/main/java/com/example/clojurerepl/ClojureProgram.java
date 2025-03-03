package com.example.clojurerepl;

import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ClojureProgram {
    private String code;
    private List<String> timingRuns;
    private String name;

    public ClojureProgram(String code) {
        this.code = code;
        this.timingRuns = new ArrayList<>();
        // Generate a default name based on first line or first N characters
        this.name = generateName(code);
    }

    private String generateName(String code) {
        String firstLine = code.split("\n")[0].trim();
        String displayName = firstLine.length() > 30 ? 
            firstLine.substring(0, 27) + "..." : 
            firstLine;
            
        // Generate hash suffix
        String hashSuffix = generateHashSuffix(code);
        
        return displayName + " [" + hashSuffix + "]";
    }
    
    private String generateHashSuffix(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes());
            
            // Convert first 3 bytes to hex (6 characters)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash code if SHA-256 is unavailable
            return String.format("%06x", Math.abs(code.hashCode()) % 0xFFFFFF);
        }
    }

    public String getCode() {
        return code;
    }

    public List<String> getTimingRuns() {
        return timingRuns;
    }

    public void addTimingRun(String timing) {
        timingRuns.add(timing);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClojureProgram that = (ClojureProgram) o;
        return code.equals(that.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
} 