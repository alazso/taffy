package so.alaz.taffy.gui;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Prompts built over no menus, and the TextRequest path of prompts built over menus.
final class TextPromptsTest {

    private static final String HINT = "Type it in chat, or type cancel to stop.";
    private static final String STOPPED = "Stopped. Nothing changed.";
    private static final String TIMED_OUT = "No answer within 30 seconds, so nothing changed.";
    private static final String ERROR = "Something went wrong. Try again in a moment.";
    private static final Component TOO_SHORT = Component.text("Too short.");

    @TempDir
    Path folder;

    private final PromptFakes.ManualTimer timer = new PromptFakes.ManualTimer();
    private final PromptFakes.FakeLink link = new PromptFakes.FakeLink();
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<Listener> registered = new ArrayList<>();
    private final List<String> checked = new ArrayList<>();
    private final List<String> accepted = new ArrayList<>();
    private final List<PromptEnd> ends = new ArrayList<>();
    private boolean owned = true;
    private MenuHarness harness;
    private MenuHarness.Viewer alex;
    private Prompts prompts;
    private PaperPromptListener chat;

    // Chat prompts built over no menus, for a viewer with no menu open.
    private void alone() {
        harness = new MenuHarness(folder);
        alex = harness.viewer("Alex");
        prompts = alone(PromptMode.CHAT, null, Asks.of(harness.plugin));
        chat = prompts.promptListener();
    }

    private Prompts alone(PromptMode mode, @Nullable AnvilInput anvil, Asks asks) {
        return new Prompts(parts(), mode, bedrock::contains, timer, anvil, null, link, PromptFakes::ownScreen,
            harness.clock, player -> owned, registered::add, asks);
    }

    private Menus.Parts parts() {
        return Menus.Parts.of(harness.plugin, harness.messages, harness.tasks, harness.entity, harness.errors);
    }

    // Refuses a text of fewer than three characters.
    private @Nullable Component refusal(String text) {
        checked.add(text);
        return text.length() < 3 ? TOO_SHORT : null;
    }

    private TextRequest request() {
        return new TextRequest(Component.text("Name?"), "crate", this::refusal, accepted::add, ends::add);
    }

    private TextRequest later(Function<String, CompletableFuture<@Nullable Component>> check) {
        return TextRequest.later(Component.text("Name?"), "crate", check, accepted::add, ends::add);
    }

    private AsyncChatEvent type(String text) {
        Component message = Component.text(text);
        AsyncChatEvent event = new AsyncChatEvent(true, alex.player, new HashSet<>(), ChatRenderer.defaultRenderer(),
            message, message, mock(SignedMessage.class));
        chat.onChat(event);
        return event;
    }

