package so.alaz.taffy.gui;

import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

final class MenuPromptsTest {

    @Test
    void noPromptsEndForgetAndShutDownNothingAndOwnNoView() {
        UUID player = UUID.randomUUID();
        InventoryView view = mock(InventoryView.class);

        assertThatCode(() -> {
            MenuPrompts.NONE.end(player);
            MenuPrompts.NONE.quit(player);
            MenuPrompts.NONE.shutdown();
        }).doesNotThrowAnyException();
        assertThat(MenuPrompts.NONE.owns(view)).isFalse();
        verifyNoInteractions(view);
    }
}
