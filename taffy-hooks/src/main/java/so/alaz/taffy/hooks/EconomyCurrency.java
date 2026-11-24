package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One currency of an economy that has several, as {@link EconomyProvider#vaultUnlockedCurrencies} and
 * {@link EconomyProvider#coinsEngineCurrencies} list them. The id is what {@link EconomyProvider#vaultUnlocked} and
 * {@link EconomyProvider#coinsEngine} take as their currency. The name and the symbol are the text the economy gave,
 * unchanged: they can hold legacy colour codes or the tags of the economy plugin's own format, and are shown as
 * literal text, as {@link EconomyProvider#format} describes.
 *
 * @param id the id the economy knows the currency by
 * @param name the name the economy shows for the currency, or {@code null} when it gives none
 * @param symbol the symbol of the currency, such as {@code $}, or {@code null} when the economy gives none
 * @since 0.4.13
 */
public record EconomyCurrency(String id, @Nullable String name, @Nullable String symbol) {

    /**
     * Creates a currency. A blank name or symbol is kept as {@code null}.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public EconomyCurrency {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a currency id is not blank");
        }
        name = name == null || name.isBlank() ? null : name;
        symbol = symbol == null || symbol.isBlank() ? null : symbol;
    }
}
