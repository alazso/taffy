package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EconomyCurrencyTest {

    @Test
    void aCurrencyCarriesItsIdItsNameAndItsSymbol() {
        EconomyCurrency gems = new EconomyCurrency("gems", "Gems", "G");

        assertThat(gems.id()).isEqualTo("gems");
        assertThat(gems.name()).isEqualTo("Gems");
        assertThat(gems.symbol()).isEqualTo("G");
        assertThat(gems).isEqualTo(new EconomyCurrency("gems", "Gems", "G"));
    }

    @Test
    void aBlankNameOrSymbolIsKeptAsNull() {
        EconomyCurrency bare = new EconomyCurrency("gems", " ", "");

        assertThat(bare.name()).isNull();
        assertThat(bare.symbol()).isNull();
        assertThat(bare).isEqualTo(new EconomyCurrency("gems", null, null));
    }

    @Test
    void aMissingOrBlankIdIsRefused() {
        assertThatThrownBy(() -> new EconomyCurrency(null, "Gems", "G"))
            .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new EconomyCurrency(" ", "Gems", "G"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a currency id is not blank");
    }
}
