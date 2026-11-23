package so.alaz.taffy.storage;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The store of ProVouchers 1.6.0 ported from its own JDBC connections onto {@link Tx}, over the tables
 * {@link ProVouchersDatabase} creates. The guide for porting a synchronous JDBC store in the documentation shows
 * these methods, so each sample of the guide compiles and runs here on every dialect.
 */
final class PortedVoucherStore {

    /** Thrown inside work to roll its transaction back when a cap no longer fits. */
    static final class CapReached extends SQLException {

        CapReached(String key) {
            super("the cap of " + key + " no longer fits");
        }
    }

    private static final int ATTEMPTS = 32;

    private final Storage storage;

    PortedVoucherStore(Storage storage) {
        this.storage = storage;
    }

    /** Whether the stored claim of the unique id is this one, after claiming it when it was free. */
    CompletableFuture<Boolean> recordUse(String uid, UUID player, long redeemedAt) {
        return storage.async(tx -> {
            tx.update(tx.dialect().insertOrIgnore("provouchers_used_vouchers",
                    List.of("uid", "player_uuid", "redeemed_at"), List.of("uid")),
                params -> params.text(uid).text(player.toString()).integer(redeemedAt));
            return tx.queryOne("SELECT player_uuid, redeemed_at FROM provouchers_used_vouchers WHERE uid = ?",
                params -> params.text(uid),
                row -> player.equals(UUID.fromString(row.text("player_uuid")))
                    && Long.valueOf(redeemedAt).equals(row.integer("redeemed_at"))).orElse(false);
        });
    }

    /** Stores or replaces the expiry of a player's cooldown. */
    CompletableFuture<Void> setCooldown(UUID player, String voucherId, long expiresAt) {
        return storage.async(tx -> {
            tx.update(tx.dialect().upsert("provouchers_cooldowns",
                    List.of("player_uuid", "voucher_id", "expires_at"),
                    List.of("player_uuid", "voucher_id"),
                    List.of("expires_at")),
                params -> params.text(player.toString()).text(voucherId).integer(expiresAt));
            return null;
        });
    }

    /** The cooldowns of a player that end after {@code now}, by voucher id. */
    CompletableFuture<Map<String, Long>> activeCooldowns(UUID player, long now) {
        return storage.read(tx -> {
            Map<String, Long> cooldowns = new HashMap<>();
            tx.query("SELECT voucher_id, expires_at FROM provouchers_cooldowns"
                    + " WHERE player_uuid = ? AND expires_at > ?",
                params -> params.text(player.toString()).integer(now),
                row -> cooldowns.put(row.text("voucher_id"), row.integer("expires_at")));
            return cooldowns;
        });
    }

