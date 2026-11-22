package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class DialogPromptTest {

    private static final Component TITLE = Component.text("Price?");

    private final CapturedLog log = new CapturedLog();
    private final PromptFakes.FakeDialogs dialogs = new PromptFakes.FakeDialogs();
    private final DialogPrompt prompt = new DialogPrompt(dialogs, log.logger);
    private final List<String> submitted = new ArrayList<>();
    private final AtomicInteger cancels = new AtomicInteger();
    private final UUID id = UUID.randomUUID();
    private final Player player = player(id, true);

    private static Player player(UUID id, boolean online) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(online);
        return player;
    }

    private boolean open(Player asked, long token, String initial) {
        return prompt.open(asked, token, TITLE, null, initial, submitted::add, cancels::incrementAndGet);
    }

    @Test
    void anOpenDialogShowsUntilAButtonOfItIsPressedAndEachButtonActsOnce() {
        assertThat(open(player, 1, "10")).isTrue();

        assertThat(prompt.showing(id, 1)).isTrue();
        assertThat(prompt.showing(id, 2)).isFalse();
        assertThat(dialogs.last().title()).isSameAs(TITLE);
        assertThat(dialogs.last().initial()).isEqualTo("10");
        dialogs.submit(0, "7");
        dialogs.submit(0, "8");
        dialogs.cancel(0);

        assertThat(submitted).containsExactly("7");
        assertThat(cancels).hasValue(0);
        assertThat(prompt.showing(id, 1)).isFalse();
    }

    @Test
    void aButtonOfADialogThatIsNoLongerTheOneShownLastDoesNothing() {
        open(player, 1, "10");
        open(player, 1, "nope");

        dialogs.submit(0, "7");
        dialogs.cancel(0);

        assertThat(submitted).isEmpty();
        assertThat(cancels).hasValue(0);
        assertThat(prompt.showing(id, 1)).isTrue();
        dialogs.cancel(1);
        assertThat(cancels).hasValue(1);
    }

    @Test
    void theStartingTextIsCutToTheLongestAnswerWithoutSplittingASurrogatePair() {
        open(player, 1, "a".repeat(Prompts.MAX_LENGTH - 1) + "😀");

        assertThat(dialogs.last().initial()).isEqualTo("a".repeat(Prompts.MAX_LENGTH - 1));
    }

    @Test
    void closeTakesAwayTheDialogOfItsTokenOnlyAndSendsNothingToAPlayerWhoIsOffline() {
        open(player, 1, "10");

        prompt.close(player, 2);
        assertThat(dialogs.closed).isEmpty();
        assertThat(prompt.showing(id, 1)).isTrue();
        prompt.close(player, 1);
        prompt.close(player, 1);

        assertThat(dialogs.closed).containsExactly(player);
        Player gone = player(UUID.randomUUID(), false);
        open(gone, 3, "");
        prompt.close(gone, 3);
        assertThat(dialogs.closed).containsExactly(player);
        assertThat(prompt.showing(gone.getUniqueId(), 3)).isFalse();
    }

    @Test
    void forgetDropsTheDialogWithoutClosingIt() {
        open(player, 1, "10");

        prompt.forget(id);

        assertThat(prompt.showing(id, 1)).isFalse();
        assertThat(dialogs.closed).isEmpty();
        dialogs.submit(0, "7");
        assertThat(submitted).isEmpty();
    }

    @Test
    void shutdownClosesTheDialogOfEachPlayerTheThreadOwnsAndForgetsTheOthers() {
        Player other = player(UUID.randomUUID(), true);
        open(player, 1, "10");
        open(other, 2, "10");

        prompt.shutdown(each -> each == player);

        assertThat(dialogs.closed).containsExactly(player);
        assertThat(prompt.showing(id, 1)).isFalse();
        assertThat(prompt.showing(other.getUniqueId(), 2)).isFalse();
    }

    @Test
    void aDialogApiThatCannotBeLinkedIsLoggedOnceAndMakesThePromptUnusable() {
        NoSuchMethodError changed = new NoSuchMethodError("DialogBase.builder");
        dialogs.failure = changed;

        assertThat(prompt.usable()).isTrue();
        assertThat(open(player, 1, "10")).isFalse();
        assertThat(open(player, 2, "10")).isFalse();

        assertThat(prompt.usable()).isFalse();
        assertThat(prompt.showing(id, 1)).isFalse();
        assertThat(log.lines()).filteredOn(line -> line.contains("could not be linked")).hasSize(1);
        assertThat(log.records.getFirst().getThrown()).isSameAs(changed);
    }

    @Test
    void aShowThatThrowsLeavesNoDialogBehindAndThePromptUsable() {
        dialogs.failure = new IllegalStateException("refused");

        assertThatIllegalStateException().isThrownBy(() -> open(player, 1, "10")).withMessage("refused");

        assertThat(prompt.showing(id, 1)).isFalse();
        assertThat(prompt.usable()).isTrue();
    }

    @Test
    void thePaperDialogPromptExistsOnlyWhereTheServerHasTheApiClass() {
        assertThat(DialogPrompt.paper(PaperDialogs.API, log.logger)).isNotNull();
        assertThat(log.lines()).isEmpty();

        assertThat(DialogPrompt.paper("io.papermc.paper.dialog.Missing", log.logger)).isNull();

        assertThat(log.lines()).singleElement().asString()
            .contains("gui: this server has no Paper Dialog API, so prompts are asked in chat");
    }

    @Test
    void theConstructorRefusesNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new DialogPrompt(null, log.logger)).withMessage("dialogs");
        assertThatNullPointerException().isThrownBy(() -> new DialogPrompt(dialogs, null)).withMessage("logger");
    }
}
