package so.alaz.taffy.platform;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
 * <p>Resolved textures are cached per instance; failed lookups are not. A lookup the executor accepted
 * but never runs, as can happen to a task scheduled just before the owning plugin disables, leaves its
 * future and every later future for the same name or uuid from this instance incomplete. A lookup that
 * throws an {@link Error} completes its future exceptionally.
 *
 * <p>{@link #fromName} and {@link #fromUuid} may be called from any thread.
 */
public final class SkullBuilder {

    private final Executor lookups;
    private final ConcurrentHashMap<String, CompletableFuture<String>> textureCache = new ConcurrentHashMap<>();

    /**
     * A builder whose {@link #fromName} and {@link #fromUuid} lookups run on {@code lookups}. A lookup
     * blocks on the network, so the executor must run its tasks off the calling thread. The executor
     * must throw {@link RejectedExecutionException} when it cannot take a task, as
     * {@link Scheduler#asyncExecutor()} does: the lookup then completes with a blank head.
     */
    public SkullBuilder(Executor lookups) {
        this.lookups = lookups;
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
     * A head for the name's current skin, resolved from Mojang on the lookup executor and cached.
     * Completes with a blank head if the name cannot be resolved or the executor rejects the lookup.
     */
    public CompletableFuture<ItemStack> fromName(String name) {
        return resolveTexture("name:" + name.toLowerCase(Locale.ROOT), () -> {
            PlayerProfile profile = Bukkit.createProfile(name);
            profile.complete(true);
            return textureValue(profile);
        }).thenApply(SkullBuilder::headOrBlank);
    }

    /**
     * A head for the uuid's current skin, resolved from Mojang on the lookup executor and cached.
     * Completes with a blank head if the uuid cannot be resolved or the executor rejects the lookup.
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

    private CompletableFuture<String> resolveTexture(String key, Supplier<String> loader) {
        CompletableFuture<String> lookup = textureCache.computeIfAbsent(key, cacheKey -> {
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return loader.get();
                    } catch (RuntimeException ex) {
                        return null;
                    }
                }, lookups);
            } catch (RejectedExecutionException rejected) {
                return CompletableFuture.completedFuture(null);
            }
        });
        // Evicts a failed lookup so the next request retries it. Must stay outside computeIfAbsent: a
        // lookup that already completed runs this immediately, and a removal inside computeIfAbsent throws.
        lookup.whenComplete((value, error) -> {
            if (value == null) {
                textureCache.remove(key, lookup);
            }
        });
        return lookup;
    }
}