    @Test
    void promptsOverNoMenusRegisterTheirListenerAndAskInChatWithTheCancelLink() {
        alone();

        prompts.ask(alex.player, request());

        assertThat(registered).containsExactly(chat);
        assertThat(prompts.alone()).isTrue();
        assertThat(alex.said()).containsExactly("[Server] Name?", HINT + " [Cancel]");
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT);
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(prompts.pending(alex.id).request()).isNull();
    }

    @Test
    void anAnswerTheCheckLetsThroughRunsAcceptedOnceAndOpensNothing() {
        alone();
        prompts.ask(alex.player, request());

        AsyncChatEvent event = type("  gold ");

        assertThat(event.isCancelled()).isTrue();
        assertThat(checked).containsExactly("gold");
        assertThat(accepted).containsExactly("gold");
        assertThat(ends).isEmpty();
        assertThat(alex.view).isNull();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("more").isCancelled()).isFalse();
        timer.fire(Prompts.TIMEOUT);
        assertThat(ends).isEmpty();
    }

    @Test
    void aRefusedAnswerTellsTheReasonAndTheAskTakesTheNextLineBeforeItsFirstDeadline() {
        alone();
        long asked = harness.clock.getAsLong();
        prompts.ask(alex.player, request());
        harness.advance(Duration.ofSeconds(10));

        type("ab");

        assertThat(alex.said().getLast()).isEqualTo("Too short.\nYou typed: ab");
        assertThat(accepted).isEmpty();
        assertThat(ends).isEmpty();
        assertThat(prompts.pending(alex.id).deadline()).isEqualTo(asked + Prompts.TIMEOUT.toNanos());
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT, Duration.ofSeconds(20));
        type("abc");
        assertThat(accepted).containsExactly("abc");
    }

    @Test
    void theCancelWordAndTheCancelLinkEndTheAskAsCancelledWithTheMessage() {
        alone();
        prompts.ask(alex.player, request());

        type("Cancel");

        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(checked).isEmpty();
        prompts.ask(alex.player, request());
        link.click(1);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED, PromptEnd.CANCELLED);
        assertThat(accepted).isEmpty();
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void theDeadlineEndsTheAskAsTimedOutWithTheMessage() {
        alone();
        prompts.ask(alex.player, request());

        timer.fire(Prompts.TIMEOUT);

        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(ends).containsExactly(PromptEnd.TIMED_OUT);
        assertThat(type("late").isCancelled()).isFalse();
    }

    @Test
    void aBedrockPlayerGetsNoCancelLink() {
        alone();
        bedrock.add(alex.id);

        prompts.ask(alex.player, request());

        assertThat(alex.said()).containsExactly("[Server] Name?", HINT);
        assertThat(link.actions).isEmpty();
    }

    @Test
    void theListenerOfPromptsOverNoMenusEndsTheAskOfAPlayerWhoQuitsAsLeft() {
        alone();
        prompts.ask(alex.player, request());

        chat.onQuit(new PlayerQuitEvent(alex.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(ends).containsExactly(PromptEnd.LEFT);
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void theListenerOfPromptsOverMenusLeavesAQuitToTheMenus() {
        Sample sample = new Sample(folder);
        harness = sample.harness;
        alex = sample.viewer;
        prompts = new Prompts(harness.menus, PromptMode.CHAT, bedrock::contains, timer, null, link,
            PromptFakes::ownScreen, harness.clock);
        prompts.ask(alex.player, request());

        prompts.promptListener().onQuit(new PlayerQuitEvent(alex.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(prompts.alone()).isFalse();
        assertThat(ends).isEmpty();
        assertThat(prompts.pending(alex.id)).isNotNull();
    }

    @Test
    void anAnswerForAPlayerWhoIsGoneEndsTheAskAsLeft() {
        alone();
        prompts.ask(alex.player, request());
        alex.offline();

        type("gold");

        assertThat(ends).containsExactly(PromptEnd.LEFT);
        assertThat(accepted).isEmpty();
    }

    @Test
    void aNewerAskOfOtherPromptsOfThePluginReplacesTheAskAndOneLineAnswersOneAsk() {
        alone();
        Prompts other = alone(PromptMode.CHAT, null, prompts.asks());
        List<String> second = new ArrayList<>();
        prompts.ask(alex.player, request());

        other.ask(alex.player, new TextRequest(Component.text("Cost?"), "", text -> null, second::add, end -> { }));

        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(prompts.pending(alex.id)).isNull();
        type("gold");
        assertThat(second).containsExactly("gold");
        assertThat(accepted).isEmpty();
    }

    @Test
    void aCheckThatThrowsIsReportedToldAsAnErrorAndEndsTheAskAsCancelled() {
        alone();
        IllegalStateException failure = new IllegalStateException("boom");
        prompts.ask(alex.player, new TextRequest(Component.text("Name?"), "", text -> {
            throw failure;
        }, accepted::add, ends::add));

        type("gold");

        assertThat(alex.said().getLast()).isEqualTo(ERROR);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(accepted).isEmpty();
        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.prompt", failure));
        assertThat(harness.log.lines()).anyMatch(line -> line.contains("a prompt answer failed for Alex"));
    }

    @Test
    void anAcceptedStepThatThrowsIsLoggedAndReportedAndEndsNothing() {
        alone();
        IllegalStateException failure = new IllegalStateException("boom");
        prompts.ask(alex.player, new TextRequest(Component.text("Name?"), "", text -> null, text -> {
            throw failure;
        }, ends::add));

        type("gold");

        assertThat(ends).isEmpty();
        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.prompt", failure));
        assertThat(harness.log.lines()).anyMatch(line -> line.contains("the accepted step of a prompt for Alex"));
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void aCheckThatDecidesLaterTakesNoLineMeanwhileAndAcceptsWhenItsFutureCompletesWithNull() {
        alone();
        CompletableFuture<@Nullable Component> decision = new CompletableFuture<>();
        prompts.ask(alex.player, later(text -> decision));
        type("gold");

        assertThat(accepted).isEmpty();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("chatter").isCancelled()).isFalse();
        decision.complete(null);

        assertThat(accepted).containsExactly("gold");
        assertThat(ends).isEmpty();
    }

    @Test
    void aLaterRefusalTellsTheReasonAndTheAskWaitsAgain() {
        alone();
        CompletableFuture<@Nullable Component> decision = new CompletableFuture<>();
        List<CompletableFuture<@Nullable Component>> decisions =
            new ArrayList<>(List.of(decision, CompletableFuture.completedFuture(null)));
        prompts.ask(alex.player, later(text -> decisions.removeFirst()));
        type("gold");

        decision.complete(TOO_SHORT);

        assertThat(alex.said().getLast()).isEqualTo("Too short.\nYou typed: gold");
        assertThat(prompts.pending(alex.id)).isNotNull();
        type("golden");
        assertThat(accepted).containsExactly("golden");
    }

    @Test
    void aLaterCheckThatFailsIsReportedAndEndsTheAskAsCancelled() {
        alone();
        CompletableFuture<@Nullable Component> decision = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("no economy");
        prompts.ask(alex.player, later(text -> decision));
        type("gold");

        decision.completeExceptionally(failure);

        assertThat(alex.said().getLast()).isEqualTo(ERROR);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.prompt", failure));
    }

    @Test
    void aCancelledFutureEndsTheAskAsCancelledWithNoMessageWhetherItRunsOrIsDone() {
        alone();
        CompletableFuture<@Nullable Component> decision = new CompletableFuture<>();
        prompts.ask(alex.player, later(text -> decision));
        type("gold");
        int said = alex.messages.size();

        decision.cancel(false);

        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        prompts.ask(alex.player, later(text -> decision));
        said += 2;
        type("gold");
        assertThat(ends).containsExactly(PromptEnd.CANCELLED, PromptEnd.CANCELLED);
        assertThat(alex.messages).hasSize(said);
        assertThat(harness.reports).isEmpty();
        assertThat(accepted).isEmpty();
    }

    @Test
    void aLaterCheckThatReturnsNoFutureIsHandledAsOneThatThrows() {
        alone();
        prompts.ask(alex.player, later(text -> null));

        type("gold");

        assertThat(alex.said().getLast()).isEqualTo(ERROR);
        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(harness.reports).singleElement().satisfies(report -> assertThat(report.error())
            .isInstanceOf(NullPointerException.class).hasMessage("the refusal check returned null"));
    }

    @Test
    void theCancelLinkEndsAnAskWhoseCheckStillRunsAndItsResultIsDropped() {
        alone();
        CompletableFuture<@Nullable Component> decision = new CompletableFuture<>();
        prompts.ask(alex.player, later(text -> decision));
        type("gold");

        link.click(0);
        decision.complete(null);

        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(accepted).isEmpty();
    }

    @Test
    void shutdownEndsEveryAskAsCancelledAndNothingIsAskedAfterwards() {
        alone();
        prompts.ask(alex.player, request());
        int said = alex.messages.size();

        prompts.shutdown();

        assertThat(ends).containsExactly(PromptEnd.CANCELLED);
        assertThat(prompts.pending(alex.id)).isNull();
        prompts.ask(alex.player, request());
        prompts.askLater(alex.player, request());
        harness.drain();
        assertThat(alex.messages).hasSize(said);
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(registered).contains(chat);
    }

    @Test
    void promptsOverNoMenusRefuseAQuestionThatOpensAMenu() {
        alone();
        PromptRequest menu = new PromptRequest(Component.text("Price?"), "", new Route("main", 0, null),
            text -> new Answer.Retry(TOO_SHORT));

        assertThatIllegalStateException().isThrownBy(() -> prompts.ask(alex.player, menu))
            .withMessage("these prompts are built over no menus; ask with a TextRequest");
        assertThatNullPointerException().isThrownBy(() -> prompts.ask(alex.player, (PromptRequest) null))
            .withMessage("request");
        assertThatIllegalStateException().isThrownBy(() -> prompts.askLater(alex.player, menu))
            .withMessage("these prompts are built over no menus; ask with a TextRequest");
        assertThatNullPointerException().isThrownBy(() -> prompts.askLater(alex.player, (PromptRequest) null))
            .withMessage("request");
        harness.drain();

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void anAskFromAThreadThatDoesNotOwnThePlayerIsRefusedAndAskLaterAsksOnTheNextTick() {
        alone();
        owned = false;

        assertThatIllegalStateException().isThrownBy(() -> prompts.ask(alex.player, request()))
            .withMessage("Prompts.ask must run on the player's entity thread; use Prompts.askLater");
        assertThatNullPointerException().isThrownBy(() -> prompts.ask(alex.player, (TextRequest) null))
            .withMessage("request");
        assertThatNullPointerException().isThrownBy(() -> prompts.askLater(alex.player, (TextRequest) null))
            .withMessage("request");
        owned = true;
        prompts.askLater(alex.player, request());

        assertThat(prompts.pending(alex.id)).isNull();
        harness.drain();
        assertThat(prompts.pending(alex.id)).isNotNull();
    }

    @Test
    void aTextAskOfPromptsOverMenusClosesTheOpenMenuAsLeftAndAMenuOpeningEndsIt() {
        Sample sample = new Sample(folder);
        harness = sample.harness;
        alex = sample.viewer;
        prompts = new Prompts(harness.menus, PromptMode.CHAT, bedrock::contains, timer, null, link,
            PromptFakes::ownScreen, harness.clock);
        chat = prompts.promptListener();

        prompts.ask(alex.player, request());

        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(alex.said()).containsExactly("[Server] Name?", HINT + " [Cancel]");
        harness.open(alex, Sample.KIND.id());
        assertThat(ends).containsExactly(PromptEnd.REPLACED);
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void anAcceptedTextAnswerOfPromptsOverMenusOpensNoMenu() {
        Sample sample = new Sample(folder);
        harness = sample.harness;
        alex = sample.viewer;
        prompts = new Prompts(harness.menus, PromptMode.CHAT, bedrock::contains, timer, null, link,
            PromptFakes::ownScreen, harness.clock);
        chat = prompts.promptListener();
        prompts.ask(alex.player, request());

        type("gold");

        assertThat(accepted).containsExactly("gold");
        assertThat(alex.session()).isNull();
        assertThat(sample.menu.sessions).hasSize(1);
    }

    @Test
    void thePublicConstructorRegistersThroughTheServerAndBuildsAnAnvilOverThePreviewKey() {
        harness = new MenuHarness(folder);
        alex = harness.viewer("Alex");
        Server server = harness.plugin.getServer();
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        NamespacedKey key = new NamespacedKey("taffytest", "own");

        Prompts built = new Prompts(parts().preview(key), PromptMode.ANVIL, BedrockPlayers.NONE, timer);

        verify(manager).registerEvents(built.promptListener(), harness.plugin);
        assertThat(built.anvil()).isNotNull();
        assertThat(built.anvil().marker().key()).isEqualTo(key);
        assertThat(built.alone()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> built.ask(alex.player, request()));
        when(server.isOwnedByCurrentRegion(alex.player)).thenReturn(true);
        bedrock.add(alex.id);
        Prompts chatOnly = new Prompts(parts(), PromptMode.CHAT, bedrock::contains, timer);
        chatOnly.ask(alex.player, request());
        assertThat(chatOnly.anvil()).isNull();
        assertThat(chatOnly.pending(alex.id)).isNotNull();
    }

    @Test
    void theConstructorOverPartsRefusesEveryNullArgument() {
        harness = new MenuHarness(folder);
        Menus.Parts parts = parts();
        Menus.Parts absent = null;
        BedrockPlayers none = BedrockPlayers.NONE;

        assertThatNullPointerException().isThrownBy(() -> new Prompts(absent, PromptMode.CHAT, none, timer))
            .withMessage("parts");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(parts, null, none, timer)).withMessage("mode");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(absent, PromptMode.CHAT, none, timer, null,
            null, link, PromptFakes::ownScreen, harness.clock, player -> true, registered::add)).withMessage("parts");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(parts, PromptMode.CHAT, null, timer, null,
            null, link, PromptFakes::ownScreen, harness.clock, player -> true, registered::add))
            .withMessage("bedrock");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(parts, PromptMode.CHAT, none, null, null,
            null, link, PromptFakes::ownScreen, harness.clock, player -> true, registered::add)).withMessage("timer");
        assertThat(registered).isEmpty();
    }

    @Test
    void aTextRequestRefusesEveryNullPart() {
        Component question = Component.text("Name?");
        Function<String, @Nullable Component> check = text -> null;
        Function<String, CompletableFuture<@Nullable Component>> later = text -> new CompletableFuture<>();

        assertThatNullPointerException().isThrownBy(() -> new TextRequest(null, "", check, accepted::add, ends::add))
            .withMessage("question");
        assertThatNullPointerException().isThrownBy(() -> new TextRequest(question, null, check, accepted::add,
            ends::add)).withMessage("initial");
        assertThatNullPointerException().isThrownBy(() -> new TextRequest(question, "", null, accepted::add,
            ends::add)).withMessage("refusal");
        assertThatNullPointerException().isThrownBy(() -> new TextRequest(question, "", check, null, ends::add))
            .withMessage("accepted");
        assertThatNullPointerException().isThrownBy(() -> new TextRequest(question, "", check, accepted::add, null))
            .withMessage("ended");
        assertThatNullPointerException().isThrownBy(() -> TextRequest.later(question, "", null, accepted::add,
            ends::add)).withMessage("refusal");
        TextRequest built = TextRequest.later(question, "crate", later, accepted::add, ends::add);
        assertThat(built.question()).isSameAs(question);
        assertThat(built.initial()).isEqualTo("crate");
        built.accepted().accept("a");
        built.ended().accept(PromptEnd.LEFT);
        assertThat(accepted).containsExactly("a");
        assertThat(ends).containsExactly(PromptEnd.LEFT);
    }

    @Test
    void anAskHoldsExactlyOneQuestion() {
        TextRequest text = request();
        PromptRequest menu = new PromptRequest(Component.text("Price?"), "10", new Route("main", 0, null),
            answer -> new Answer.Retry(TOO_SHORT), ends::add);

        assertThat(new Ask(null, text).origin()).isNull();
        assertThat(new Ask(menu, null).origin()).isSameAs(menu.origin());
        assertThat(new Ask(menu, null).initial()).isEqualTo("10");
        assertThat(new Ask(null, text).initial()).isEqualTo("crate");
        assertThat(new Ask(null, text).ended()).isSameAs(text.ended());
        assertThatIllegalArgumentException().isThrownBy(() -> new Ask(menu, text));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ask(null, null));
    }
}
