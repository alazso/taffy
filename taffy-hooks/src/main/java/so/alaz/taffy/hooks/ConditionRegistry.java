package so.alaz.taffy.hooks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.platform.Text;
import so.alaz.taffy.platform.ThrowLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Condition types by name, and the conditions of a config list built from them. An owner writes a list such as
 * <pre>{@code
 * conditions:
 *   - type: permission
 *     permission: shop.vip
 *   - type: papi
 *     placeholder: "%player_level%"
 *     operator: ">="
 *     value: 10
 *     deny: "<red>You need level 10."
 * }</pre>
 * where each entry names its {@code type}, the keys of that type and, when the owner wants a message of their own,
 * a {@code deny} text. {@link #build} turns the list into one {@link Condition} when the file loads, and that
 * condition is tested for a player whenever the plugin needs the answer.
 *
 * <p>A new registry holds four types:
 * <ul>
 *   <li>{@value #PERMISSION}, with the key {@code permission}: passes when {@link Player#hasPermission(String)} is
 *     {@code true} for that node. Its default denial is the message {@value #PERMISSION_KEY}.</li>
 *   <li>{@value #WORLD}, with the list {@code worlds}: passes when the name of the player's world is one of them,
 *     written exactly as the world is named. Its default denial is {@value #WORLD_KEY}.</li>
 *   <li>{@value #PLACEHOLDER}, with the keys {@code placeholder}, {@code operator} and {@code value}: fills in the
 *     PlaceholderAPI placeholders of {@code placeholder} for the player, as {@link Text#resolve(String, Player)}
 *     does, and compares the result with {@code value}, which is used as written. Its default denial is
 *     {@value #NOT_MET_KEY}. The operator is read without regard to case and is {@code equals} when left out:
 *     {@code equals}, {@code ==} and {@code =} pass when the two texts are equal without regard to case;
 *     {@code !=}, {@code ne} and {@code not} when they are not; {@code contains} when the result holds the value,
 *     without regard to case; and {@code >} or {@code gt}, {@code >=} or {@code gte}, {@code <} or {@code lt}, and
 *     {@code <=} or {@code lte} compare two numbers. A number is read as {@link Double#parseDouble(String)} reads
 *     the text without the spaces around it, so {@code 1,000} and {@code $5} are not numbers. With one of those
 *     four operators a {@code value} that is not a number is an error when the file loads, and a result that is
 *     not a number denies. While PlaceholderAPI is not installed, or its pass throws, the placeholder stays as
 *     written and is compared that way: every operator then denies except {@code !=}, which passes, and
 *     {@code contains} with a value the written placeholder holds.</li>
 *   <li>{@value #REGION}, with the list {@code regions}: passes when one of the regions at the player's location,
 *     as {@link RegionProvider#regionsAt} gives them, is one of the list, compared with
 *     {@link String#equalsIgnoreCase(String)}. Its default denial is {@value #REGION_KEY}. While the registry has
 *     no region provider, or the provider is not {@link RegionProvider#available() available}, as with WorldGuard
 *     switched off or not installed, the condition denies with {@value #REGION_UNAVAILABLE_KEY}.</li>
 * </ul>
 * A plugin adds its own types with {@link #register}, and a type registered under one of the four names takes its
 * place.
 *
 * <p>There is no economy type. A plugin that wants one registers its own and runs it as
 * {@link EconomyProvider#thread} names for the player. A provider whose thread is the player's entity thread can be
 * asked inside {@link Condition#test}. A provider that answers on the global thread or on an asynchronous one
 * cannot: the plugin then asks for the balance on that thread first and tests the conditions once the answer is
 * back, so a check made for an event the plugin wants to cancel finishes on a later tick, after the event.
 *
 * <p>The four built-in types read the player, so the condition {@link #build} returns is tested on the thread that
 * owns the player: the main thread on Paper, the player's region thread on Folia. {@link #register},
 * {@link #isRegistered}, {@link #types} and {@link #build} may be called from any thread, and the four built-in
 * types read nothing from the server while they are built.
 *
 * @since 0.4.14
 */
public final class ConditionRegistry {

    /**
     * The name of the permission type.
     *
     * @since 0.4.14
     */
    public static final String PERMISSION = "permission";

    /**
     * The name of the world type.
     *
     * @since 0.4.14
     */
    public static final String WORLD = "world";

    /**
     * The name of the PlaceholderAPI comparison type.
     *
     * @since 0.4.14
     */
    public static final String PLACEHOLDER = "papi";

    /**
     * The name of the region type.
     *
     * @since 0.4.14
     */
    public static final String REGION = "region";

    /**
     * The message key of the permission type's default denial.
     *
     * @since 0.4.14
     */
    public static final String PERMISSION_KEY = "condition.permission";

    /**
     * The message key of the world type's default denial.
     *
     * @since 0.4.14
     */
    public static final String WORLD_KEY = "condition.world";

    /**
     * The message key of the region type's default denial.
     *
     * @since 0.4.14
     */
    public static final String REGION_KEY = "condition.region";

    /**
     * The message key of the region type's denial while no region provider is available.
     *
     * @since 0.4.14
     */
    public static final String REGION_UNAVAILABLE_KEY = "condition.region-unavailable";

    /**
     * The message key of the PlaceholderAPI comparison's default denial, and of the denial of a condition that threw
     * while it was tested.
     *
     * @since 0.4.14
     */
    public static final String NOT_MET_KEY = "condition.requirement-not-met";

    private static final String TYPE = "type";
    private static final String DENY = "deny";
    private static final String SOURCE = "condition.";
    private static final int DESCRIBE_LIMIT = 100;

    /** Renders the reason of a denial for the player who was denied. */
    interface Denials {

        Component custom(String template, Player viewer, TagResolver[] values);

        Component keyed(String key, Player viewer, TagResolver[] values);
    }

    private record Entry(String type, String path, Condition condition, ConditionDenial fallback) {
    }

    private final Map<String, ConditionType> types = new ConcurrentHashMap<>();
    private final ThrowLog throwLog;
    private final ErrorReports errors;
    private final Denials denials;

    ConditionRegistry(Logger logger, ErrorReports errors, Denials denials,
                      BiFunction<String, Player, String> placeholders,
                      Supplier<@Nullable RegionProvider> regions) {
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
        this.denials = denials;
        BuiltinConditions.register(this, placeholders, regions);
    }

    /**
     * A registry that holds the four built-in types. A denial is rendered through {@code text} for the player who
     * was denied: an entry's {@code deny} text as {@link Text#render(String, Player, TagResolver...)} renders it,
     * and a default denial as {@link Messages#render(Text, Player, String, TagResolver...)} renders its key from
     * the plugin's lang files, so the plugin's bundled English file holds {@value #PERMISSION_KEY},
     * {@value #WORLD_KEY}, {@value #REGION_KEY}, {@value #REGION_UNAVAILABLE_KEY} and {@value #NOT_MET_KEY} for the
     * types it lets owners use.
     *
     * @param logger the plugin's logger, which receives a warning when a condition type throws
     * @param errors receives what a condition type threw, under {@code condition.<type>}
     * @param text renders denials and fills in the placeholders of the {@value #PLACEHOLDER} type
     * @param messages holds the default denials
     * @param regions gives the region provider each time a {@value #REGION} condition is tested, or {@code null}
     *     while there is none, as {@code () -> detected.provider()} does for the result of
     *     {@link RegionProvider#worldGuard}
     * @return the registry
     * @throws NullPointerException if an argument is {@code null}
     * @since 0.4.14
     */
    public static ConditionRegistry create(Logger logger, ErrorReports errors, Text text, Messages messages,
                                           Supplier<@Nullable RegionProvider> regions) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(regions, "regions");
        Denials denials = new Denials() {
            @Override
            public Component custom(String template, Player viewer, TagResolver[] values) {
                return text.render(template, viewer, values);
            }

            @Override
            public Component keyed(String key, Player viewer, TagResolver[] values) {
                return messages.render(text, viewer, key, values);
            }
        };
        return new ConditionRegistry(logger, errors, denials, (input, player) -> text.resolve(input, player), regions);
    }

    /**
     * Registers a type under a name, in place of any type of that name. The name is matched without regard to case
     * and is the {@code type} an owner writes.
     *
     * @param name the type name
     * @param type builds the condition of an entry
     * @throws NullPointerException if {@code name} or {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     * @since 0.4.14
     */
    public void register(String name, ConditionType type) {
        Objects.requireNonNull(type, "type");
        types.put(key(name), type);
    }

    /**
     * Whether a type is registered under a name, without regard to case.
     *
     * @param name the type name
     * @return {@code true} if {@link #build} accepts the name
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     * @since 0.4.14
     */
    public boolean isRegistered(String name) {
        return types.containsKey(key(name));
    }

    /**
     * The registered type names, in lower case and in alphabetical order.
     *
     * @return an unmodifiable copy
     * @since 0.4.14
     */
    public Set<String> types() {
        return Collections.unmodifiableSet(new TreeSet<>(types.keySet()));
    }

    /**
     * Builds the conditions of a config list into one condition that tests them in file order and stops at the first
     * one that denies, whose result it returns. It passes when every entry passes, and when the list is absent or
     * empty.
     *
     * <p>Nothing in the list is thrown: each mistake is passed to {@code problems} as an error with its path, and
     * the entry it is in is left out of the returned condition. A plugin therefore refuses the file when
     * {@code problems} received an error, since a condition the owner wrote would otherwise not be checked. The
     * errors are: a value that is not a list; an entry that is not a group of keys; a {@code type} that is missing
     * or not registered, with the registered names as the fix; a {@code deny} that is a list or a group of keys; a
     * key of the type that is missing or unusable; and a type that throws or builds nothing, which is also logged
     * as a warning and reported to the {@link ErrorReports} under {@code condition.<type>}.
     *
     * <p>A condition that throws while the returned condition tests it, or that returns {@code null}, denies. The
     * reason is the entry's {@code deny} text, or the message {@value #NOT_MET_KEY} when it has none, and an empty
     * component when that cannot be rendered either. What was thrown is logged as a warning, at most once a minute
     * for each type with its stack the first time, and reported under {@code condition.<type>}. An {@link Error}
     * other than a {@link LinkageError} propagates.
     *
     * @param value the list as the file's parser gave it, such as {@code ConfigReader.value("conditions")} or
     *     {@code ConfigurationSection.getList("conditions")}: a {@link List} whose entries are {@link Map}s or
     *     {@link ConfigurationSection}s, or {@code null} for an absent key
     * @param path the path of the list in the file, which every error starts with
     * @param problems receives each error
     * @return the condition for the entries that were built, tested on the thread the class description names
     * @throws NullPointerException if {@code path} or {@code problems} is {@code null}
     * @since 0.4.14
     */
    public Condition build(@Nullable Object value, String path, ConditionProblems problems) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(problems, "problems");
        if (value == null) {
            return tested(List.of());
        }
        if (!(value instanceof List<?> list)) {
            problems.error(path, "is " + describe(value) + "; expected a list of conditions", null);
            return tested(List.of());
        }
        List<Entry> entries = new ArrayList<>(list.size());
        int index = 0;
        for (Object raw : list) {
            Entry entry = entry(raw, path + "[" + index++ + "]", problems);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return tested(List.copyOf(entries));
    }

    private @Nullable Entry entry(@Nullable Object raw, String path, ConditionProblems problems) {
        Map<String, Object> values = values(raw);
        if (values == null) {
            problems.error(path, "is " + describe(raw) + ", which is not a group of keys", null);
            return null;
        }
        Object named = values.get(TYPE);
        String known = "one of " + String.join(", ", types());
        if (named == null) {
            problems.error(path + "." + TYPE, "is missing", "name " + known);
            return null;
        }
        String name = ConditionConfig.scalar(named) ? String.valueOf(named).toLowerCase(Locale.ROOT) : null;
        ConditionType type = name == null ? null : types.get(name);
        if (name == null || type == null) {
            problems.error(path + "." + TYPE, "is " + describe(named) + ", which is not a registered condition type",
                "use " + known);
            return null;
        }
        Object denial = values.get(DENY);
        boolean denyUsable = denial == null || ConditionConfig.scalar(denial);
        if (!denyUsable) {
            problems.error(path + "." + DENY, "is " + describe(denial) + "; expected a text", null);
        }
        String deny = denial != null && denyUsable ? String.valueOf(denial) : null;
        ConditionConfig config = new ConditionConfig(name, path, values, deny, problems, denials);
        Condition condition = null;
        try {
            condition = type.create(config);
            if (condition == null && config.errors() == 0) {
                config.entryError("could not be built by the condition type " + name);
            }
        } catch (Exception | LinkageError ex) {
            failed(name, "build", "The condition type " + name + " could not build " + path + " (" + ex + ").", ex);
            config.entryError("could not be built, since the condition type " + name + " failed (" + ex + ")");
        }
        if (condition == null || config.errors() > 0 || !denyUsable) {
            return null;
        }
        return new Entry(name, path, condition, config.denial(NOT_MET_KEY));
    }

    private Condition tested(List<Entry> entries) {
        return player -> {
            Objects.requireNonNull(player, "player");
            for (Entry entry : entries) {
                ConditionResult result = test(entry, player);
                if (!result.passed()) {
                    return result;
                }
            }
            return ConditionResult.pass();
        };
    }

    private ConditionResult test(Entry entry, Player player) {
        try {
            return Objects.requireNonNull(entry.condition().test(player), "the condition returned no result");
        } catch (Exception | LinkageError ex) {
            failed(entry.type(), "test", "The condition " + entry.path() + " of type " + entry.type()
                + " could not be tested, so it denies (" + ex + ").", ex);
        }
        try {
            return entry.fallback().result(player);
        } catch (RuntimeException unrendered) {
            return ConditionResult.denied(Component.empty());
        }
    }

    private void failed(String type, String call, String message, Throwable thrown) {
        throwLog.warn(type + "." + call, message, thrown);
        errors.report(SOURCE + type, thrown);
    }

    private static String key(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static @Nullable Map<String, Object> values(@Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                values.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return values;
    }

    static String describe(@Nullable Object value) {
        if (value == null) {
            return "an empty entry";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        if (value instanceof Map<?, ?> || value instanceof ConfigurationSection) {
            return "a group of keys";
        }
        String written = String.valueOf(value);
        String shown = written.length() > DESCRIBE_LIMIT ? written.substring(0, DESCRIBE_LIMIT) + "..." : written;
        return value instanceof String ? "\"" + shown + "\"" : shown;
    }
}
