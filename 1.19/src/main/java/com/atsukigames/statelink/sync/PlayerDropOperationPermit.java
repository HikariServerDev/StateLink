package com.atsukigames.statelink.sync;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Session identity captured for the duration of one ServerPlayerEntity.dropItem
 * call. The final world-spawn hook must match this token and recheck live
 * authority; a prior method-entry check is not carried across a lease renewal,
 * session replacement, or stale callback.
 */
public final class PlayerDropOperationPermit<T> {
    private final ThreadLocal<Token<T>> current = new ThreadLocal<>();

    Scope<T> duringDrop(
        T player,
        boolean managed,
        boolean allowedAtEntry,
        PlayerSessionContext context,
        DisconnectDropPermit.Identity identity
    ) {
        Objects.requireNonNull(player, "player");
        Token<T> previous = current.get();
        Token<T> token = new Token<>(
            Thread.currentThread(), player, managed, allowedAtEntry, context, identity);
        current.set(token);
        return new Scope<>(this, token, previous);
    }

    public static <T> Scope<T> unmanagedScope(T player) {
        Objects.requireNonNull(player, "player");
        return Scope.noop();
    }

    boolean isBlocked(
        T player,
        BiPredicate<T, CapturedAuthority> currentAuthority,
        Predicate<T> normallyBlocked
    ) {
        Token<T> token = current.get();
        if (token == null) return normallyBlocked.test(player);
        if (token.thread != Thread.currentThread() || token.player != player) return true;
        if (!token.managed) return false;
        if (!token.allowedAtEntry || token.context == null || token.identity == null) return true;
        return !currentAuthority.test(player, new CapturedAuthority(token.context, token.identity));
    }

    record CapturedAuthority(
        PlayerSessionContext context,
        DisconnectDropPermit.Identity identity
    ) {}

    private record Token<T>(
        Thread thread,
        T player,
        boolean managed,
        boolean allowedAtEntry,
        PlayerSessionContext context,
        DisconnectDropPermit.Identity identity
    ) {}

    public static final class Scope<T> implements AutoCloseable {
        private final PlayerDropOperationPermit<T> owner;
        private final Token<T> token;
        private final Token<T> previous;
        private boolean closed;

        private Scope(
            PlayerDropOperationPermit<T> owner,
            Token<T> token,
            Token<T> previous
        ) {
            this.owner = owner;
            this.token = token;
            this.previous = previous;
        }

        private Scope() {
            this.owner = null;
            this.token = null;
            this.previous = null;
        }

        private static <T> Scope<T> noop() {
            return new Scope<>();
        }

        public boolean allowedAtEntry() {
            return owner == null || !token.managed || token.allowedAtEntry;
        }

        @Override
        public void close() {
            if (closed || owner == null) return;
            closed = true;
            if (owner.current.get() == token) {
                if (previous == null) owner.current.remove();
                else owner.current.set(previous);
            } else {
                // Defensive leak prevention if a third-party nested call unwound
                // incorrectly: never let this thread reuse a stale authorization.
                owner.current.remove();
            }
        }
    }
}
