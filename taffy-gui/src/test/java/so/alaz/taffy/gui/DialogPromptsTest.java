package so.alaz.taffy.gui;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

// Prompts in the DIALOG mode over a fake of the dialog seam, for both kinds of question.
final class DialogPromptsTest {

    private static final String HINT = "Type it in chat, or type cancel to stop.";
    private static final String STOPPED = "Stopped. Nothing changed.";
    private static final String TIMED_OUT = "No answer within 30 seconds, so nothing changed.";
    private static final Component NOT_A_NUMBER = Component.text("Not a number.");

    @TempDir
    Path folder;

    private final PromptFakes.ManualTimer timer = new PromptFakes.ManualTimer();
    private final PromptFakes.FakeLink link = new PromptFakes.FakeLink();
    private final PromptFakes.FakeDialogs dialogs = new PromptFakes.FakeDialogs();
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<String> judged = new ArrayList<>();
    private final List<String> accepted = new ArrayList<>();
    private final List<PromptEnd> ends = new ArrayList<>();
    private Sample sample;
    private MenuHarness harness;
    private MenuHarness.Viewer alex;
    private DialogPrompt surface;
    private Prompts prompts;
    private PaperPromptListener chat;

    // Dialog prompts over the menus of the sample, whose menu is open in state 2.
    private void start() {
        sample = new Sample(folder);
        harness = sample.harness;
        alex = sample.viewer;
        surface = new DialogPrompt(dialogs, harness.log.logger);
        prompts = new Prompts(harness.menus, PromptMode.DIALOG, bedrock::contains, timer, null, surface, link,
            PromptFakes::ownScreen, harness.clock);
        chat = prompts.promptListener();
        sample.menu.sessions.getLast().state(2);
    }

    // Dialog prompts over no menus, for a viewer with no menu open.
    private void alone() {
        harness = new MenuHarness(folder);
        alex = harness.viewer("Alex");
        surface = new DialogPrompt(dialogs, harness.log.logger);
        prompts = new Prompts(Menus.Parts.of(harness.plugin, harness.messages, harness.tasks, harness.entity,
            harness.errors), PromptMode.DIALOG, bedrock::contains, timer, null, surface, link,
            PromptFakes::ownScreen, harness.clock, player -> true, listener -> { });
        chat = prompts.promptListener();
    }

    // Accepts a number as the next state of the sample menu and refuses anything else.
    private Answer judge(String text) {
        judged.add(text);
        try {
            return new Answer.Accept(new Route(Sample.KIND.id(), Integer.parseInt(text), null));
        } catch (NumberFormatException refused) {
            return new Answer.Retry(NOT_A_NUMBER);
        }
    }

    private void ask() {
        ask(this::judge, "10");
    }

    private void ask(Function<String, Answer> judge, String initial) {
        MenuSession<?> session = alex.session();
        Route origin = session == null ? new Route(Sample.KIND.id(), 2, null) : session.route();
        prompts.ask(alex.player, new PromptRequest(Component.text("Price?"), initial, origin, judge, ends::add));
    }

    private TextRequest text() {
        return new TextRequest(Component.text("Name?"), "crate", answer -> {
            judged.add(answer);
            return answer.length() < 3 ? NOT_A_NUMBER : null;
        }, accepted::add, ends::add);
    }

    private AsyncChatEvent type(String text) {
        Component message = Component.text(text);
        AsyncChatEvent event = new AsyncChatEvent(true, alex.player, new HashSet<>(), ChatRenderer.defaultRenderer(),
            message, message, mock(SignedMessage.class));
        chat.onChat(event);
        return event;
    }

    private @Nullable Object state() {
        MenuSession<?> session = alex.session();
        return session == null ? null : session.state();
    }

