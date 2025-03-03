package com.example.clojurerepl;

import java.util.ArrayList;
import java.util.List;

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
        if (firstLine.length() > 30) {
            return firstLine.substring(0, 27) + "...";
        }
        return firstLine;
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