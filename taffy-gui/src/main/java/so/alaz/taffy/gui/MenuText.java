package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.LegacyText;
import so.alaz.taffy.platform.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text of a menu: its title and each item's name and lore. A title, name or lore written in the layout wins in
 * every language; otherwise the text comes from the first lang key base that holds the key, as {@link Messages}
 * resolves it for the viewer's locale. Names are not italic; lore lines are not italic and are gray unless the text
 * sets a colour.
 *
 * <p>Text written in the layout or the lang files is trusted MiniMessage from the owner. Values go in through the
 * placeholders after the template is parsed, so a player-typed value passed as {@code Placeholder.unparsed} or
 * {@code Placeholder.component} is shown as it is. A lang template may write a value as {@code {name}} as well as
 * {@code <name>}; text written in the layout reads only {@code <name>}, and a {@code {name}} there is shown as
 * written. Lang text is rendered as {@link Messages} renders it, so it may write {@code <prefix>}; text written in the
 * layout does not read {@code <prefix>}. A section-sign code in either is converted as
 * {@link LegacyText#sectionToMiniMessage(String)} converts it.
 *
 * <p>A lang key that no file holds renders as the marker {@code <red>missing:key}, except where a method says
 * otherwise.
 */
public final class MenuText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Messages messages;

    MenuText(Messages messages) {
        this.messages = messages;
    }

    /**
     * Gives the messages this text reads, for a menu that renders a lang key as a message of its own.
     *
     * @return the messages
     */
    public Messages messages() {
        return messages;
    }

    /**
     * Gives a menu's title: the layout's own title when it has one, otherwise the lang key.
     *
     * @param locale the viewer's locale
     * @param layout the menu's layout
     * @param key the lang key of the title
     * @param placeholders the values the title reads
     * @return the title, or the missing-key marker when the layout has no title and no lang file holds the key
     */
    public Component title(Locale locale, MenuLayout layout, String key, TagResolver... placeholders) {
        String override = layout.title();
        if (override != null) {
            return layoutText(override, TagResolver.resolver(placeholders));
        }
        return messages.render(locale, key, placeholders);
    }

    /**
     * Gives an item's name: the layout's own name when the item has one, otherwise {@code <base>.name} of the first
     * base that holds it, not italic. When bases are given and none holds a name, the name is the missing-key marker
     * of the last base's name key.
     *
     * @param locale the viewer's locale
     * @param item the item
     * @param bases the lang key bases tried in order, such as {@link MenuKind#textBases(String)} gives
     * @param placeholders the values the name reads
     * @return the name, or {@code null} when the item has no name of its own and {@code bases} is empty, which hides
     *     the tooltip
     */
    public @Nullable Component name(Locale locale, ItemLayout item, List<String> bases, TagResolver... placeholders) {
        String override = item.name();
        if (override != null) {
            return name(layoutText(override, TagResolver.resolver(placeholders)));
        }
        if (bases.isEmpty()) {
            return null;
        }
        for (String base : bases) {
            if (messages.has(locale, base + ".name")) {
                return name(messages.render(locale, base + ".name", placeholders));
            }
        }
        return name(messages.render(locale, bases.getLast() + ".name", placeholders));
    }

    /**
     * Gives an item's lore: the layout's own lore when the item has one, otherwise {@code <base>.lore} of the first
     * base that holds it, one component per line, gray unless coloured and not italic.
     *
     * @param locale the viewer's locale
     * @param item the item
     * @param bases the lang key bases tried in order, such as {@link MenuKind#textBases(String)} gives
     * @param placeholders the values the lore reads
     * @return the lines, empty when neither the item nor any base has lore
     */
    public List<Component> lore(Locale locale, ItemLayout item, List<String> bases, TagResolver... placeholders) {
        List<String> override = item.lore();
        if (override != null) {
            TagResolver resolver = TagResolver.resolver(placeholders);
            List<Component> lines = new ArrayList<>();
            for (String line : override) {
                lines.add(lore(layoutText(line, resolver)));
            }
            return List.copyOf(lines);
        }
        for (String base : bases) {
            if (messages.has(locale, base + ".lore")) {
                return lines(locale, base + ".lore", placeholders);
            }
        }
        return List.of();
    }

    /**
     * Gives a lang key as a name, not italic.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @param placeholders the values the text reads
     * @return the name, or the missing-key marker when no lang file holds the key
     */
    public Component label(Locale locale, String key, TagResolver... placeholders) {
        return name(messages.render(locale, key, placeholders));
    }

    /**
     * Gives a lang key as one lore line, gray unless coloured and not italic.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @param placeholders the values the text reads
     * @return the line, or the missing-key marker when no lang file holds the key
     */
    public Component line(Locale locale, String key, TagResolver... placeholders) {
        return lore(messages.render(locale, key, placeholders));
    }

    /**
     * Gives a lang key as lore, one component per line, each gray unless coloured and not italic.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @param placeholders the values the text reads
     * @return the lines in order, or the single missing-key marker line when no lang file holds the key
     */
    public List<Component> lines(Locale locale, String key, TagResolver... placeholders) {
        return messages.lines(locale, key, placeholders).stream().map(MenuText::lore).toList();
    }

    /**
     * Tells whether a lang key resolves for a locale.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @return whether a lang file the locale reads, the bundled English text included, holds the key
     */
    public boolean has(Locale locale, String key) {
        return messages.has(locale, key);
    }

    private static Component layoutText(String text, TagResolver resolver) {
        return MINI.deserialize(LegacyText.sectionToMiniMessage(text), resolver);
    }

    private static Component name(Component text) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Component lore(Component text) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            .colorIfAbsent(NamedTextColor.GRAY);
    }
}
