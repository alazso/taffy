package so.alaz.taffy.platform;

import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.PreProcess;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Renders MiniMessage strings (RGB, gradients, PlaceholderAPI, MiniPlaceholders) into
 * Adventure {@link Component}s.
 *
 * <p>PlaceholderAPI placeholders are resolved first, then the result is parsed as MiniMessage, with
 * MiniPlaceholders' component-safe tags added as resolvers. Both integrations are optional: when a
 * plugin is absent, its pass is skipped.
 *
 * <p>MiniMessage refuses a section sign followed by a legacy code character, which is what many
 * PlaceholderAPI expansions return. Every render therefore converts such a code to its tag, as
 * {@link LegacyText#sectionToMiniMessage} does: in the input, in what PlaceholderAPI put into it, and in
 * each value supplied as {@code Placeholder.parsed}. A converted code such as {@code <red>} in a parsed
 * value is an open tag, so it colours the rest of the template after the value, as the legacy code would.
 * A value supplied as {@code Placeholder.unparsed} or {@code Placeholder.component} is inserted as it is,
 * section sign included.
 *
 * <p>An instance is thread-safe and may be shared. A PlaceholderAPI pass that throws, and a
 * MiniPlaceholders resolver that cannot be built or that throws while the text is parsed, is skipped and
 * logged as {@link ThrowLog} does, under the sources {@code placeholderapi} and {@code miniplaceholders};
 * nothing is reported to {@link ErrorReports}. A single MiniPlaceholders tag that throws while it resolves
 * is left as written by MiniMessage and is not logged. PlaceholderAPI expansions and MiniPlaceholders
 * audience tags run on the calling thread and read the viewer, so a call that passes a viewer belongs on
 * the thread that owns that player.
 */
public final class Text {

    private static final Pattern BRACED_NAME = Pattern.compile("\\{([a-z0-9_-]+)}");
    private static final String PLACEHOLDER_API = "placeholderapi";
    private static final String MINI_PLACEHOLDERS = "miniplaceholders";
    private static final String MINI_PLACEHOLDERS_FAILED =
        "MiniPlaceholders failed, so the text is shown without its placeholders.";

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final @Nullable BiFunction<Player, String, String> placeholderApi;
    private final @Nullable Supplier<@Nullable TagResolver> miniPlaceholders;
    private final ThrowLog failures;

    /**
     * Detects PlaceholderAPI and MiniPlaceholders once, by class presence. A lookup that fails is
     * reported to {@code logger} and leaves that integration off.
     */
    public Text(Logger logger) {
        this.failures = new ThrowLog(logger);
        this.placeholderApi = Classes.present("me.clip.placeholderapi.PlaceholderAPI", getClass(), logger)
            ? (viewer, input) -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, input)
            : null;
        this.miniPlaceholders = Classes.present("io.github.miniplaceholders.api.MiniPlaceholders", getClass(), logger)
            ? () -> MiniPlaceholders.audienceGlobalPlaceholders()
            : null;
    }

    /** Takes each integration's pass directly. {@code null} means the plugin is absent. */
    Text(
        Logger logger,
        @Nullable BiFunction<Player, String, String> placeholderApi,
        @Nullable Supplier<@Nullable TagResolver> miniPlaceholders
    ) {
        this.failures = new ThrowLog(logger);
        this.placeholderApi = placeholderApi;
        this.miniPlaceholders = miniPlaceholders;
    }

    /** Parses the input as MiniMessage, without PlaceholderAPI resolution. */
    public Component render(String input) {
        return deserialize(applyPlaceholders(input, null), null, TagResolver.empty());
    }

    /**
     * Resolves PlaceholderAPI placeholders against the viewer (when non-null and PlaceholderAPI is
     * present), then parses the result as MiniMessage.
     */
    public Component render(String input, @Nullable Player viewer) {
        return deserialize(applyPlaceholders(input, viewer), viewer, TagResolver.empty());
    }

    /**
     * Renders a template with values. A value is written in the template as {@code <name>}, or as
     * {@code {name}}, which reads as {@code <name>} for every name {@code values} supplies. A
     * {@code {name}} that no value supplies stays as written. Inside a tag argument that MiniMessage
     * does not parse, such as a click value, a supplied {@code {name}} becomes the literal text
     * {@code <name>} and is not filled in. A supplied {@code {name}} written as an unquoted tag
     * argument, as in {@code <hover:show_text:{name}>}, leaves a tag MiniMessage cannot read, which is
     * then shown as text.
     *
     * <p>PlaceholderAPI runs on the template only, before MiniMessage parses it. MiniMessage inserts
     * the values afterwards, so PlaceholderAPI never sees them. A supplied {@code {name}} inside a
     * {@code %...%} placeholder is rewritten to {@code <name>} as well, so that placeholder does not
     * receive the value. A value that carries PlaceholderAPI placeholders of its own is passed
     * through {@link #resolve(String, Player)} by the caller before it is supplied.
     *
     * <p>The caller picks how each value goes in: {@code Placeholder.component} or
     * {@code Placeholder.unparsed} for player-typed text, which is then inserted as it is, and
     * {@code Placeholder.parsed} for a value that carries MiniMessage on purpose. A value wins over a
     * MiniPlaceholders tag of the same name.
     *
     * @throws ParsingException when the text still holds a section sign followed by a legacy code character
     *     once the values are inserted, as when a parsed value that ends in a section sign is followed by a code
     *     character in the template
     */
    public Component render(String template, @Nullable Player viewer, TagResolver... values) {
        TagResolver supplied = TagResolver.resolver(values);
        return deserialize(applyPlaceholders(aliasBraces(template, supplied), viewer), viewer, supplied);
    }

    /** Renders each line as {@link #render(String, Player)} does, in order. */
    public List<Component> render(List<String> lines, @Nullable Player viewer) {
        return lines.stream().map(line -> render(line, viewer)).toList();
    }

    /**
     * Renders {@code miniMessage} with {@code player} as the viewer, as {@link #render(String, Player)}
     * does, and sends the result to that player. Belongs on the thread that owns the player.
     */
    public void send(Player player, String miniMessage) {
        player.sendMessage(render(miniMessage, player));
    }

    /**
     * Resolves PlaceholderAPI placeholders in the input against the viewer, without MiniMessage
     * parsing. Returns the input unchanged when PlaceholderAPI is absent or throws, and logs the
     * failure, or the viewer is null. The result is what PlaceholderAPI returned, with any
     * section-sign code still in it.
     */
    public String resolve(String input, @Nullable Player viewer) {
        return applyPlaceholders(input, viewer);
    }

    /** Renders the input (PlaceholderAPI, then MiniMessage) and flattens it to plain text. */
    public String plain(String input, @Nullable Player viewer) {
        return PlainTextComponentSerializer.plainText().serialize(render(input, viewer));
    }

    /**
     * Rewrites {@code {name}} to {@code <name>} for every name {@code values} has. Only a name made
     * of lower-case letters, digits, {@code _} and {@code -} is looked at.
     */
    static String aliasBraces(String template, TagResolver values) {
        return BRACED_NAME.matcher(template).replaceAll(
            match -> values.has(match.group(1)) ? "<" + match.group(1) + ">" : match.group());
    }

    /**
     * Wraps {@code resolver} so that the value of each pre-process tag it returns, such as a
     * {@code Placeholder.parsed} value, has its section-sign codes converted as
     * {@link LegacyText#sectionToMiniMessage} converts them. Every other tag is returned as it is.
     */
    static TagResolver sectionSafe(TagResolver resolver) {
        return new TagResolver() {
            @Override
            public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context ctx) throws ParsingException {
                Tag tag = resolver.resolve(name, arguments, ctx);
                return tag instanceof PreProcess pre
                    ? Tag.preProcessParsed(LegacyText.sectionToMiniMessage(pre.value()))
                    : tag;
            }

            @Override
            public boolean has(String name) {
                return resolver.has(name);
            }
        };
    }

    /**
     * Parses already-resolved text as MiniMessage with MiniPlaceholders' resolvers when installed,
     * falling back to parsing without them if an expansion throws. The failure is logged only when
     * the fallback parse succeeds; when it throws as well, its exception propagates.
     */
    private Component deserialize(String placeholdersApplied, @Nullable Player viewer, TagResolver values) {
        String resolved = LegacyText.sectionToMiniMessage(placeholdersApplied);
        TagResolver expansions = miniPlaceholderResolver();
        if (expansions == null) {
            return parse(resolved, viewer, values);
        }
        try {
            // The later resolver answers first, so a supplied value wins a name both have.
            return parse(resolved, viewer, TagResolver.resolver(expansions, values));
        } catch (RuntimeException ex) {
            Component fallback = parse(resolved, viewer, values);
            failures.warn(MINI_PLACEHOLDERS, MINI_PLACEHOLDERS_FAILED, ex);
            return fallback;
        }
    }

    /** Parses with the viewer as the target that audience placeholders read, when there is one. */
    private Component parse(String resolved, @Nullable Player viewer, TagResolver resolver) {
        TagResolver safe = sectionSafe(resolver);
        return viewer == null ? mm.deserialize(resolved, safe) : mm.deserialize(resolved, viewer, safe);
    }

    /**
     * MiniPlaceholders' resolver for global and audience placeholders, built per render so
     * expansions registered later are included. {@code null} when MiniPlaceholders is absent or
     * throws while building it, which is logged.
     */
    private @Nullable TagResolver miniPlaceholderResolver() {
        if (miniPlaceholders == null) {
            return null;
        }
        try {
            return miniPlaceholders.get();
        } catch (LinkageError | RuntimeException ex) {
            failures.warn(MINI_PLACEHOLDERS, MINI_PLACEHOLDERS_FAILED, ex);
            return null;
        }
    }

    private String applyPlaceholders(String input, @Nullable Player viewer) {
        if (viewer == null || placeholderApi == null) {
            return input;
        }
        try {
            return placeholderApi.apply(viewer, input);
        } catch (LinkageError | RuntimeException ex) {
            failures.warn(PLACEHOLDER_API,
                "PlaceholderAPI failed while resolving placeholders, so the text is shown without them.", ex);
            return input;
        }
    }

}
