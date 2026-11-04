package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectThreadDispatchTest {
    @Test
    void offThreadCallbackIsOnlyRunAfterServerExecutorDrainsIt() {
        ArrayDeque<Runnable> serverQueue = new ArrayDeque<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        boolean deferred = PlayerSyncCoordinator.dispatchIfOffServerThread(
            () -> false,
            serverQueue::add,
            () -> callbackThread.set(Thread.currentThread()));

        assertTrue(deferred);
        assertNull(callbackThread.get(), "disconnect processing must not run on the caller thread");
        assertEquals(1, serverQueue.size());

        serverQueue.remove().run();
        assertEquals(serverThread, callbackThread.get());
    }

    @Test
    void serverThreadCallbackRemainsForTheCurrentCallToProcessInline() {
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        ArrayDeque<Runnable> serverQueue = new ArrayDeque<>();

        boolean deferred = PlayerSyncCoordinator.dispatchIfOffServerThread(
            () -> true,
            serverQueue::add,
            () -> callbackThread.set(Thread.currentThread()));

        assertFalse(deferred);
        assertTrue(serverQueue.isEmpty());
        assertNull(callbackThread.get(), "caller continues processing inline when already on server thread");
    }
}
