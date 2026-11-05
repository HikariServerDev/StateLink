package com.atsukigames.statelink.config;

/** A sanitized configuration error that never includes parsed values or credentials. */
public final class ConfigurationException extends RuntimeException {
    public ConfigurationException(String message) {
        super(message);
    }
}