    /**
     * Adds uses to the player's count and the key's total, each guarded by its cap. Completes with {@code false},
     * with no count changed, when either cap no longer fits.
     */
    CompletableFuture<Boolean> addUses(String key, UUID player, int amount, int perPlayerCap, int globalCap) {
        return storage.asyncRetrying(tx -> {
            ensureCounters(tx, key, player);
            int mine = tx.update("UPDATE provouchers_code_uses SET uses = uses + ?, updated_at = ?"
                    + " WHERE code = ? AND player_uuid = ? AND uses + ? <= ?",
                params -> params.intValue(amount).time(tx.now()).text(key).text(player.toString())
                    .intValue(amount).intValue(perPlayerCap));
            if (mine == 0) {
                return false;
            }
            int total = tx.update("UPDATE provouchers_use_totals SET uses = uses + ? WHERE code = ? AND uses + ? <= ?",
                params -> params.intValue(amount).text(key).intValue(amount).intValue(globalCap));
            if (total == 0) {
                throw new CapReached(key);
            }
            return true;
        }).exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException wrapped ? wrapped.getCause() : failure;
            if (cause instanceof CapReached) {
                return false;
            }
            throw failure instanceof CompletionException wrapped ? wrapped : new CompletionException(failure);
        });
    }

    /**
     * Grants up to {@code requested} uses within both caps in one transaction that locks the two counters, and
     * completes with the number granted.
     */
    CompletableFuture<Integer> reserveUses(String key, UUID player, int requested, int perPlayerCap, int globalCap) {
        return storage.asyncRetrying(tx -> {
            String locked = tx.dialect().forUpdate();
            ensureCounters(tx, key, player);
            long total = tx.queryOne("SELECT uses FROM provouchers_use_totals WHERE code = ?" + locked,
                params -> params.text(key), row -> row.integer("uses")).orElseThrow();
            int mine = tx.queryOne("SELECT uses FROM provouchers_code_uses WHERE code = ? AND player_uuid = ?" + locked,
                params -> params.text(key).text(player.toString()), row -> row.intValue("uses")).orElseThrow();
            int grant = (int) Math.max(0, Math.min(requested, Math.min(perPlayerCap - mine, globalCap - total)));
            if (grant == 0) {
                return 0;
            }
            tx.update("UPDATE provouchers_code_uses SET uses = uses + ?, updated_at = ?"
                    + " WHERE code = ? AND player_uuid = ?",
                params -> params.intValue(grant).time(tx.now()).text(key).text(player.toString()));
            tx.update("UPDATE provouchers_use_totals SET uses = uses + ? WHERE code = ?",
                params -> params.intValue(grant).text(key));
            return grant;
        });
    }

    private static void ensureCounters(Tx tx, String key, UUID player) throws SQLException {
        tx.update(tx.dialect().insertOrIgnore("provouchers_use_totals", List.of("code", "uses"), List.of("code")),
            params -> params.text(key).intValue(0));
        tx.update(tx.dialect().insertOrIgnore("provouchers_code_uses",
                List.of("code", "player_uuid", "uses", "updated_at"), List.of("code", "player_uuid")),
            params -> params.text(key).text(player.toString()).intValue(0).time(tx.now()));
    }

    /**
     * Grants up to {@code requested} uses within both caps without a lock: each attempt reads the counters in one
     * transaction and adds to them in the next, and an attempt whose caps no longer fit reads again.
     */
    CompletableFuture<Integer> reserveUsesOptimistically(String key, UUID player, int requested, int perPlayerCap,
                                                         int globalCap) {
        return attempt(key, player, requested, perPlayerCap, globalCap, ATTEMPTS);
    }

    private CompletableFuture<Integer> attempt(String key, UUID player, int requested, int perPlayerCap,
                                               int globalCap, int attemptsLeft) {
        return storage.read(tx -> {
            long total = tx.queryOne("SELECT uses FROM provouchers_use_totals WHERE code = ?",
                params -> params.text(key), row -> row.integer("uses")).orElse(0L);
            int mine = tx.queryOne("SELECT uses FROM provouchers_code_uses WHERE code = ? AND player_uuid = ?",
                params -> params.text(key).text(player.toString()), row -> row.intValue("uses")).orElse(0);
            return (int) Math.max(0, Math.min(requested, Math.min(perPlayerCap - mine, globalCap - total)));
        }).thenCompose(grant -> {
            if (grant == 0) {
                return CompletableFuture.completedFuture(0);
            }
            return addUses(key, player, grant, perPlayerCap, globalCap).thenCompose(added -> {
                if (added) {
                    return CompletableFuture.completedFuture(grant);
                }
                if (attemptsLeft == 1) {
                    return CompletableFuture.<Integer>failedFuture(new SQLException("Could not reserve uses of '" + key
                        + "': " + ATTEMPTS + " attempts in a row conflicted with concurrent writes"));
                }
                return attempt(key, player, requested, perPlayerCap, globalCap, attemptsLeft - 1);
            });
        });
    }

    /**
     * The synchronous form of {@link #recordUse} for a caller that is not on a database thread, such as a command
     * that already runs off the server thread.
     */
    boolean recordUseAndWait(String uid, UUID player, long redeemedAt) {
        if (Thread.currentThread().getName().contains("-db-")) {
            throw new IllegalStateException("a database thread must not wait for database work");
        }
        return recordUse(uid, player, redeemedAt).join();
    }
}
