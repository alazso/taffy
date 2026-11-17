package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

/**
 * A parsed reference to a third-party custom item, such as
 * {@code itemsadder:ax_wings_pack:phoenix_wings} or {@code oraxen:my_item}.
 *
 * <p>The text before the first colon is treated as a provider hint (matched through
 * {@link CustomItemPlugin#byKey}); the remainder is the provider's own item id, which may itself contain colons.
 *
 * @param providerHint the provider name hint, not blank, or {@code null} if none was given
 * @param id           the provider's item id
 */
public record CustomItemRef(@Nullable String providerHint, String id) {

    /**
     * Checks the hint and the id.
     *
     * @throws IllegalArgumentException if {@code providerHint} is blank, or {@code id} is {@code null} or blank
     */
    public CustomItemRef {
        if (providerHint != null && providerHint.isBlank()) {
            throw new IllegalArgumentException("invalid provider hint '" + providerHint + "': expected null or a hint"
                + " that is not blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("invalid custom item id '" + id + "': expected an id that is not"
                + " blank");
        }
    }

    /**
     * Whether the reference names a recognized provider (or none, for an unqualified id).
     *
     * @return {@code true} if the hint is {@code null} or names a {@link CustomItemPlugin}
     */
    public boolean hasKnownProvider() {
        return providerHint == null || CustomItemPlugin.byKey(providerHint) != null;
    }

    /**
     * The plugin the hint names.
     *
     * @return the plugin, or {@code null} if there is no hint or it names no plugin
     */
    public @Nullable CustomItemPlugin provider() {
        return CustomItemPlugin.byKey(providerHint);
    }

    /**
     * Splits {@code reference} on its first colon into a provider hint and an id. A blank hint becomes
     * {@code null}.
     *
     * @param reference the reference, such as {@code ia:ns:item} or {@code my_item}
     * @return the parsed reference
     * @throws IllegalArgumentException if {@code reference} is {@code null} or its id is blank
     */
    public static CustomItemRef parse(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException("invalid custom item reference 'null': expected an id, optionally"
                + " after a plugin key and a colon");
        }
        int colon = reference.indexOf(':');
        if (colon < 0) {
            return new CustomItemRef(null, reference);
        }
        String hint = reference.substring(0, colon);
        String id = reference.substring(colon + 1);
        return new CustomItemRef(hint.isBlank() ? null : hint, id);
    }
}
