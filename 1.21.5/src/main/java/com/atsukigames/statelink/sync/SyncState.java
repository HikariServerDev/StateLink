package com.atsukigames.statelink.sync;

/**
 * ローカルのプレイヤーセッション状態。
 *
 * <p>DBのownership状態とは別に管理する。特にLOADING中は、ローカルに
 * PlayerDataが存在していてもauthoritative dataを取得済みとはみなさない。</p>
 */
public enum SyncState {
    LOADING,
    READY,
    DEGRADED,
    /** Disconnect requested; mutation is gated while server-thread cleanup/snapshot runs. */
    QUIESCING,
    FLUSHING,
    CLOSED
}
