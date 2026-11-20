package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import so.alaz.taffy.platform.Messages;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Where the menus and prompts get the text of a lang key: a function from a locale, a key and the values the text
 * reads to a component. {@link #of(Messages)} gives the source that reads Taffy's {@link Messages}; a plugin that
 * keeps its text elsewhere passes its own function, such as
 * {@code (locale, key, placeholders) -> lang.component(locale, key, placeholders)}.
 *
 * <p>The menus and prompts call a source on the viewer's entity thread.
 */
@FunctionalInterface
public interface TextSource {

    /**
     * Gives the text of a key.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @param placeholders the values the text reads
     * @return the text; for a key the source does not hold, whatever the source shows in its place
     */
    Component render(Locale locale, String key, TagResolver... placeholders);

    /**
     * Tells whether the source holds a key. The menus ask before they read text that is optional: an item's lore,
     * each lang key base but the last of an item's name, a list's empty state and the word that cancels a prompt.
     * A source that answers {@code true} for a key it does not hold has that text rendered all the same.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @return whether {@link #render(Locale, String, TagResolver...)} gives text of the key; {@code true} unless the
     *     source overrides this method
     */
    default boolean has(Locale locale, String key) {
        return true;
    }

    /**
     * Gives the text of a key one component per line, as an item's lore shows it.
     *
     * @param locale the viewer's locale
     * @param key the lang key
     * @param placeholders the values the text reads
     * @return the lines in order; the one component {@link #render(Locale, String, TagResolver...)} gives unless the
     *     source overrides this method
     */
    default List<Component> lines(Locale locale, String key, TagResolver... placeholders) {
        return List.of(render(locale, key, placeholders));
    }

    /**
     * Gives the source that reads Taffy's lang files: {@link Messages#render(Locale, String, TagResolver...)},
     * {@link Messages#has(Locale, String)} and {@link Messages#lines(Locale, String, TagResolver...)}. A key no file
     * holds renders as the marker {@code <red>missing:key}.
     *
     * @param messages the plugin's lang files
     * @return the source
     * @throws NullPointerException if {@code messages} is {@code null}
     */
    static TextSource of(Messages messages) {
        Objects.requireNonNull(messages, "messages");
        return new TextSource() {
            @Override
            public Component render(Locale locale, String key, TagResolver... placeholders) {
                return messages.render(locale, key, placeholders);
            }

            @Override
            public boolean has(Locale locale, String key) {
                return messages.has(locale, key);
            }

            @Override
            public List<Component> lines(Locale locale, String key, TagResolver... placeholders) {
                return messages.lines(locale, key, placeholders);
            }
        };
    }
}
