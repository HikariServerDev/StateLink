package com.atsukigames.statelink.sync;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** A thread- and session-scoped permit for vanilla disconnect cleanup drops. */
final class DisconnectDropPermit<T> {
    private final ThreadLocal<Token<T>> current = new ThreadLocal<>();

    boolean isAllowed(T player) {
        Token<T> token = current.get();
        if (token == null || token.thread != Thread.currentThread() || token.player != player) return false;
        PlayerSessionContext context = token.context;
        Identity identity = token.identity;
        return identity.uuid.equals(context.uuid())
            && identity.localSessionId.equals(context.localSessionId())
            && identity.generation == context.generation()
            && identity.fence == context.fencingToken()
            && context.state() == SyncState.QUIESCING
            && context.isAuthoritativeDataLoaded()
            && token.authorityCheck.test(player, identity);
    }

    boolean isBlocked(T player, Predicate<T> normallyBlocked) {
        Token<T> token = current.get();
        if (token != null) {
            // While vanilla is closing this exact screen, any drop outside the
            // scoped identity/authority is denied even if a different/current
            // session would otherwise be READY. Falling back to ordinary
            // quarantine here could let a stale cleanup token piggyback on a
            // replacement session's READY state.
            return !isAllowed(player);
        }
        return normallyBlocked.test(player);
    }

    void duringCleanup(
        T player,
        PlayerSessionContext context,
        long expectedFence,
        BiPredicate<T, Identity> authorityCheck,
        Runnable cleanup
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(authorityCheck, "authorityCheck");
        Objects.requireNonNull(cleanup, "cleanup");
        Token<T> previous = current.get();
        Identity identity = new Identity(
            context.uuid(), context.localSessionId(), context.generation(), expectedFence);
        current.set(new Token<>(Thread.currentThread(), player, context, identity, authorityCheck));
        try {
            cleanup.run();
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    record Identity(UUID uuid, UUID localSessionId, long generation, long fence) {
        Identity {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(localSessionId, "localSessionId");
        }
    }

    private record Token<T>(
        Thread thread,
        T player,
        PlayerSessionContext context,
        Identity identity,
        BiPredicate<T, Identity> authorityCheck
    ) {}
}
