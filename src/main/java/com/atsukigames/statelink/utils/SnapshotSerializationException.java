package com.atsukigames.statelink.utils;

/** Signals that an authoritative snapshot could not preserve an item's full data. */
public final class SnapshotSerializationException extends RuntimeException {
    public SnapshotSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
