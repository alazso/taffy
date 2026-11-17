package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CustomItemRefTest {

    @Test
    void splitsProviderFromIdOnFirstColon() {
        CustomItemRef ref = CustomItemRef.parse("itemsadder:ax_wings_pack:phoenix_wings");
        assertThat(ref.providerHint()).isEqualTo("itemsadder");
        assertThat(ref.id()).isEqualTo("ax_wings_pack:phoenix_wings");
        assertThat(ref.provider()).isSameAs(CustomItemPlugin.ITEMSADDER);
        assertThat(ref.hasKnownProvider()).isTrue();
    }

    @Test
    void singleTokenIdHasNoProviderHint() {
        CustomItemRef ref = CustomItemRef.parse("my_item");
        assertThat(ref.providerHint()).isNull();
        assertThat(ref.id()).isEqualTo("my_item");
        assertThat(ref.provider()).isNull();
        assertThat(ref.hasKnownProvider()).isTrue();
    }

    @Test
    void oraxenStyleReference() {
        CustomItemRef ref = CustomItemRef.parse("oraxen:cool_sword");
        assertThat(ref.providerHint()).isEqualTo("oraxen");
        assertThat(ref.id()).isEqualTo("cool_sword");
        assertThat(ref.provider()).isSameAs(CustomItemPlugin.ORAXEN);
    }

    @Test
    void blankProviderHintBecomesNull() {
        CustomItemRef ref = CustomItemRef.parse(":just_id");
        assertThat(ref.providerHint()).isNull();
        assertThat(ref.id()).isEqualTo("just_id");

        CustomItemRef spaced = CustomItemRef.parse("  :just_id");
        assertThat(spaced.providerHint()).isNull();
        assertThat(spaced.id()).isEqualTo("just_id");
    }

    @Test
    void aBlankHintIsRefusedByTheConstructor() {
        for (String hint : new String[] {"", " ", "	"}) {
            assertThatThrownBy(() -> new CustomItemRef(hint, "ruby"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid provider hint '" + hint + "': expected null or a hint that is not blank");
        }
    }

    @Test
    void aMissingOrNamedHintIsAccepted() {
        assertThat(new CustomItemRef(null, "ruby").providerHint()).isNull();
        CustomItemRef named = new CustomItemRef("nexo", "ruby");
        assertThat(named.providerHint()).isEqualTo("nexo");
        assertThat(named.id()).isEqualTo("ruby");
    }

    @Test
    void blankIdRejected() {
        assertThatThrownBy(() -> CustomItemRef.parse("oraxen:"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid custom item id '': expected an id that is not blank");
        assertThatThrownBy(() -> CustomItemRef.parse("nexo:   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomItemRef.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomItemRef("nexo", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aliasesNameTheirProviders() {
        CustomItemRef head = CustomItemRef.parse("hdb:7129");
        assertThat(head.providerHint()).isEqualTo("hdb");
        assertThat(head.id()).isEqualTo("7129");
        assertThat(head.provider()).isSameAs(CustomItemPlugin.HEADDATABASE);
        assertThat(head.hasKnownProvider()).isTrue();

        CustomItemRef item = CustomItemRef.parse("ia:foo");
        assertThat(item.provider()).isSameAs(CustomItemPlugin.ITEMSADDER);
        assertThat(item.hasKnownProvider()).isTrue();
    }

    @Test
    void theHintKeepsItsCaseAndStillFindsItsProvider() {
        CustomItemRef ref = CustomItemRef.parse("Nexo:ruby");
        assertThat(ref.providerHint()).isEqualTo("Nexo");
        assertThat(ref.provider()).isSameAs(CustomItemPlugin.NEXO);
    }

    @Test
    void anUnknownHintIsKeptButNamesNoProvider() {
        CustomItemRef ref = CustomItemRef.parse("other:SWORD:katana");
        assertThat(ref.providerHint()).isEqualTo("other");
        assertThat(ref.id()).isEqualTo("SWORD:katana");
        assertThat(ref.provider()).isNull();
        assertThat(ref.hasKnownProvider()).isFalse();
    }

    @Test
    void aNullReferenceIsRejected() {
        assertThatThrownBy(() -> CustomItemRef.parse(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid custom item reference 'null': expected an id, optionally after a plugin key and a"
                + " colon");
    }
}
