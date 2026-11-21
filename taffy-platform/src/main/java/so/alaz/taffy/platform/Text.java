package so.alaz.taffy.platform;

import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.PreProcess;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders MiniMessage strings (RGB, gradients, PlaceholderAPI, MiniPlaceholders) into
 * Adventure {@link Component}s.
 *
 * <p>The input is parsed as MiniMessage, with MiniPlaceholders' component-safe tags added as resolvers. Both
 * integrations are optional: when a plugin is absent, its pass is skipped.
 *
 * <p>A render with a viewer resolves each PlaceholderAPI placeholder of the input on its own: every run of text
 * between two {@code %} signs is given to PlaceholderAPI, and a run PlaceholderAPI returns unchanged stays as
 * written. What a placeholder returns is inserted as text and is never parsed as MiniMessage, so a tag in it, such
 * as {@code <click:run_command:'/pay Ann 100'>} or the {@code <name>} of a supplied value, is shown as written.
 * Section-sign colour and format codes in it still apply, to that output only: they do not colour the template
 * after the placeholder. A placeholder inside a tag argument that MiniMessage does not parse, such as a click
 * value or an insertion, stays as written, {@code %} signs included, and so does one after an escaping backslash.
 * A placeholder written as an unquoted tag argument, as in {@code <color:%team_color%>} or
 * {@code <gradient:%a%:%b%>}, or inside the URL of {@code <click:open_url:...>}, leaves a tag MiniMessage cannot
 * read, which is then shown as text.
 * {@link #withPlaceholderFormatting()} gives a {@code Text} that also reads formatting tags in what a placeholder
 * returns. Tag names that start with {@code taffy_papi_} are reserved for the render: a supplied value of such a
 * name may be shadowed, and such a tag written in the input may be replaced.
 *
 * <p>MiniMessage refuses a section sign followed by a legacy code character. Every render therefore converts such
 * a code to its tag, as {@link LegacyText#sectionToMiniMessage} does: in the input and in each value supplied as
 * {@code Placeholder.parsed}. A converted code such as {@code <red>} in a parsed value is an open tag, so it
 * colours the rest of the template after the value, as the legacy code would. A value supplied as
 * {@code Placeholder.unparsed} or {@code Placeholder.component} is inserted as it is, section sign included.
 *
 * <p>An instance is thread-safe and may be shared. A PlaceholderAPI pass that throws, and a
 * MiniPlaceholders resolver that cannot be built or that throws while the text is parsed, is skipped and
 * logged as {@link ThrowLog} does, under the sources {@code placeholderapi} and {@code miniplaceholders};
 * nothing is reported to {@link ErrorReports}. A single MiniPlaceholders tag that throws an exception while
 * it resolves is left as written by MiniMessage and is not logged. A tag that throws a {@link LinkageError}
 * such as {@code NoSuchMethodError}, as an expansion built against another API does, skips the whole
 * MiniPlaceholders pass and is logged. PlaceholderAPI expansions and MiniPlaceholders audience tags run on
 * the calling thread and read the viewer, so a call that passes a viewer belongs on the thread that owns
 * that player.
 */
public final class Text {

    private static final Pattern BRACED_NAME = Pattern.compile("\\{([a-z0-9_-]+)}");
    private static final String PLACEHOLDER_API = "placeholderapi";
    private static final String MINI_PLACEHOLDERS = "miniplaceholders";
    private static final String MINI_PLACEHOLDERS_FAILED =
        "MiniPlaceholders failed, so the text is shown without its placeholders.";

    private static final String OUTPUT_TAG = "taffy_papi_";
    private static final Pattern OUTPUT_NAME = Pattern.compile("<" + OUTPUT_TAG + "(\\d+)>");
    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.builder().character(LegacyComponentSerializer.SECTION_CHAR).hexColors().build();
    private static final MiniMessage FORMATTING = MiniMessage.builder()
        .tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(),
            StandardTags.reset()))
        .build();

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final @Nullable BiFunction<Player, String, String> placeholderApi;
    private final @Nullable Supplier<@Nullable TagResolver> miniPlaceholders;
    private final ThrowLog failures;
    private final boolean placeholderFormatting;

    /**
     * Detects PlaceholderAPI and MiniPlaceholders once, by class presence. A lookup that fails is
     * reported to {@code logger} and leaves that integration off.
     *
     * @throws IllegalStateException when {@link Relocation#check()} fails, because the plugin's jar holds Taffy
     *     in its original package
     */
    public Text(Logger logger) {
        Relocation.check();
        this.failures = new ThrowLog(logger);
        this.placeholderApi = Classes.present("me.clip.placeholderapi.PlaceholderAPI", getClass(), logger)
            ? (viewer, input) -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, input)
            : null;
        this.miniPlaceholders = Classes.present("io.github.miniplaceholders.api.MiniPlaceholders", getClass(), logger)
            ? () -> MiniPlaceholders.audienceGlobalPlaceholders()
            : null;
        this.placeholderFormatting = false;
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
        this.placeholderFormatting = false;
    }

    private Text(Text source, boolean placeholderFormatting) {
        this.failures = source.failures;
        this.placeholderApi = source.placeholderApi;
        this.miniPlaceholders = source.miniPlaceholders;
        this.placeholderFormatting = placeholderFormatting;
    }

    /**
     * Returns a {@code Text} that renders as this one does and also reads formatting tags in what a PlaceholderAPI
     * placeholder returns: the colour tags, the decoration tags, {@code <gradient>}, {@code <rainbow>} and
     * {@code <reset>}. Every other tag in it, such as {@code <click>}, {@code <hover>} or the {@code <name>} of a
     * supplied value, is shown as written. The formatting applies to that output only, as its section-sign codes
     * do. This instance is left as it is, and the returned one shares its integrations and its log, so one plugin
     * can render chat through this instance and owner-written text through the returned one.
     */
    public Text withPlaceholderFormatting() {
        return placeholderFormatting ? this : new Text(this, true);
    }

    /** Parses the input as MiniMessage, without PlaceholderAPI resolution. */
    public Component render(String input) {
        return deserialize(input, null, TagResolver.empty());
    }

    /**
     * Parses the input as MiniMessage and fills in its PlaceholderAPI placeholders against the viewer (when
     * non-null and PlaceholderAPI is present). What a placeholder returns is inserted as text, as the class
     * description states.
     */
    public Component render(String input, @Nullable Player viewer) {
        return renderFilled(input, null, viewer, TagResolver.empty());
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
     * through {@link #resolve(String, Player)} by the caller before it is supplied, as
     * {@code Placeholder.unparsed} unless every tag in what those placeholders return is meant to be parsed.
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
        return renderFilled(template, null, viewer, TagResolver.resolver(values));
    }

    /**
     * Renders {@code template} with {@code values} and with {@code prefix}, a MiniMessage template of its own,
     * supplied under the name {@code prefix}. The PlaceholderAPI placeholders of the prefix are filled in before
     * those of the template, and both as the class description states. A value named {@code prefix} in
     * {@code values} takes the place of {@code prefix}.
     */
    Component renderWithPrefix(String template, String prefix, @Nullable Player viewer, TagResolver values) {
        return renderFilled(template, prefix, viewer, values);
    }

    private Component renderFilled(
        String template,
        @Nullable String prefix,
        @Nullable Player viewer,
        TagResolver values
    ) {
        List<Component> outputs = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        TagResolver supplied = values;
        if (prefix != null) {
            String filled = LegacyText.sectionToMiniMessage(
                insertPlaceholders(aliasBraces(prefix, values), viewer, outputs, tokens));
            // The later resolver answers first, so a supplied value named prefix wins.
            supplied = TagResolver.resolver(Placeholder.parsed("prefix", filled), values);
        }
        String filled = insertPlaceholders(aliasBraces(template, supplied), viewer, outputs, tokens);
        if (outputs.isEmpty()) {
            return deserialize(filled, viewer, supplied);
        }
        TagResolver.Builder tags = TagResolver.builder().resolver(supplied);
        for (int i = 0; i < outputs.size(); i++) {
            tags.resolver(Placeholder.component(OUTPUT_TAG + i, outputs.get(i)));
        }
        return restoreTokens(deserialize(filled, viewer, tags.build()), tokens);
    }

    /**
     * Returns {@code component} with each generated tag that is still text in it replaced by the placeholder
     * the tag stood for, taken from {@code tokens} at the index the tag names. Text content, insertions, the
     * string value of a click event and the component of a show-text hover event are looked at, at every depth.
     * The same instance is returned when it holds no generated tag.
     */
    private static Component restoreTokens(Component component, List<String> tokens) {
        Component out = component;
        if (out instanceof TextComponent text) {
            String content = restore(text.content(), tokens);
            if (!content.equals(text.content())) {
                out = text.content(content);
            }
        }
        Style style = out.style();
        String insertion = style.insertion();
        if (insertion != null && !restore(insertion, tokens).equals(insertion)) {
            out = out.insertion(restore(insertion, tokens));
        }
        ClickEvent<?> click = style.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text payload
            && !restore(payload.value(), tokens).equals(payload.value())) {
            out = out.clickEvent(withValue(click, restore(payload.value(), tokens)));
        }
        HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.value() instanceof Component shown && restoreTokens(shown, tokens) != shown) {
            out = out.hoverEvent(HoverEvent.showText(restoreTokens(shown, tokens)));
        }
        List<Component> children = out.children();
        List<Component> restored = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = restoreTokens(children.get(i), tokens);
            if (child != children.get(i)) {
                if (restored == null) {
                    restored = new ArrayList<>(children);
                }
                restored.set(i, child);
            }
        }
        return restored == null ? out : out.children(restored);
    }

    private static String restore(String value, List<String> tokens) {
        if (!value.contains(OUTPUT_TAG)) {
            return value;
        }
        return OUTPUT_NAME.matcher(value).replaceAll(match -> {
            String index = match.group(1);
            boolean known = index.length() < 10 && Integer.parseInt(index) < tokens.size();
            return Matcher.quoteReplacement(known ? tokens.get(Integer.parseInt(index)) : match.group());
        });
    }

    /** A click event of the action of {@code click} whose string value is {@code value}. */
    @SuppressWarnings("unchecked")
    private static <T extends ClickEvent.Payload> ClickEvent<T> withValue(ClickEvent<T> click, String value) {
        return ClickEvent.clickEvent(click.action(), (T) ClickEvent.Payload.string(value));
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
     * section-sign code and any MiniMessage tag still in it, so a render that parses it reads the tags a
     * placeholder returned.
     */
    public String resolve(String input, @Nullable Player viewer) {
        return applyPlaceholders(input, viewer);
    }

    /** Renders the input as {@link #render(String, Player)} does and flattens it to plain text. */
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
     * Parses {@code input} as MiniMessage with MiniPlaceholders' resolvers when installed,
     * falling back to parsing without them if an expansion throws. The failure is logged only when
     * the fallback parse succeeds; when it throws as well, its exception propagates.
     */
    private Component deserialize(String input, @Nullable Player viewer, TagResolver values) {
        String resolved = LegacyText.sectionToMiniMessage(input);
        TagResolver expansions = miniPlaceholderResolver();
        if (expansions == null) {
            return parse(resolved, viewer, values);
        }
        try {
            // The later resolver answers first, so a supplied value wins a name both have.
            return parse(resolved, viewer, TagResolver.resolver(expansions, values));
        } catch (LinkageError | RuntimeException ex) {
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

    /**
     * Replaces each run between two {@code %} signs that PlaceholderAPI resolves for {@code viewer} with a
     * generated tag, and adds what the placeholder returned to {@code outputs}, and the run as written to
     * {@code tokens}, at the index the tag names. A run PlaceholderAPI returns unchanged stays as written, and its
     * closing {@code %} may open the next run. When PlaceholderAPI is absent or throws, which is logged, or the
     * viewer is null, the input is returned unchanged and both lists are left as they were.
     */
    private String insertPlaceholders(
        String input,
        @Nullable Player viewer,
        List<Component> outputs,
        List<String> tokens
    ) {
        int open = input.indexOf('%');
        if (viewer == null || placeholderApi == null || open < 0) {
            return input;
        }
        int before = outputs.size();
        StringBuilder out = new StringBuilder(input.length());
        int copied = 0;
        try {
            for (int close = input.indexOf('%', open + 1); close >= 0; close = input.indexOf('%', open + 1)) {
                String token = input.substring(open, close + 1);
                String result = close == open + 1 ? token : placeholderApi.apply(viewer, token);
                if (token.equals(result)) {
                    open = close;
                    continue;
                }
                out.append(input, copied, open).append('<').append(OUTPUT_TAG).append(outputs.size()).append('>');
                outputs.add(output(result));
                tokens.add(token);
                copied = close + 1;
                open = input.indexOf('%', copied);
                if (open < 0) {
                    break;
                }
            }
        } catch (LinkageError | RuntimeException ex) {
            outputs.subList(before, outputs.size()).clear();
            tokens.subList(before, tokens.size()).clear();
            failures.warn(PLACEHOLDER_API,
                "PlaceholderAPI failed while resolving placeholders, so the text is shown without them.", ex);
            return input;
        }
        return out.append(input, copied, input.length()).toString();
    }

    private Component output(String result) {
        return placeholderFormatting
            ? FORMATTING.deserialize(LegacyText.sectionToMiniMessage(result))
            : LEGACY.deserialize(result);
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