    @Test
    void aDialogAskHandsTheMenuOffAndShowsTheQuestionOverTheStartingTextWithNoChatLine() {
        start();

        ask();

        assertThat(alex.view).isNull();
        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(alex.said()).isEmpty();
        assertThat(link.actions).isEmpty();
        assertThat(dialogs.shown).hasSize(1);
        assertThat(dialogs.shown.getFirst().player()).isSameAs(alex.player);
        assertThat(MenuHarness.plain(dialogs.last().title())).isEqualTo("Price?");
        assertThat(dialogs.last().note()).isNull();
        assertThat(dialogs.last().initial()).isEqualTo("10");
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT);
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.DIALOG);
    }

    @Test
    void theSubmittedTextIsJudgedAndTheAcceptedRouteOpens() {
        start();
        ask();

        dialogs.submit(0, "  7 ");

        assertThat(judged).containsExactly("7");
        assertThat(state()).isEqualTo(7);
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(dialogs.closed).isEmpty();
        assertThat(ends).isEmpty();
    }

    @Test
    void aRefusedAnswerShowsTheDialogAgainOnTheNextTickWithTheReasonAndTheRefusedText() {
        start();
        ask();

        dialogs.submit(0, "nope");

        assertThat(alex.said()).isEmpty();
        assertThat(dialogs.shown).hasSize(1);
        assertThat(prompts.pending(alex.id)).isNotNull();
        harness.drain();
        assertThat(dialogs.shown).hasSize(2);
        assertThat(MenuHarness.plain(dialogs.last().note())).isEqualTo("Not a number.\nYou typed: nope");
        assertThat(dialogs.last().initial()).isEqualTo("nope");
        assertThat(MenuHarness.plain(dialogs.last().title())).isEqualTo("Price?");
        dialogs.submit(0, "4");
        assertThat(judged).containsExactly("nope");
        dialogs.submit(1, "5");
        assertThat(state()).isEqualTo(5);
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void theCancelButtonCancelsTheAskAndOpensTheOrigin() {
        start();
        ask();

        dialogs.cancel(0);

        assertThat(alex.said()).containsExactly(STOPPED);
        assertThat(state()).isEqualTo(2);
        assertThat(dialogs.closed).isEmpty();
        assertThat(ends).isEmpty();
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void theCancelWordTypedIntoTheFieldCancelsTheAsk() {
        start();
        ask();

        dialogs.submit(0, "cancel");

        assertThat(alex.said()).containsExactly(STOPPED);
        assertThat(judged).isEmpty();
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void theDeadlineTakesTheDialogAwayAndOpensTheOrigin() {
        start();
        ask();

        timer.fire(Prompts.TIMEOUT);

        assertThat(dialogs.closed).containsExactly(alex.player);
        assertThat(alex.said()).containsExactly(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
        dialogs.submit(0, "7");
        assertThat(judged).isEmpty();
    }

    @Test
    void aMenuThatOpensTakesTheDialogAwayAndEndsTheAskAsReplaced() {
        start();
        ask();

        harness.open(alex, Sample.KIND.id());

        assertThat(dialogs.closed).containsExactly(alex.player);
        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void aNewerAskOfOtherPromptsOfThePluginTakesTheDialogAwayAndEndsTheAskAsReplaced() {
        start();
        Prompts other = new Prompts(harness.another(), PromptMode.CHAT, bedrock::contains, timer, null, link,
            PromptFakes::ownScreen, harness.clock, prompts.asks());
        ask();

        other.ask(alex.player, text());

        assertThat(dialogs.closed).containsExactly(alex.player);
        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(other.pending(alex.id)).isNotNull();
        dialogs.submit(0, "7");
        assertThat(judged).isEmpty();
    }

    @Test
    void aChatLineIsLeftAloneWhileADialogAskWaits() {
        start();
        ask();

        assertThat(type("7").isCancelled()).isFalse();

        assertThat(judged).isEmpty();
        assertThat(prompts.pending(alex.id)).isNotNull();
    }

    @Test
    void aBedrockPlayerIsAskedInChat() {
        start();
        bedrock.add(alex.id);

        ask();

        assertThat(dialogs.shown).isEmpty();
        assertThat(alex.said()).containsExactly("[Server] Price?", HINT);
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(type("7").isCancelled()).isTrue();
        assertThat(state()).isEqualTo(7);
    }

    @Test
    void noDialogShowsWhileAnAnswerIsDecidedLaterAndARefusalShowsItAgain() {
        start();
        CompletableFuture<Answer> later = new CompletableFuture<>();
        ask(text -> new Answer.Later(later), "10");

        dialogs.submit(0, "7");
        harness.drain();

        assertThat(dialogs.shown).hasSize(1);
        assertThat(prompts.pending(alex.id)).isNull();
        later.complete(new Answer.Retry(NOT_A_NUMBER));
        harness.drain();
        assertThat(dialogs.shown).hasSize(2);
        assertThat(dialogs.last().initial()).isEqualTo("7");
        assertThat(alex.said()).isEmpty();
        assertThat(prompts.pending(alex.id)).isNotNull();
    }

    @Test
    void aRefusedAnswerEndsTheAskAsReplacedWhenAnotherInventoryIsOpenOnTheNextTick() {
        start();
        ask();
        dialogs.submit(0, "nope");
        alex.foreign(GuiFakes.foreign(27));

        harness.drain();

        assertThat(dialogs.shown).hasSize(1);
        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void aQuitForgetsTheDialogAndEndsTheAskAsLeft() {
        start();
        ask();

        harness.menus.prompts().quit(alex.id);
        chat.onQuit(new PlayerQuitEvent(alex.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(ends).containsExactly(PromptEnd.LEFT);
        assertThat(dialogs.closed).isEmpty();
        dialogs.submit(0, "7");
        assertThat(judged).isEmpty();
    }

    @Test
    void theMenusShutdownTakesTheDialogAwayAndEndsTheAskAsCancelled() {
        start();
        ask();

        harness.menus.shutdown();

        assertThat(dialogs.closed).containsExactly(alex.player);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(alex.said()).isEmpty();
        dialogs.cancel(0);
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void aDialogApiThatCannotBeLinkedAsksInChatUnderTheSameHandOffAndEveryLaterAskToo() {
        start();
        dialogs.failure = new NoSuchMethodError("DialogBase.builder");

        ask();

        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(alex.said()).containsExactly("[Server] Price?", HINT + " [Cancel]");
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT);
        assertThat(harness.log.lines()).filteredOn(line -> line.contains("could not be linked")).hasSize(1);
        assertThat(type("7").isCancelled()).isTrue();
        assertThat(state()).isEqualTo(7);
        dialogs.failure = null;
        ask();
        assertThat(dialogs.shown).isEmpty();
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        link.click(1);
        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(harness.log.lines()).filteredOn(line -> line.contains("could not be linked")).hasSize(1);
    }

    @Test
    void aDialogTheServerFailsToShowLeavesNoAskBehind() {
        start();
        dialogs.failure = new IllegalStateException("refused");

        assertThatIllegalStateException().isThrownBy(this::ask).withMessage("refused");

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(timer.waits()).isEmpty();
        assertThat(surface.usable()).isTrue();
    }

    @Test
    void aDialogTheServerFailsToShowAgainAfterARefusalEndsTheAskAsReplaced() {
        start();
        ask();
        dialogs.submit(0, "nope");
        dialogs.failure = new IllegalStateException("refused");

        assertThatIllegalStateException().isThrownBy(harness::drain).withMessage("refused");

        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(dialogs.shown).hasSize(1);
    }

    @Test
    void aTextDialogTheServerFailsToShowAgainAfterARefusalRunsEndedOnce() {
        alone();
        prompts.ask(alex.player, text());
        dialogs.submit(0, "ab");
        dialogs.failure = new IllegalStateException("refused");

        assertThatIllegalStateException().isThrownBy(harness::drain).withMessage("refused");

        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(accepted).isEmpty();
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void promptsInTheDialogModeWithoutADialogApiAskInChat() {
        sample = new Sample(folder);
        harness = sample.harness;
        alex = sample.viewer;
        prompts = new Prompts(harness.menus, PromptMode.DIALOG, bedrock::contains, timer, null, null, link,
            PromptFakes::ownScreen, harness.clock);

        ask();

        assertThat(alex.said()).containsExactly("[Server] Price?", HINT + " [Cancel]");
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
    }

    @Test
    void aTextQuestionInADialogRunsAcceptedForAnAnswerThatPassesTheCheck() {
        alone();

        prompts.ask(alex.player, text());

        assertThat(alex.said()).isEmpty();
        assertThat(MenuHarness.plain(dialogs.last().title())).isEqualTo("Name?");
        assertThat(dialogs.last().initial()).isEqualTo("crate");
        dialogs.submit(0, "ab");
        harness.drain();
        assertThat(MenuHarness.plain(dialogs.last().note())).isEqualTo("Not a number.\nYou typed: ab");
        assertThat(dialogs.last().initial()).isEqualTo("ab");
        dialogs.submit(1, "gold");
        assertThat(accepted).containsExactly("gold");
        assertThat(ends).isEmpty();
        assertThat(dialogs.closed).isEmpty();
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void theCancelButtonAndTheDeadlineEndATextQuestionAndShutdownTakesItsDialogAway() {
        alone();
        prompts.ask(alex.player, text());

        dialogs.cancel(0);

        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(alex.said()).containsExactly(STOPPED);
        prompts.ask(alex.player, text());
        harness.advance(Duration.ofSeconds(31));
        timer.fire(Prompts.TIMEOUT);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED, PromptEnd.TIMED_OUT);
        assertThat(dialogs.closed).containsExactly(alex.player);
        prompts.ask(alex.player, text());
        prompts.shutdown();
        assertThat(ends).containsExactly(PromptEnd.CANCELLED, PromptEnd.TIMED_OUT, PromptEnd.CANCELLED);
        assertThat(dialogs.closed).containsExactly(alex.player, alex.player);
    }

    @Test
    void theListenerOfPromptsOverNoMenusForgetsTheDialogOfAPlayerWhoQuits() {
        alone();
        prompts.ask(alex.player, text());

        chat.onQuit(new PlayerQuitEvent(alex.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(ends).containsExactly(PromptEnd.LEFT);
        dialogs.submit(0, "gold");
        assertThat(accepted).isEmpty();
    }
}
