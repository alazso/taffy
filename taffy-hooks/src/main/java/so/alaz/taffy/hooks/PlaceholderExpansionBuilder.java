package so.alaz.taffy.hooks;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects a plugin's own placeholders and registers them with PlaceholderAPI and MiniPlaceholders, each when it is
 * enabled and its API classes are present. With the identifier {@code shop}, the name {@code balance} is reachable as
 * {@code %shop_balance%} in PlaceholderAPI and {@code <shop_balance>} in MiniPlaceholders.
 *
 * <p>A name holds only the lower case letters a to z, the digits, {@code _} and {@code -}, the characters a
 * MiniMessage tag allows, so every player and global placeholder resolves in both backends. In MiniPlaceholders a
 * player and a global placeholder of the same name collide, and which one answers is unspecified. A handler that
 * returns {@code null} gives no value. A handler that throws a {@link RuntimeException} gives no value either, and
 * the failure is logged as a warning, at most once per minute for each placeholder. MiniPlaceholders inserts a value
 * as plain text, so markup in it is shown as written. Offline placeholders are registered with PlaceholderAPI only,
 * since MiniPlaceholders resolves against an online audience.
 *
 * <p>A prefix placeholder answers every name that starts with its prefix and a {@code _}, and its handler takes the
 * rest of the name: with the prefix {@code keys}, {@code %shop_keys_vote%} gives the handler {@code vote}. In
 * PlaceholderAPI a placeholder registered by its whole name answers first, then the longest prefix that fits, and
 * no shorter prefix is asked when that one gives no value. The rest is passed as written, in the case it was typed
 * in, and is empty for {@code %shop_keys_%}. In MiniPlaceholders the prefix is the tag and the rest is its
 * argument, {@code <shop_keys:vote>}, with several arguments joined by {@code :}; the tag without an argument
 * goes to the placeholder of that whole name, when there is one, and otherwise gives no value. The rest is text a
 * player may have typed, so a handler treats it as untrusted.
 *
 * <p>Registering replaces any PlaceholderAPI expansion that already uses the identifier. MiniPlaceholders refuses an
 * identifier another expansion already uses, which gives it {@link HookState#FAILED}.
 *
 * <p>The plugin that registers lists PlaceholderAPI and MiniPlaceholders in its {@code paper-plugin.yml} under
 * {@code dependencies.server}, with {@code load: BEFORE} and {@code required: false}, so their APIs are visible to it
 * and they are enabled first when they are installed. The plugin that owns the placeholders is given to
 * {@link #register(Plugin, ErrorReports, boolean)}: its name gives the default identifier, and its authors and
 * version describe the expansion.
 *
 * <p>The builder is not thread-safe. PlaceholderAPI may call a handler from any thread, so handlers must be
 * thread-safe.
 */
public final class PlaceholderExpansionBuilder {

    private static final String PLACEHOLDERAPI = "PlaceholderAPI";

    private static final String MINIPLACEHOLDERS = "MiniPlaceholders";

    private static final String PLACEHOLDERAPI_PROBE = "me.clip.placeholderapi.expansion.PlaceholderExpansion";

    private static final String MINIPLACEHOLDERS_PROBE = "io.github.miniplaceholders.api.Expansion";

    private @Nullable String identifier;
    private final Map<String, Function<Player, @Nullable String>> players = new LinkedHashMap<>();
    private final Map<String, Function<OfflinePlayer, @Nullable String>> offline = new LinkedHashMap<>();
    private final Map<String, Supplier<@Nullable String>> globals = new LinkedHashMap<>();
    private final Map<String, BiFunction<Player, String, @Nullable String>> playerPrefixes = new LinkedHashMap<>();
    private final Map<String, BiFunction<OfflinePlayer, String, @Nullable String>> offlinePrefixes =
        new LinkedHashMap<>();
    private final Map<String, Function<String, @Nullable String>> globalPrefixes = new LinkedHashMap<>();

    /**
     * Starts an empty set of placeholders. Unless {@link #identifier(String)} sets one, the identifier is the name of
     * the plugin given to {@link #register(Plugin, ErrorReports, boolean)} in lower case, keeping only the letters
     * {@code a} to {@code z} and the digits.
     */
    public PlaceholderExpansionBuilder() {
    }

    static String defaultIdentifier(String name) {
        StringBuilder kept = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    /**
     * Sets the identifier that prefixes every placeholder. It is kept in lower case, as PlaceholderAPI keeps it.
     *
     * @param identifier the identifier, in any case
     * @return this builder
     * @throws IllegalArgumentException if {@code identifier} is empty or holds a character other than the letters
     *     {@code a} to {@code z} in either case, the digits and {@code -}; PlaceholderAPI reads {@code _} as the end
     *     of the identifier, and a MiniMessage tag allows no other character
     */
    public PlaceholderExpansionBuilder identifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isEmpty() || !identifier.chars().allMatch(PlaceholderExpansionBuilder::identifierChar)) {
            throw new IllegalArgumentException("invalid identifier '" + identifier + "': expected one or more of the"
                + " letters a to z in either case, the digits and '-'");
        }
        this.identifier = identifier.toLowerCase(Locale.ROOT);
        return this;
    }

    private static boolean identifierChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    }

    private static String checkedName(String name) {
        return checked("name", name);
    }

    private static String checkedPrefix(String prefix) {
        checked("prefix", prefix);
        if (prefix.endsWith("_")) {
            throw new IllegalArgumentException("invalid placeholder prefix '" + prefix + "': expected no '_' at the"
                + " end, since the '_' between the prefix and the rest of the name is added");
        }
        return prefix;
    }

    private static String checked(String kind, String name) {
        Objects.requireNonNull(name, kind);
        if (name.isEmpty() || !name.chars().allMatch(PlaceholderExpansionBuilder::nameChar)) {
            throw new IllegalArgumentException("invalid placeholder " + kind + " '" + name + "': expected one or more"
                + " of the letters a to z in lower case, the digits, '_' and '-'");
        }
        return name;
    }

    private static boolean nameChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /**
     * Adds a placeholder resolved for an online player. In PlaceholderAPI it gives no value for a player who is not
     * online. A later placeholder of the same kind and name replaces this one.
     *
     * @param name the name after the identifier
     * @param handler gives the value for the player, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is empty or holds a character other than the letters
     *     {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder player(String name, Function<Player, @Nullable String> handler) {
        players.put(checkedName(name), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Adds a placeholder resolved for a player who may be offline. It is registered with PlaceholderAPI only. A later
     * placeholder of the same kind and name replaces this one.
     *
     * @param name the name after the identifier
     * @param handler gives the value for the player, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is empty or holds a character other than the letters
     *     {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder offline(String name, Function<OfflinePlayer, @Nullable String> handler) {
        offline.put(checkedName(name), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Adds a placeholder with no player. In PlaceholderAPI a global placeholder wins over an offline or player
     * placeholder of the same name, and an offline one over a player one. A later placeholder of the same kind and
     * name replaces this one.
     *
     * @param name the name after the identifier
     * @param handler gives the value, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code name} is empty or holds a character other than the letters
     *     {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder global(String name, Supplier<@Nullable String> handler) {
        globals.put(checkedName(name), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Adds a prefix placeholder resolved for an online player, as the class description states. In PlaceholderAPI
     * it gives no value for a player who is not online. A later prefix placeholder of the same kind and prefix
     * replaces this one.
     *
     * @param prefix the start of the name after the identifier, without the {@code _} that ends it
     * @param handler gives the value for the player and the rest of the name, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code prefix} is empty, ends with {@code _} or holds a character other
     *     than the letters {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder playerPrefix(String prefix,
                                                    BiFunction<Player, String, @Nullable String> handler) {
        playerPrefixes.put(checkedPrefix(prefix), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Adds a prefix placeholder resolved for a player who may be offline, as the class description states. It is
     * registered with PlaceholderAPI only. A later prefix placeholder of the same kind and prefix replaces this one.
     *
     * @param prefix the start of the name after the identifier, without the {@code _} that ends it
     * @param handler gives the value for the player and the rest of the name, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code prefix} is empty, ends with {@code _} or holds a character other
     *     than the letters {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder offlinePrefix(String prefix,
                                                     BiFunction<OfflinePlayer, String, @Nullable String> handler) {
        offlinePrefixes.put(checkedPrefix(prefix), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Adds a prefix placeholder with no player, as the class description states. In PlaceholderAPI a global prefix
     * placeholder wins over an offline or player one of the same prefix, and an offline one over a player one. A
     * later prefix placeholder of the same kind and prefix replaces this one.
     *
     * @param prefix the start of the name after the identifier, without the {@code _} that ends it
     * @param handler gives the value for the rest of the name, or {@code null} for no value
     * @return this builder
     * @throws IllegalArgumentException if {@code prefix} is empty, ends with {@code _} or holds a character other
     *     than the letters {@code a} to {@code z} in lower case, the digits, {@code _} and {@code -}
     */
    public PlaceholderExpansionBuilder globalPrefix(String prefix, Function<String, @Nullable String> handler) {
        globalPrefixes.put(checkedPrefix(prefix), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Registers the placeholders added so far with PlaceholderAPI and MiniPlaceholders, PlaceholderAPI first, each
     * when the owner switched placeholders on, it is installed and enabled, and its API classes are visible to the
     * owner. Placeholders added afterwards are not registered. A backend that refuses the placeholders or throws is
     * {@link HookState#FAILED}: it is logged as a warning, reported to {@code errors} under
     * {@code hook.placeholderapi} or {@code hook.miniplaceholders}, its status carries the warning's text as its
     * reason, and the other backend is still registered. One that is installed but not enabled, not visible to the
     * owner, or lacking the API is skipped with a warning; one that is not installed is skipped silently. Call on the
     * server thread, in {@code onEnable}: PlaceholderAPI fires a synchronous event when it registers.
     *
     * @param owner the plugin that owns the placeholders: its server's plugin manager is searched, its logger receives
     *     every warning, its name gives the default identifier, and its authors and version describe the expansion
     * @param errors receives a backend that refused the placeholders or threw while registering them
     * @param switchedOn whether the owner switched placeholders on; {@code false} gives both backends
     *     {@link HookState#SWITCHED_OFF} and looks at neither
     * @return the registration, which the plugin unregisters when it disables
     * @throws IllegalStateException if no identifier was set and the owner's name holds no letter {@code a} to
     *     {@code z} and no digit, also when switched off
     * @throws NullPointerException if {@code owner} or {@code errors} is {@code null}
     */
    public PlaceholderRegistration register(Plugin owner, ErrorReports errors, boolean switchedOn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Logger logger = owner.getLogger();
        return register(owner, errors, switchedOn, new PluginPresence(owner.getServer().getPluginManager()),
            logger);
    }

    PlaceholderRegistration register(Plugin owner, ErrorReports errors, boolean switchedOn, Presence presence,
                                     Logger logger) {
        String id = identifier != null ? identifier : defaultIdentifier(owner.getName());
        if (id.isEmpty()) {
            throw new IllegalStateException("no identifier: the plugin name holds no letter a to z and no digit,"
                + " so set one with identifier(String)");
        }
        ThrowLog failures = new ThrowLog(logger);
        Map<String, Function<Player, @Nullable String>> playerHandlers = new LinkedHashMap<>();
        players.forEach((name, handler) -> playerHandlers.put(name,
            player -> guarded(failures, id, name, () -> handler.apply(player))));
        Map<String, Function<OfflinePlayer, @Nullable String>> offlineHandlers = new LinkedHashMap<>();
        offline.forEach((name, handler) -> offlineHandlers.put(name,
            player -> guarded(failures, id, name, () -> handler.apply(player))));
        Map<String, Supplier<@Nullable String>> globalHandlers = new LinkedHashMap<>();
        globals.forEach((name, handler) -> globalHandlers.put(name, () -> guarded(failures, id, name, handler)));
        Map<String, BiFunction<Player, String, @Nullable String>> playerPrefixHandlers = new LinkedHashMap<>();
        playerPrefixes.forEach((prefix, handler) -> playerPrefixHandlers.put(prefix,
            (player, rest) -> guardedPrefix(failures, id, prefix, () -> handler.apply(player, rest))));
        Map<String, BiFunction<OfflinePlayer, String, @Nullable String>> offlinePrefixHandlers = new LinkedHashMap<>();
        offlinePrefixes.forEach((prefix, handler) -> offlinePrefixHandlers.put(prefix,
            (player, rest) -> guardedPrefix(failures, id, prefix, () -> handler.apply(player, rest))));
        Map<String, Function<String, @Nullable String>> globalPrefixHandlers = new LinkedHashMap<>();
        globalPrefixes.forEach((prefix, handler) -> globalPrefixHandlers.put(prefix,
            rest -> guardedPrefix(failures, id, prefix, () -> handler.apply(rest))));
        PlaceholderPrefixes prefixes = new PlaceholderPrefixes(Map.copyOf(playerPrefixHandlers),
            Map.copyOf(offlinePrefixHandlers), Map.copyOf(globalPrefixHandlers));

        Detected<Runnable> placeholderApi = backend(PLACEHOLDERAPI, PLACEHOLDERAPI_PROBE, id, switchedOn, presence,
            logger, errors, () -> PapiExpansion.register(id, author(owner), owner.getPluginMeta().getVersion(),
                Map.copyOf(playerHandlers), Map.copyOf(offlineHandlers), Map.copyOf(globalHandlers), prefixes, logger));
        Detected<Runnable> miniPlaceholders = backend(MINIPLACEHOLDERS, MINIPLACEHOLDERS_PROBE, id, switchedOn,
            presence, logger, errors, () -> MiniExpansion.register(id, author(owner),
                owner.getPluginMeta().getVersion(), Map.copyOf(playerHandlers), Map.copyOf(globalHandlers), prefixes));
        return new PlaceholderRegistration(placeholderApi, miniPlaceholders, logger);
    }

    // A register action that returns null was refused; the backend has already logged the refusal.
    private static Detected<Runnable> backend(String backend, String probe, String id, boolean switchedOn,
                                              Presence presence, Logger logger, ErrorReports errors,
                                              Supplier<@Nullable Runnable> register) {
        if (!switchedOn) {
            return new Detected<>(new HookStatus(backend, HookState.SWITCHED_OFF, null, null), null);
        }
        HookStatus stopped = Detection.check(backend, probe, presence, logger, errors);
        if (stopped != null) {
            if (stopped.state() == HookState.NOT_ENABLED) {
                logger.warning(backend + " is installed but not enabled, so the placeholders are not registered with"
                    + " it.");
            }
            return new Detected<>(stopped, null);
        }
        String version = presence.version(backend);
        Runnable action;
        try {
            action = register.get();
        } catch (Exception | LinkageError failure) {
            String reason = backend + " could not register the placeholders (" + failure + ").";
            logger.log(Level.WARNING, reason, failure);
            errors.report(Detection.source(backend), failure);
            return new Detected<>(new HookStatus(backend, HookState.FAILED, version, reason), null);
        }
        if (action == null) {
            String reason = backend + " refused the placeholders under the identifier " + id + ".";
            errors.report(Detection.source(backend), new IllegalStateException(reason));
            return new Detected<>(new HookStatus(backend, HookState.FAILED, version, reason), null);
        }
        return new Detected<>(new HookStatus(backend, HookState.ON, version, null), action, presence.plugin(backend),
            () -> presence.plugin(backend));
    }

    private static String author(Plugin owner) {
        List<String> authors = owner.getPluginMeta().getAuthors();
        return authors.isEmpty() ? owner.getName() : String.join(", ", authors);
    }

    private static @Nullable String guarded(ThrowLog failures, String identifier, String name,
                                            Supplier<@Nullable String> handler) {
        try {
            return handler.get();
        } catch (RuntimeException failure) {
            failures.warn(identifier + "_" + name, "The placeholder " + identifier + "_" + name
                + " failed, so it gives no value (" + failure + ").", failure);
            return null;
        }
    }

    // The source ends in '*', which no name holds, so a prefix and a whole name never share one.
    private static @Nullable String guardedPrefix(ThrowLog failures, String identifier, String prefix,
                                                  Supplier<@Nullable String> handler) {
        try {
            return handler.get();
        } catch (RuntimeException failure) {
            failures.warn(identifier + "_" + prefix + "_*", "The prefix placeholder " + identifier + "_" + prefix
                + "_ failed, so it gives no value (" + failure + ").", failure);
            return null;
        }
    }
}
