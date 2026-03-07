package com.loggenengine.model;

public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    public String padded() {
        return String.format("%-5s", this.name());
    }
}
