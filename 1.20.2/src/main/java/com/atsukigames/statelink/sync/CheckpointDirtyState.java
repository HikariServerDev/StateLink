package com.atsukigames.statelink.sync;

/** Change epochs, not snapshot identity: an old DB completion cannot clean a later mutation. */
public final class CheckpointDirtyState {
    private long changed = 1;
    private long committed;
    public synchronized long markChanged() { return ++changed; }
    public synchronized long version() { return changed; }
    public synchronized boolean isDirty() { return changed != committed; }
    public synchronized void committed(long capturedVersion) {
        if (capturedVersion > committed && capturedVersion <= changed) committed = capturedVersion;
    }
}
