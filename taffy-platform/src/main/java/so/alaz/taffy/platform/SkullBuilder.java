package so.alaz.taffy.platform;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Builds {@code PLAYER_HEAD} items from a texture, a texture URL, a player name or a uuid.
 *
 * <p>{@link #fromTexture} and {@link #fromUrl} are synchronous and need no network: the head
 * carries a texture property and the client fetches the image from Mojang's CDN.
 * {@link #fromName} and {@link #fromUuid} resolve the player's current skin from Mojang on the
 * executor given to the constructor. The returned future completes on that executor's thread, or
 * on the calling thread when the texture is already cached, so inventory or entity work must be
 * scheduled back onto the owning thread.
 *
 * <p>Lookups are cached per instance. A resolved texture is reused for 60 minutes from the moment its
 * lookup completed, then the next call looks the skin up again. A failed lookup, one that found no texture
 * or threw, is remembered for 2 minutes: calls for the same name or uuid in that time start no lookup and
 * complete with a blank head, or exceptionally when the lookup threw an {@link Error}. A lookup the executor
 * rejected is not remembered. The cache holds at most 1000 names and uuids, and a new one past that drops
 * the one whose lookup started longest ago.
 *
 * <p>A lookup the executor accepted but never runs, as can happen to a task scheduled just before the owning
 * plugin disables, leaves its future and every later future for the same name or uuid from this instance
 * incomplete. A lookup that throws an {@link Error} completes its future exceptionally.
 *
 * <p>{@link #fromName} and {@link #fromUuid} may be called from any thread.
 */
public final class SkullBuilder {

    static final Duration RESOLVED_TTL = Duration.ofMinutes(60);
    static final Duration FAILED_TTL = Duration.ofMinutes(2);
    static final int MAX_CACHED = 1000;

    private final Executor lookups;
    private final LongSupplier nanoClock;
    // Insertion order is the order the lookups started in. Guarded by itself.
    private final Map<String, Lookup> textureCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lookup> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /**
     * A builder whose {@link #fromName} and {@link #fromUuid} lookups run on {@code lookups}. A lookup
     * blocks on the network, so the executor must run its tasks off the calling thread. The executor
     * must throw {@link RejectedExecutionException} when it cannot take a task, as
     * {@link Scheduler#asyncExecutor()} does: the lookup then completes with a blank head.
     */
    public SkullBuilder(Executor lookups) {
        this(lookups, System::nanoTime);
    }

    /** As {@link #SkullBuilder(Executor)}, with the age of a cached lookup measured on {@code nanoClock}. */
    SkullBuilder(Executor lookups, LongSupplier nanoClock) {
        this.lookups = lookups;
        this.nanoClock = nanoClock;
    }

    /** A head carrying the given base64 {@code textures} property value (the {@code eyJ0ZXh0...} form). */
    public static ItemStack fromTexture(String base64) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        // Deterministic profile id so identical textures yield identical, stackable heads.
        PlayerProfile profile = Bukkit.createProfile(
            UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8)));
        profile.setProperty(new ProfileProperty("textures", base64));
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A head for a Minecraft texture URL (e.g. {@code http://textures.minecraft.net/texture/...}).
     * Throws {@link IllegalArgumentException} when the URL contains a double quote or a backslash.
     */
    public static ItemStack fromUrl(String url) {
        return fromTexture(base64FromUrl(url));
    }

    /**
     * A head for the name's skin, resolved from Mojang on the lookup executor and cached for 60 minutes.
     * Completes with a blank head if the name cannot be resolved, a lookup of it failed in the last 2 minutes,
     * or the executor rejects the lookup.
     */
    public CompletableFuture<ItemStack> fromName(String name) {
        return resolveTexture("name:" + name.toLowerCase(Locale.ROOT), () -> {
            PlayerProfile profile = Bukkit.createProfile(name);
            profile.complete(true);
            return textureValue(profile);
        }).thenApply(SkullBuilder::headOrBlank);
    }

    /**
     * A head for the uuid's skin, resolved from Mojang on the lookup executor and cached for 60 minutes.
     * Completes with a blank head if the uuid cannot be resolved, a lookup of it failed in the last 2 minutes,
     * or the executor rejects the lookup.
     */
    public CompletableFuture<ItemStack> fromUuid(UUID uuid) {
        return resolveTexture("uuid:" + uuid, () -> {
            PlayerProfile profile = Bukkit.createProfile(uuid);
            profile.complete(true);
            return textureValue(profile);
        }).thenApply(SkullBuilder::headOrBlank);
    }

    /** The value of the profile's {@code textures} property, or {@code null} when it has none. */
    static @Nullable String textureValue(PlayerProfile profile) {
        return profile.getProperties().stream()
            .filter(property -> property.getName().equals("textures"))
            .map(ProfileProperty::getValue)
            .findFirst()
            .orElse(null);
    }

    private static ItemStack headOrBlank(@Nullable String texture) {
        return texture != null ? fromTexture(texture) : new ItemStack(Material.PLAYER_HEAD);
    }

    private static String base64FromUrl(String url) {
        // The URL is written into the JSON unescaped.
        if (url.indexOf('"') >= 0 || url.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("A texture URL cannot contain a double quote or a backslash: " + url);
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The number of names and uuids in the cache, expired ones included. */
    int cached() {
        synchronized (textureCache) {
            return textureCache.size();
        }
    }

    private CompletableFuture<String> resolveTexture(String key, Supplier<String> loader) {
        Lookup lookup = new Lookup();
        synchronized (textureCache) {
            Lookup cached = textureCache.get(key);
            if (cached != null && !cached.expired(nanoClock.getAsLong())) {
                return cached.texture;
            }
            // A put on a present key keeps its place in the insertion order.
            textureCache.remove(key);
            textureCache.put(key, lookup);
        }
        try {
            lookups.execute(() -> {
                String texture;
                try {
                    texture = loader.get();
                } catch (RuntimeException ex) {
                    texture = null;
                } catch (Throwable error) {
                    lookup.expireAfter(FAILED_TTL, nanoClock.getAsLong());
                    lookup.texture.completeExceptionally(error);
                    return;
                }
                lookup.expireAfter(texture != null ? RESOLVED_TTL : FAILED_TTL, nanoClock.getAsLong());
                lookup.texture.complete(texture);
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (textureCache) {
                textureCache.remove(key, lookup);
            }
            lookup.texture.complete(null);
        }
        return lookup.texture;
    }

    private static final class Lookup {

        final CompletableFuture<String> texture = new CompletableFuture<>();
        // Written before texture completes.
        private volatile long expiryNanos;

        void expireAfter(Duration ttl, long nowNanos) {
            expiryNanos = nowNanos + ttl.toNanos();
        }

        boolean expired(long nowNanos) {
            return texture.isDone() && nowNanos - expiryNanos >= 0;
        }
    }
}
