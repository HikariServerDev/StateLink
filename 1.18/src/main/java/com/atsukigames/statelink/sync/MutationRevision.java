package com.atsukigames.statelink.sync;

/** Server-thread mutation counters avoid scanning all progress/statistics on idle checkpoints. */
public interface MutationRevision {
    long statelink$mutationRevision();
}
