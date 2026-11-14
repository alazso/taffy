package so.alaz.taffy.platform;

import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * PlaceholderAPI expansions return. Every render therefore passes the text it is about to parse, the
 * input together with what PlaceholderAPI put into it, through {@link LegacyText#sectionToMiniMessage},
 * so such a code becomes its tag. A value supplied as {@code Placeholder.parsed} is not seen by that
 * step: one that holds such a code makes the render throw MiniMessage's {@code ParsingException}, so the
 * caller converts it first.
 *
 * <p>An instance holds no mutable state and may be shared. PlaceholderAPI expansions and
 * MiniPlaceholders audience tags run on the calling thread and read the viewer, so a call that
 * passes a viewer belongs on the thread that owns that player.
 */
public final class Text {

    private static final Pattern BRACED_NAME = Pattern.compile("\\{([a-z0-9_-]+)}");

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final @Nullable BiFunction<Player, String, String> placeholderApi;
    private final @Nullable Supplier<@Nullable TagResolver> miniPlaceholders;

    /**
     * Detects PlaceholderAPI and MiniPlaceholders once, by class presence. A lookup that fails is
     * reported to {@code logger} and leaves that integration off.
     */
    public Text(Logger logger) {
        this.placeholderApi = Classes.present("me.clip.placeholderapi.PlaceholderAPI", getClass(), logger)
            ? (viewer, input) -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, input)
            : null;
        this.miniPlaceholders = Classes.present("io.github.miniplaceholders.api.MiniPlaceholders", getClass(), logger)
            ? () -> MiniPlaceholders.audienceGlobalPlaceholders()
            : null;
    }

    /** Takes each integration's pass directly. {@code null} means the plugin is absent. */
    Text(
        @Nullable BiFunction<Player, String, String> placeholderApi,
        @Nullable Supplier<@Nullable TagResolver> miniPlaceholders
    ) {
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
     * through {@link #resolve(String, Player)} by the caller before it is supplied, and through
     * {@link LegacyText#sectionToMiniMessage} as well when it is supplied as {@code Placeholder.parsed}.
     *
     * <p>The caller picks how each value goes in: {@code Placeholder.component} or
     * {@code Placeholder.unparsed} for player-typed text, which is then inserted as it is, and
     * {@code Placeholder.parsed} for a value that carries MiniMessage on purpose. A value wins over a
     * MiniPlaceholders tag of the same name.
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
     * parsing. Returns the input unchanged when PlaceholderAPI is absent or throws, or the viewer is
     * null. The result is what PlaceholderAPI returned, with any section-sign code still in it.
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
     * Parses already-resolved text as MiniMessage with MiniPlaceholders' resolvers when installed,
     * falling back to parsing without them if an expansion throws.
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
            return parse(resolved, viewer, values);
        }
    }

    /** Parses with the viewer as the target that audience placeholders read, when there is one. */
    private Component parse(String resolved, @Nullable Player viewer, TagResolver resolver) {
        return viewer == null ? mm.deserialize(resolved, resolver) : mm.deserialize(resolved, viewer, resolver);
    }

    /**
     * MiniPlaceholders' resolver for global and audience placeholders, built per render so
     * expansions registered later are included. {@code null} when MiniPlaceholders is absent or
     * throws while building it.
     */
    private @Nullable TagResolver miniPlaceholderResolver() {
        if (miniPlaceholders == null) {
            return null;
        }
        try {
            return miniPlaceholders.get();
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
    }

    private String applyPlaceholders(String input, @Nullable Player viewer) {
        if (viewer == null || placeholderApi == null) {
            return input;
        }
        try {
            // A throwing PlaceholderAPI expansion must not break rendering.
            return placeholderApi.apply(viewer, input);
        } catch (RuntimeException ex) {
            return input;
        }
    }

}
