package com.atsukigames.statelink.sync;

/** DBへスナップショットを書き込む契機。ログとcoalescing判断で使用する。 */
public enum SaveReason {
    INITIAL,
    PERIODIC,
    DEATH,
    DIMENSION,
    DISCONNECT_FINAL,
    SHUTDOWN_FINAL
}
