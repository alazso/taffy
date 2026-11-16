package so.alaz.taffy.gui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MenuKindTest {

    private static final MenuKind HOME = MenuKind.of("home", false, "browse", "sort", MenuKind.CLOSE);

    private static final MenuKind OPTIONS = MenuKind.of("options", false, "locale", MenuKind.BACK, MenuKind.CLOSE);

    private static final MenuKind AMOUNT = MenuKind.of("amount", false, "item", "minus-1", "plus-1", "plus-8",
            MenuKind.BACK)
        .text("item", null)
        .text("plus-1", "gui.amount.plus")
        .text("plus-8", "gui.amount.plus");

    private static final MenuKind ENTRIES = MenuKind.of("entries", true, "claim-all", MenuKind.BACK,
        MenuKind.PREVIOUS_PAGE, MenuKind.NEXT_PAGE, MenuKind.REFRESH, MenuKind.CLOSE);

    @Test
    void eachActionReadsItsMenusKeysAndAFooterActionFallsBackToTheFooterKeys() {
        assertThat(HOME.textBases("browse")).containsExactly("gui.home.browse");
        assertThat(OPTIONS.textBases("back")).containsExactly("gui.options.back", "gui.footer.back");
        assertThat(AMOUNT.textBases("plus-8")).containsExactly("gui.amount.plus");
        assertThat(AMOUNT.textBases("minus-1")).containsExactly("gui.amount.minus-1");
        assertThat(AMOUNT.textBases("item")).isEmpty();
        assertThat(AMOUNT.textBases("nothing")).isEmpty();
        assertThat(ENTRIES.textBases("previous-page")).containsExactly("gui.entries.previous-page",
            "gui.footer.previous-page");
        assertThat(ENTRIES.textBases("next-page")).containsExactly("gui.entries.next-page", "gui.footer.next-page");
        assertThat(ENTRIES.textBases("refresh")).containsExactly("gui.entries.refresh", "gui.footer.refresh");
        assertThat(ENTRIES.textBases("close")).containsExactly("gui.entries.close", "gui.footer.close");
    }

    @Test
    void anActionThatIsNotAFooterActionGetsNoFooterBase() {
        assertThat(HOME.textBases("sort")).containsExactly("gui.home.sort");
        assertThat(ENTRIES.textBases("claim-all")).containsExactly("gui.entries.claim-all");
    }

    @Test
    void actionsKeepTheirOrderAndAKindIsAValue() {
        assertThat(AMOUNT.actions()).containsExactly("item", "minus-1", "plus-1", "plus-8", "back");
        assertThat(ENTRIES.paged()).isTrue();
        assertThat(HOME.paged()).isFalse();
        assertThat(ENTRIES.has("claim-all")).isTrue();
        assertThat(ENTRIES.has("sort")).isFalse();
        assertThat(HOME.text("sort", "gui.home.sort")).isEqualTo(HOME);
        assertThatThrownBy(() -> HOME.texts().put("fly", "gui.fly"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTextBaseCanOnlyBeSetForAnActionTheMenuHas() {
        assertThatThrownBy(() -> HOME.text("fly", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the home menu has no action fly");
    }

    @Test
    void aKindNeedsAnIdAndTexts() {
        assertThatThrownBy(() -> new MenuKind(null, false, Map.of()))
            .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new MenuKind("home", false, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aKindRefusesANullAction() {
        Map<String, String> texts = new HashMap<>();
        texts.put("browse", "gui.home.browse");
        texts.put(null, "gui.home.nothing");

        assertThatThrownBy(() -> new MenuKind("home", false, texts)).isInstanceOf(NullPointerException.class)
            .hasMessage("action");
        assertThatThrownBy(() -> MenuKind.of("home", false, "browse", null))
            .isInstanceOf(NullPointerException.class).hasMessage("action");
    }

    @Test
    void theFooterConstantsNameTheFooterActionsAndTheirKeyPrefix() {
        assertThat(new String[] {MenuKind.BACK, MenuKind.PREVIOUS_PAGE, MenuKind.NEXT_PAGE, MenuKind.REFRESH,
            MenuKind.CLOSE, MenuKind.FOOTER_TEXT}).containsExactly("back", "previous-page", "next-page", "refresh",
            "close", "gui.footer.");
    }
}
