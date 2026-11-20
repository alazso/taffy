package so.alaz.taffy.gui;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import so.alaz.taffy.platform.Messages;
import so.alaz.taffy.test.CapturedLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PromptsTest {

    private static final String HINT = "Type it in chat, or type cancel to stop.";
    private static final String STOPPED = "Stopped. Nothing changed.";
    private static final String TIMED_OUT = "No answer within 30 seconds, so nothing changed.";
    private static final Component NOT_A_NUMBER = Component.text("Not a number.");

    @TempDir
    Path folder;

    private final ManualTimer timer = new ManualTimer();
    private final FakeLink link = new FakeLink();
    private @Nullable RealAnvil real;
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<String> judged = new ArrayList<>();
    private boolean owned = true;
    private Sample sample;
    private MenuHarness harness;
    private MenuHarness.Viewer alex;
    private Prompts prompts;
    private PaperPromptListener chat;

    private void start(PromptMode mode) {
        start(mode, mode == PromptMode.ANVIL, null);
    }

    // With an anvil, the real AnvilInput over mocked anvil views and the prompts' listener are installed on the
    // viewer. With messages, the menus and the prompts read those lang files.
    private void start(PromptMode mode, boolean withAnvil, @Nullable Messages messages) {
        sample = messages == null ? new Sample(folder) : new Sample(folder, messages);
        harness = sample.harness;
        alex = sample.viewer;
        harness.owned = player -> owned;
        real = withAnvil ? new RealAnvil() : null;
        prompts = new Prompts(harness.menus, mode, bedrock::contains, timer, real == null ? null : real.input, link,
            PromptsTest::ownScreen, harness.clock);
        if (real != null) {
            real.install(alex);
        }
        chat = (PaperPromptListener) prompts.listener();
        sample.menu.sessions.getLast().state(2);
    }

    private RealAnvil real() {
        assertThat(real).as("an installed anvil").isNotNull();
        return real;
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
        ask(this::judge);
    }

    private void ask(Function<String, Answer> judge) {
        MenuSession<?> session = alex.session();
        Route origin = session == null ? new Route(Sample.KIND.id(), 2, null) : session.route();
        prompts.ask(alex.player, new PromptRequest(Component.text("Price?"), "10", origin, judge));
    }

    private long token() {
        Pending pending = prompts.pending(alex.id);
        assertThat(pending).isNotNull();
        return pending.token();
    }

    private AsyncChatEvent type(String text) {
        return type(text, false);
    }

    private AsyncChatEvent type(String text, boolean cancelled) {
        Component message = Component.text(text);
        AsyncChatEvent event = new AsyncChatEvent(true, alex.player, new HashSet<>(), ChatRenderer.defaultRenderer(),
            message, message, mock(SignedMessage.class));
        event.setCancelled(cancelled);
        chat.onChat(event);
        return event;
    }

    // Delivers event to every handler of the prompts' listener that takes its type, as the server's event bus does.
    private void post(Event event) {
        try {
            for (Method method : PaperPromptListener.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventHandler.class)
                    && method.getParameterTypes()[0].isInstance(event)) {
                    method.invoke(chat, event);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object state() {
        MenuSession<?> session = alex.session();
        assertThat(session).as("an open menu").isNotNull();
        return session.state();
    }

    @Test
    void aChatAskClosesTheMenuAsAHandOffAndSendsTheQuestionAndTheHintWithTheCancelLink() {
        start(PromptMode.CHAT);

        ask();

        assertThat(alex.view).isNull();
        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(alex.said()).containsExactly("[Server] Price?", HINT + " [Cancel]");
        assertThat(alex.messages.get(1).children().getLast().clickEvent()).isSameAs(link.events.getFirst());
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT);
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
    }

    @Test
    void anAnswerIsTakenFromChatAndTheAcceptedRouteOpensWithItsState() {
        start(PromptMode.CHAT);
        ask();

        AsyncChatEvent event = type("  7 ");

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.message()).isEqualTo(Component.text("  7 "));
        assertThat(judged).containsExactly("7");
        assertThat(state()).isEqualTo(7);
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("8").isCancelled()).isFalse();
    }

    @Test
    void aRefusedAnswerSaysWhyRepeatsTheInputAsTextAndTakesTheNextLineBeforeTheFirstDeadline() {
        start(PromptMode.CHAT);
        Instant asked = harness.clock.instant();
        ask();
        harness.advance(Duration.ofSeconds(10));

        assertThat(type("<red>nope").isCancelled()).isTrue();

        assertThat(alex.said().getLast()).isEqualTo("Not a number.\nYou typed: <red>nope");
        assertThat(alex.session()).isNull();
        assertThat(prompts.pending(alex.id).deadline()).isEqualTo(asked.plus(Prompts.TIMEOUT));
        assertThat(timer.waits()).containsExactly(Prompts.TIMEOUT, Duration.ofSeconds(20));
        assertThat(type("5").isCancelled()).isTrue();
        assertThat(state()).isEqualTo(5);
        assertThat(judged).containsExactly("<red>nope", "5");
    }

    @Test
    void theTimerForTheTimeLeftAfterARefusalTimesTheAskOutOnceAndTheFirstTimerAddsNothing() {
        start(PromptMode.CHAT);
        ask();
        harness.advance(Duration.ofSeconds(10));
        type("nope");

        timer.fire(Duration.ofSeconds(20));

        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
        assertThat(prompts.pending(alex.id)).isNull();
        int opened = sample.menu.sessions.size();
        int said = alex.messages.size();
        timer.fire(Prompts.TIMEOUT);

        assertThat(alex.said().stream().filter(line -> line.endsWith(TIMED_OUT))).hasSize(1);
        assertThat(alex.messages).hasSize(said);
        assertThat(sample.menu.sessions).hasSize(opened);
    }

    @Test
    void aRefusedAnswerAfterTheDeadlineEndsTheAskAtOnce() {
        start(PromptMode.CHAT);
        ask();
        harness.advance(Prompts.TIMEOUT.plusSeconds(1));

        type("nope");

        assertThat(alex.said().getLast()).endsWith(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void aRefusedAnswerDoesNotWaitAgainOnceAnotherMenuOpened() {
        start(PromptMode.CHAT);
        ask(text -> {
            harness.menus.open(alex.player, Sample.KIND.id(), null);
            return new Answer.Retry(NOT_A_NUMBER);
        });

        type("nope");

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(state()).isEqualTo(0);
        assertThat(type("7").isCancelled()).isFalse();
    }

    @Test
    void theCancelWordInAnyCaseAndWithSpacesReopensTheOriginWithItsState() {
        start(PromptMode.CHAT);
        ask();

        assertThat(type("  CaNcEl ").isCancelled()).isTrue();

        assertThat(judged).isEmpty();
        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void theEnglishCancelWordStillWorksWhenTheLocalWordDiffers() throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/de.yml"), "gui.prompt.cancel-word: \"abbrechen\"\n");
        start(PromptMode.CHAT);
        alex.locale = Locale.GERMAN;

        ask();
        assertThat(alex.said().getLast()).contains("abbrechen");
        type("cancel");
        assertThat(state()).isEqualTo(2);
        ask();
        type("ABBRECHEN");

        assertThat(judged).isEmpty();
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void aBlankCancelWordFallsBackToTheEnglishWord() throws IOException {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/de.yml"), "gui.prompt.cancel-word: \"  \"\n");
        start(PromptMode.CHAT);
        alex.locale = Locale.GERMAN;

        ask();

        assertThat(alex.said().getLast()).isEqualTo(HINT + " [Cancel]");
    }

    @Test
    void langFilesWithoutTheCancelWordUseTheEnglishWordAndShowNoPrefixUnlessTheTextWritesIt() {
        Messages bare = Messages.load(folder.resolve("bare"), () -> new ByteArrayInputStream("""
            prefix: "[Server] "
            gui.prompt.question: "<question>"
            gui.prompt.hint: "Type <word> to stop."
            gui.prompt.cancel-button: "[Cancel]"
            gui.prompt.cancelled: "Stopped."
            """.getBytes(StandardCharsets.UTF_8)), new CapturedLog().logger);
        start(PromptMode.CHAT, false, bare);

        ask();
        type("cancel");

        assertThat(alex.said()).containsExactly("Price?", "Type cancel to stop. [Cancel]", "Stopped.");
        assertThat(judged).isEmpty();
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void theCancelLinkReopensTheOriginAndASecondUseDoesNothing() {
        start(PromptMode.CHAT);
        ask();

        link.click(0);
        int opened = sample.menu.sessions.size();
        int said = alex.messages.size();
        link.click(0);

        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(state()).isEqualTo(2);
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.messages).hasSize(said);
    }

    @Test
    void theTimeoutReopensTheOriginAndALaterLineIsLeftAlone() {
        start(PromptMode.CHAT);
        ask();

        timer.fire(Prompts.TIMEOUT);

        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
        assertThat(type("7").isCancelled()).isFalse();
        assertThat(judged).isEmpty();
    }

    @Test
    void aNewAskEndsTheFirstSilentlyAndTheFirstTimeoutDoesNothing() {
        start(PromptMode.CHAT);
        ask();
        long first = token();

        ask();

        assertThat(token()).isNotEqualTo(first);
        assertThat(alex.said()).hasSize(4);
        prompts.expire(alex.id, first);
        assertThat(alex.said()).hasSize(4);
        assertThat(prompts.pending(alex.id)).isNotNull();
        timer.fire(Prompts.TIMEOUT);
        assertThat(alex.said().stream().filter(line -> line.endsWith(TIMED_OUT))).hasSize(1);
    }

    @Test
    void anAskEndedByAnotherMenuReopensNothingWhenItsTimerFires() {
        start(PromptMode.CHAT);
        ask();

        harness.open(alex, Sample.KIND.id());
        int opened = sample.menu.sessions.size();
        timer.fire(Prompts.TIMEOUT);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.said()).noneMatch(line -> line.endsWith(TIMED_OUT));
        assertThat(type("7").isCancelled()).isFalse();
    }

    @Test
    void aQuitRemovesTheAsk() {
        start(PromptMode.CHAT);
        ask();

        prompts.quit(alex.id);
        timer.fire(Prompts.TIMEOUT);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said()).hasSize(2);
    }

    @Test
    void aQuitPostedToTheMenuListenerForgetsTheAnvilAndAnswersNothingAfterwards() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        AnvilView view = real().shown;

        harness.listener.onQuit(new PlayerQuitEvent(alex.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));
        timer.fire(Prompts.TIMEOUT);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(prompts.owns(view)).isFalse();
        assertThat(real().click(Prompts.ANVIL_RESULT_SLOT).isCancelled()).isFalse();
        assertThat(judged).isEmpty();
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void askLaterAsksOnTheNextTickSoAButtonAsksOutsideItsClick() {
        start(PromptMode.CHAT);
        PromptRequest request = new PromptRequest(Component.text("Price?"), "10", sample.session().route(),
            this::judge);

        prompts.askLater(alex.player, request);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.closes).isEmpty();
        assertThat(alex.said()).isEmpty();
        harness.drain();
        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(alex.said()).containsExactly("[Server] Price?", HINT + " [Cancel]");
        assertThatNullPointerException().isThrownBy(() -> prompts.askLater(alex.player, null))
            .withMessage("request");
    }

    @Test
    void askLaterAsksNothingOnceShutdownRanBeforeTheTick() {
        start(PromptMode.CHAT);
        prompts.askLater(alex.player, new PromptRequest(Component.text("Price?"), "10", sample.session().route(),
            this::judge));

        prompts.shutdown();
        harness.drain();

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said()).isEmpty();
        assertThat(alex.session()).isNotNull();
    }

    @Test
    void aDeadPlayerIsToldButNotReopened() {
        start(PromptMode.CHAT);
        ask();
        int opened = sample.menu.sessions.size();
        when(alex.player.isDead()).thenReturn(true);

        timer.fire(Prompts.TIMEOUT);

        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(alex.session()).isNull();
        assertThat(sample.menu.sessions).hasSize(opened);
    }

    @Test
    void shutdownEndsAChatAskAndReopensNothing() {
        start(PromptMode.CHAT);
        ask();
        int opened = sample.menu.sessions.size();

        prompts.shutdown();
        timer.fire(Prompts.TIMEOUT);
        link.click(0);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("7").isCancelled()).isFalse();
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.said()).hasSize(2);
        ask();
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    @SuppressWarnings("deprecation")
    void aLegacyChatEventTakesTheAnswerFirstAndTheModernEventAfterItAnswersNothing() {
        start(PromptMode.CHAT);
        ask();

        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, alex.player, " 7 ", new HashSet<>());
        post(legacy);
        Component message = Component.text(" 7 ");
        AsyncChatEvent modern = new AsyncChatEvent(true, alex.player, new HashSet<>(), ChatRenderer.defaultRenderer(),
            message, message, mock(SignedMessage.class));
        modern.setCancelled(legacy.isCancelled());
        post(modern);

        assertThat(legacy.isCancelled()).isTrue();
        assertThat(legacy.getMessage()).isEqualTo(" 7 ");
        assertThat(modern.isCancelled()).isTrue();
        assertThat(judged).containsExactly("7");
        assertThat(state()).isEqualTo(7);
        AsyncPlayerChatEvent free = new AsyncPlayerChatEvent(true, alex.player, "hello", new HashSet<>());
        post(free);
        assertThat(free.isCancelled()).isFalse();
    }

    @Test
    void aLongLineIsCutTo256Characters() {
        start(PromptMode.CHAT);
        List<String> seen = new ArrayList<>();
        ask(text -> {
            seen.add(text);
            return new Answer.Accept(new Route(Sample.KIND.id(), 3, null));
        });

        type("a".repeat(300));

        assertThat(seen).containsExactly("a".repeat(Prompts.MAX_LENGTH));
        assertThat(state()).isEqualTo(3);
    }

    @Test
    void aSurrogatePairIsNotSplitByTheCut() {
        String text = "a".repeat(Prompts.MAX_LENGTH - 1) + "😀";

        assertThat(Prompts.clean(text)).isEqualTo("a".repeat(Prompts.MAX_LENGTH - 1));
        assertThat(Prompts.clean("  short  ")).isEqualTo("short");
    }

    @Test
    void anAnswerFunctionThatThrowsIsLoggedReportedTellsThePlayerAndReopensTheOrigin() {
        start(PromptMode.CHAT);
        IllegalStateException failure = new IllegalStateException("boom");
        ask(text -> {
            throw failure;
        });

        type("7");

        assertThat(alex.said().getLast()).isEqualTo("Something went wrong. Try again in a moment.");
        assertThat(state()).isEqualTo(2);
        assertThat(harness.log.lines())
            .anyMatch(line -> line.startsWith("WARNING gui: a prompt answer failed for Alex"));
        assertThat(harness.log.records.getLast().getThrown()).isSameAs(failure);
        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.prompt", failure));
    }

    @Test
    void anAnswerFunctionThatReturnsNullIsHandledAsAFailureAndReopensTheOrigin() {
        start(PromptMode.CHAT);
        ask(text -> null);

        type("7");

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said().getLast()).isEqualTo("Something went wrong. Try again in a moment.");
        assertThat(state()).isEqualTo(2);
        assertThat(harness.log.lines())
            .anyMatch(line -> line.startsWith("WARNING gui: a prompt answer failed for Alex"));
        assertThat(harness.reports).singleElement().satisfies(report -> {
            assertThat(report.source()).isEqualTo("gui.prompt");
            assertThat(report.error()).isInstanceOf(NullPointerException.class)
                .hasMessage("the answer function returned null");
        });
    }

    @Test
    void aFailureOnTheEntityThreadIsLoggedAndReported() {
        start(PromptMode.CHAT);
        ask(text -> new Answer.Accept(new Route("nowhere", 1, null)));

        type("7");

        assertThat(harness.log.lines())
            .anyMatch(line -> line.startsWith("WARNING gui: a prompt for " + alex.id + " failed: "));
        assertThat(harness.reports).singleElement().satisfies(report -> {
            assertThat(report.source()).isEqualTo("gui.prompt");
            assertThat(report.error()).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no menu is registered as nowhere");
        });
    }

    @Test
    void aPlayerWhoLeftBeforeTheAnswerIsJudgedIsNoFailure() {
        start(PromptMode.CHAT);
        ask();
        harness.entity.online.remove(alex.id);

        type("7");

        assertThat(judged).isEmpty();
        assertThat(harness.reports).isEmpty();
        assertThat(harness.log.lines()).noneMatch(line -> line.contains("a prompt for"));
    }

    @Test
    void chatWithoutAPromptIsLeftUntouchedAndAnAlreadyCancelledLineStillAnswers() {
        start(PromptMode.CHAT);

        AsyncChatEvent free = type("hello");

        assertThat(free.isCancelled()).isFalse();
        assertThat(free.message()).isEqualTo(Component.text("hello"));
        ask();
        AsyncChatEvent muted = type("6", true);
        assertThat(muted.isCancelled()).isTrue();
        assertThat(state()).isEqualTo(6);
    }

    @Test
    void anAnvilModeWithoutAnAnvilSurfaceAsksInChat() {
        start(PromptMode.ANVIL, false, null);

        ask();

        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(alex.said()).containsExactly("[Server] Price?", HINT + " [Cancel]");
        type("4");
        assertThat(state()).isEqualTo(4);
    }

    @Test
    void anAnvilAskOpensTheAnvilAsAHandOffAndItsResultSlotSubmitsOnce() {
        start(PromptMode.ANVIL);

        ask();
        long token = token();

        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().input.showing(alex.id, token)).isTrue();
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(alex.said()).isEmpty();
        assertThat(type("5").isCancelled()).isFalse();
        prompts.anvilClick(alex.id, token, 0, "9");
        assertThat(judged).isEmpty();
        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, " 9 ");
        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, " 9 ");
        assertThat(judged).containsExactly("9");
        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(state()).isEqualTo(9);
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void theRealAnvilAndItsListenerSubmitTheResultSlotCloseTheAnvilAndOpenTheAcceptedRoute() {
        start(PromptMode.ANVIL);
        RealAnvil real = real();

        ask();
        long token = token();
        AnvilView view = real.shown;

        assertThat(view).isNotNull();
        assertThat(real.input.showing(alex.id, token)).isTrue();
        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.OPEN_NEW);
        assertThat(sample.menu.closes).containsExactly(false);
        assertThat(real.click(0).isCancelled()).isTrue();
        assertThat(judged).isEmpty();

        real.typed = " 9 ";
        InventoryClickEvent submit = real.click(Prompts.ANVIL_RESULT_SLOT);

        assertThat(submit.isCancelled()).isTrue();
        assertThat(judged).containsExactly("9");
        assertThat(real.shown).isNull();
        assertThat(real.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        verify(view.getTopInventory(), atLeastOnce()).clear();
        assertThat(real.input.showing(alex.id, token)).isFalse();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(state()).isEqualTo(9);
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void closingTheRealAnvilThroughItsListenerCancelsAndReopensTheOrigin() {
        start(PromptMode.ANVIL);
        RealAnvil real = real();
        ask();
        long token = token();
        AnvilView view = real.shown;

        real.close(InventoryCloseEvent.Reason.PLAYER);

        verify(view.getTopInventory()).clear();
        assertThat(real.input.showing(alex.id, token)).isFalse();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(state()).isEqualTo(2);
        assertThat(judged).isEmpty();
    }

    @Test
    void anAnswerThatAsksAgainClearsTheAnsweredAnvilBeforeTheNextOneReplacesIt() {
        start(PromptMode.ANVIL);
        PromptRequest next = new PromptRequest(Component.text("Amount?"), "1", new Route(Sample.KIND.id(), 2, null),
            this::judge);
        ask(text -> {
            prompts.ask(alex.player, next);
            return new Answer.Accept(new Route(Sample.KIND.id(), 5, null));
        });
        long first = token();
        AnvilView answered = real().shown;
        real().typed = "price";

        real().click(Prompts.ANVIL_RESULT_SLOT);

        AnvilView asking = real().shown;
        assertThat(asking).isNotNull().isNotSameAs(answered);
        InOrder order = inOrder(answered.getTopInventory(), alex.player);
        order.verify(answered.getTopInventory()).clear();
        order.verify(alex.player).openInventory(asking);
        assertThat(real().closes).containsExactly(InventoryCloseEvent.Reason.OPEN_NEW);
        assertThat(real().opens).containsExactly("Price? 10", "Amount? 1");
        assertThat(token()).isNotEqualTo(first);
        assertThat(real().input.showing(alex.id, token())).isTrue();
        assertThat(alex.session()).isNull();
        real().typed = "6";
        real().click(Prompts.ANVIL_RESULT_SLOT);
        assertThat(state()).isEqualTo(6);
    }

    @Test
    void anEmptyNameFieldIsJudgedAsEmptyText() {
        start(PromptMode.ANVIL);
        ask();

        prompts.anvilClick(alex.id, token(), Prompts.ANVIL_RESULT_SLOT, null);

        assertThat(judged).containsExactly("");
    }

    @Test
    void closingTheAnvilCancelsAndReopensTheOrigin() {
        start(PromptMode.ANVIL);
        ask();

        prompts.surfaceClosed(alex.id, token(), InventoryCloseEvent.Reason.PLAYER);

        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void anotherPluginsInventoryOverTheAnvilEndsTheAskSilentlyAndOpensNothing() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        int opened = sample.menu.sessions.size();

        prompts.surfaceClosed(alex.id, token, InventoryCloseEvent.Reason.OPEN_NEW);
        harness.drain();
        timer.fire(Prompts.TIMEOUT);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said()).isEmpty();
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(judged).isEmpty();
    }

    @Test
    void aTimeoutWithAnotherPluginsInventoryOpenSaysSoButReopensAndClosesNothing() {
        start(PromptMode.CHAT);
        ask();
        int opened = sample.menu.sessions.size();
        int closes = alex.closes.size();
        FakeView chest = alex.foreign(GuiFakes.foreign(27));

        timer.fire(Prompts.TIMEOUT);
        harness.drain();

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.current()).isSameAs(chest);
        assertThat(alex.closes).hasSize(closes);
    }

    @Test
    void aRefusedAnvilAnswerWithAnotherPluginsInventoryOpenEndsTheAskWithoutShowingTheAnvilAgain() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        int opened = sample.menu.sessions.size();
        real().replaced();
        FakeView chest = alex.foreign(GuiFakes.foreign(27));
        int closes = alex.closes.size();

        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "many");
        harness.drain();
        timer.fire(Prompts.TIMEOUT);
        harness.drain();

        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().closes).isEmpty();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said().getLast()).endsWith("You typed: many");
        assertThat(alex.said()).noneMatch(line -> line.endsWith(TIMED_OUT));
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.current()).isSameAs(chest);
        assertThat(alex.closes).hasSize(closes);
    }

    @Test
    void anAcceptedAnvilAnswerWithAnotherPluginsInventoryOpenOpensNothingOverIt() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        int opened = sample.menu.sessions.size();
        real().replaced();
        FakeView chest = alex.foreign(GuiFakes.foreign(27));

        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "8");

        assertThat(judged).containsExactly("8");
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.current()).isSameAs(chest);
    }

    @Test
    void anAnvilTimeoutClosesTheAnvilAndReopensTheOrigin() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();

        timer.fire(Prompts.TIMEOUT);

        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void aRefusedAnvilAnswerKeepsTheAnvilOpenAndWaiting() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();

        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "lots");
        harness.drain();

        assertThat(alex.said().getLast()).endsWith("You typed: lots");
        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().closes).isEmpty();
        assertThat(real().input.showing(alex.id, token)).isTrue();
        assertThat(token()).isEqualTo(token);
        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "4");
        assertThat(state()).isEqualTo(4);
    }

    @Test
    void aRefusedAnvilAnswerWhoseAnvilClosedShowsTheAnvilAgainOnTheNextTick() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        real().replaced();

        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "lots");
        assertThat(real().opens).containsExactly("Price? 10");
        harness.drain();

        assertThat(real().opens).containsExactly("Price? 10", "Price? 10");
        assertThat(real().input.showing(alex.id, token)).isTrue();
        assertThat(token()).isEqualTo(token);
        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "3");
        assertThat(state()).isEqualTo(3);
    }

    @Test
    void aRefusedAnvilAnswerWhoseAnvilTheServerWillNotShowAgainEndsTheAskWithNothingMore() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        int opened = sample.menu.sessions.size();
        real().replaced();
        real().refuse = true;

        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "lots");
        harness.drain();

        assertThat(real().opens).containsExactly("Price? 10", "Price? 10");
        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(alex.said()).singleElement().asString().endsWith("You typed: lots");
        assertThat(alex.session()).isNull();
        assertThat(sample.menu.sessions).hasSize(opened);
        timer.fire(Prompts.TIMEOUT);
        harness.drain();

        assertThat(alex.said()).hasSize(1);
        assertThat(alex.session()).isNull();
        assertThat(sample.menu.sessions).hasSize(opened);
    }

    @Test
    void anAnvilTheServerRefusesToOpenLeavesNoAskAndTheMenuLeft() {
        start(PromptMode.ANVIL);
        real().refuse = true;

        ask();

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(sample.menu.closes).containsExactly(true);
        assertThat(timer.waits()).isEmpty();
    }

    @Test
    void aClickOnThePromptsAnvilLogsNoLeakAndDeletesNothing() {
        start(PromptMode.ANVIL);
        ask();
        AnvilView view = real().shown;
        Inventory top = view.getTopInventory();
        ItemStack result = GuiFakes.marked(Material.PAPER, harness.marker);
        when(top.getItem(2)).thenReturn(result);
        when(view.getItem(2)).thenReturn(result);

        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2, ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
        harness.listener.onClick(event);

        verify(top, never()).setItem(2, null);
        verify(view, never()).setItem(2, null);
        assertThat(harness.log.lines()).noneMatch(line -> line.contains("preview.leak"));
        assertThat(prompts.owns(view)).isTrue();
        assertThat(prompts.owns(new FakeView(GuiFakes.foreign(27), alex.inventory, alex.player))).isFalse();
    }

    @Test
    void aBedrockPlayerTypesIntoChatWithoutTheLink() {
        start(PromptMode.ANVIL);
        bedrock.add(alex.id);

        ask();

        assertThat(prompts.pending(alex.id).mode()).isEqualTo(PromptMode.CHAT);
        assertThat(real().opens).isEmpty();
        assertThat(alex.said().getLast()).isEqualTo(HINT);
        assertThat(link.events).isEmpty();
        type("7");
        assertThat(state()).isEqualTo(7);
    }

    private Answer accept(int state) {
        return new Answer.Accept(new Route(Sample.KIND.id(), state, null));
    }

    @Test
    void aLaterAnswerTakesNoLineWhileItsFutureRunsAndOpensTheAcceptedRouteOnceItCompletes() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));

        assertThat(type("7").isCancelled()).isTrue();

        assertThat(alex.session()).isNull();
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("8").isCancelled()).isFalse();
        balance.complete(accept(7));
        assertThat(state()).isEqualTo(7);
        assertThat(alex.said()).hasSize(2);
    }

    @Test
    void aLaterAnswerThatRefusesTellsThePlayerAndWaitsForTheNextLine() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> first = new CompletableFuture<>();
        List<CompletableFuture<Answer>> answers = new ArrayList<>(List.of(first,
            CompletableFuture.completedFuture(accept(5))));
        ask(text -> new Answer.Later(answers.removeFirst()));
        type("nope");

        first.complete(new Answer.Retry(NOT_A_NUMBER));

        assertThat(alex.said().getLast()).isEqualTo("Not a number.\nYou typed: nope");
        assertThat(prompts.pending(alex.id)).isNotNull();
        assertThat(type("5").isCancelled()).isTrue();
        assertThat(state()).isEqualTo(5);
    }

    @Test
    void aLaterAnswerThatCompletesWithAnotherLaterWaitsAgain() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> first = new CompletableFuture<>();
        CompletableFuture<Answer> second = new CompletableFuture<>();
        ask(text -> new Answer.Later(first));
        type("7");

        first.complete(new Answer.Later(second));
        assertThat(alex.session()).isNull();
        second.complete(accept(7));

        assertThat(state()).isEqualTo(7);
    }

    @Test
    void aLaterAnswerWhoseFutureFailsIsLoggedReportedTellsThePlayerAndReopensTheOrigin() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("no economy");
        ask(text -> new Answer.Later(balance.thenApply(answer -> answer)));
        type("7");

        balance.completeExceptionally(failure);

        assertThat(alex.said().getLast()).isEqualTo("Something went wrong. Try again in a moment.");
        assertThat(state()).isEqualTo(2);
        assertThat(harness.log.lines())
            .anyMatch(line -> line.startsWith("WARNING gui: a prompt answer failed for Alex"));
        assertThat(harness.log.records.getLast().getThrown()).isSameAs(failure);
        assertThat(harness.reports).containsExactly(new MenuHarness.Report("gui.prompt", failure));
    }

    @Test
    void aLaterAnswerWhoseFutureCompletesWithNullIsHandledAsAFailure() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");

        balance.complete(null);

        assertThat(alex.said().getLast()).isEqualTo("Something went wrong. Try again in a moment.");
        assertThat(state()).isEqualTo(2);
        assertThat(harness.reports).singleElement().satisfies(report -> {
            assertThat(report.source()).isEqualTo("gui.prompt");
            assertThat(report.error()).isInstanceOf(NullPointerException.class)
                .hasMessage("the answer future completed with null");
        });
    }

    @Test
    void aLaterAnswerWhoseFutureWasCancelledReopensTheOriginWithNoMessageLogOrReport() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");
        int said = alex.messages.size();

        balance.cancel(false);

        assertThat(state()).isEqualTo(2);
        assertThat(alex.messages).hasSize(said);
        assertThat(harness.log.lines()).isEmpty();
        assertThat(harness.reports).isEmpty();
    }

    @Test
    void theResultOfALaterAnswerIsDroppedForAPlayerWhoLeft() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");
        int opened = sample.menu.sessions.size();
        int said = alex.messages.size();
        alex.offline();
        harness.listener.onQuit(new PlayerQuitEvent(alex.player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));

        balance.complete(accept(7));

        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.messages).hasSize(said);
        assertThat(harness.log.lines()).isEmpty();
        assertThat(harness.reports).isEmpty();
    }

    @Test
    void aLaterAnswerStillRunningAtTheDeadlineTimesOutAndItsResultIsDropped() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");

        timer.fire(Prompts.TIMEOUT);

        assertThat(alex.said().getLast()).isEqualTo(TIMED_OUT);
        assertThat(state()).isEqualTo(2);
        int opened = sample.menu.sessions.size();
        balance.complete(accept(7));
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void theCancelLinkEndsALaterAnswerStillRunningAndItsResultIsDropped() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");

        link.actions.getFirst().run();

        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        balance.complete(accept(7));
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void theResultOfALaterAnswerIsDroppedOnceAnotherMenuOpenedOrANewerAskStarted() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> first = new CompletableFuture<>();
        CompletableFuture<Answer> second = new CompletableFuture<>();
        ask(text -> new Answer.Later(first));
        type("7");
        harness.menus.open(alex.player, Sample.KIND.id(), null);

        first.complete(accept(7));

        assertThat(state()).isEqualTo(0);
        ask(text -> new Answer.Later(second));
        type("8");
        ask();
        long newer = token();
        second.complete(accept(8));
        assertThat(alex.session()).isNull();
        assertThat(token()).isEqualTo(newer);
    }

    @Test
    void theResultOfALaterAnswerIsDroppedAfterShutdown() {
        start(PromptMode.CHAT);
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        type("7");
        int opened = sample.menu.sessions.size();

        harness.menus.shutdown();
        balance.complete(accept(7));

        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.session()).isNull();
    }

    @Test
    void anAnvilStaysOpenAndSubmitsNothingWhileALaterAnswerRunsAndClosesOnceItIsAccepted() {
        start(PromptMode.ANVIL);
        RealAnvil real = real();
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> {
            judged.add(text);
            return new Answer.Later(balance);
        });
        long token = token();
        real.typed = "9";
        real.click(Prompts.ANVIL_RESULT_SLOT);

        assertThat(real.click(Prompts.ANVIL_RESULT_SLOT).isCancelled()).isTrue();

        assertThat(judged).containsExactly("9");
        assertThat(real.shown).isNotNull();
        assertThat(real.input.showing(alex.id, token)).isTrue();
        balance.complete(accept(9));
        assertThat(real.shown).isNull();
        assertThat(real.closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(state()).isEqualTo(9);
    }

    @Test
    void closingTheAnvilWhileALaterAnswerRunsCancelsTheAskAndItsResultIsDropped() {
        start(PromptMode.ANVIL);
        RealAnvil real = real();
        CompletableFuture<Answer> balance = new CompletableFuture<>();
        ask(text -> new Answer.Later(balance));
        real.typed = "9";
        real.click(Prompts.ANVIL_RESULT_SLOT);

        real.close(InventoryCloseEvent.Reason.PLAYER);

        assertThat(alex.said().getLast()).isEqualTo(STOPPED);
        assertThat(state()).isEqualTo(2);
        int opened = sample.menu.sessions.size();
        balance.complete(accept(9));
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(state()).isEqualTo(2);
    }

    @Test
    void noPlayerIsABedrockPlayerWithoutDetection() {
        assertThat(BedrockPlayers.NONE.bedrock(UUID.randomUUID())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownTakesTheAnvilAwayFromPlayersThisThreadOwnsEmptiesTheRestAndReopensNothing(boolean ownedHere) {
        start(PromptMode.ANVIL);
        owned = ownedHere;
        ask();
        long token = token();
        AnvilView view = real().shown;
        int opened = sample.menu.sessions.size();

        prompts.shutdown();
        timer.fire(Prompts.TIMEOUT);
        harness.drain();

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(prompts.owns(view)).isFalse();
        if (ownedHere) {
            verify(view.getTopInventory()).clear();
            assertThat(real().closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
            assertThat(real().shown).isNull();
        } else {
            verify(view.getTopInventory()).clear();
            assertThat(real().closes).isEmpty();
            assertThat(real().shown).isSameAs(view);
        }
        assertThat(sample.menu.sessions).hasSize(opened);
        assertThat(alex.said()).noneMatch(line -> line.endsWith(TIMED_OUT) || line.endsWith(STOPPED));
    }

    @Test
    void shutdownClosesAnAnvilWhoseSubmitWaitsForATickThatNeverComes() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        harness.entity.online.remove(alex.id);
        prompts.anvilClick(alex.id, token, Prompts.ANVIL_RESULT_SLOT, "4");
        assertThat(prompts.pending(alex.id)).isNull();

        prompts.shutdown();

        assertThat(real().opens).containsExactly("Price? 10");
        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(alex.closes).containsExactly(InventoryCloseEvent.Reason.OPEN_NEW);
        assertThat(real().closes).containsExactly(InventoryCloseEvent.Reason.PLUGIN);
        assertThat(real().shown).isNull();
        assertThat(alex.view).isNull();
        assertThat(judged).isEmpty();
        assertThat(alex.said()).isEmpty();
    }

    @Test
    void anOfflinePlayersAnvilIsEmptiedAndNotClosedAtShutdown() {
        start(PromptMode.ANVIL);
        ask();
        long token = token();
        AnvilView view = real().shown;
        alex.offline();

        prompts.shutdown();

        assertThat(real().input.showing(alex.id, token)).isFalse();
        assertThat(real().closes).isEmpty();
        verify(view.getTopInventory()).clear();
    }

    @Test
    void theRecordsRefuseNullParts() {
        Route origin = new Route(Sample.KIND.id(), 2, null);
        Function<String, Answer> judge = this::judge;

        assertThatNullPointerException().isThrownBy(() -> new Answer.Accept(null)).withMessage("next");
        assertThatNullPointerException().isThrownBy(() -> new Answer.Retry(null)).withMessage("reason");
        assertThatNullPointerException().isThrownBy(() -> new Answer.Later(null)).withMessage("answer");
        assertThatNullPointerException()
            .isThrownBy(() -> new PromptRequest(null, "", origin, judge)).withMessage("question");
        assertThatNullPointerException()
            .isThrownBy(() -> new PromptRequest(Component.empty(), null, origin, judge)).withMessage("initial");
        assertThatNullPointerException()
            .isThrownBy(() -> new PromptRequest(Component.empty(), "", null, judge)).withMessage("origin");
        assertThatNullPointerException()
            .isThrownBy(() -> new PromptRequest(Component.empty(), "", origin, null)).withMessage("answer");
    }

    @Test
    void bothConstructorsRefuseEveryNullArgumentButTheAnvil() {
        harness = new Sample(folder).harness;
        Menus menus = harness.menus;
        BedrockPlayers none = BedrockPlayers.NONE;
        Predicate<Player> screen = player -> true;

        assertThatNullPointerException().isThrownBy(() -> new Prompts(null, PromptMode.CHAT, none, timer))
            .withMessage("menus");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, null, none, timer)).withMessage("mode");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, null, timer))
            .withMessage("bedrock");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, none, null))
            .withMessage("timer");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(null, PromptMode.CHAT, none, timer, null, link,
            screen, harness.clock)).withMessage("menus");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, null, none, timer, null, link, screen,
            harness.clock)).withMessage("mode");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, null, timer, null, link,
            screen, harness.clock)).withMessage("bedrock");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, none, null, null, link,
            screen, harness.clock)).withMessage("timer");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, none, timer, null, null,
            screen, harness.clock)).withMessage("cancelLink");
        assertThatNullPointerException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, none, timer, null, link,
            null, harness.clock)).withMessage("ownScreen");
        assertThat(menus.prompts()).isSameAs(harness.prompts);
        Prompts built = new Prompts(menus, PromptMode.ANVIL, none, timer, null, link, screen, harness.clock);
        assertThat(built.anvil()).isNull();
        assertThat(built.owns(mock(InventoryView.class))).isFalse();
    }

    @Test
    void thePublicConstructorBuildsAnAnvilOverTheMenusMarkerForTheAnvilMode() {
        Menus menus = new Sample(folder).harness.menus;

        Prompts anvil = new Prompts(menus, PromptMode.ANVIL, BedrockPlayers.NONE, timer);

        assertThat(anvil.anvil()).isNotNull();
        assertThat(anvil.anvil().marker()).isSameAs(menus.marker());
        assertThat(anvil.listener()).isInstanceOf(PaperPromptListener.class);
    }

    @Test
    void thePublicConstructorBuildsNoAnvilForTheChatMode() {
        Menus menus = new Sample(folder).harness.menus;

        Prompts chatOnly = new Prompts(menus, PromptMode.CHAT, BedrockPlayers.NONE, timer);

        assertThat(chatOnly.anvil()).isNull();
        assertThat(chatOnly.owns(mock(InventoryView.class))).isFalse();
    }

    @Test
    void theListenerIsTheSameOnEveryCall() {
        start(PromptMode.ANVIL);

        assertThat(prompts.listener()).isSameAs(prompts.listener()).isSameAs(chat).isInstanceOf(
            PaperPromptListener.class);
    }

    @Test
    void aSecondPromptsOverTheSameMenusIsRefusedAndTheFirstStaysLinked() {
        start(PromptMode.CHAT);
        MenuPrompts linked = harness.menus.prompts();

        assertThatIllegalStateException().isThrownBy(() -> new Prompts(harness.menus, PromptMode.CHAT,
            BedrockPlayers.NONE, timer)).withMessage("the menus already have prompts");

        assertThat(harness.menus.prompts()).isSameAs(linked);
        ask();
        harness.open(alex, Sample.KIND.id());
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void promptsOverMenusThatWereShutDownAreRefusedAndLeaveTheMenusAsTheyWere() {
        Sample sample = new Sample(folder);
        Menus menus = sample.harness.menus;
        MenuPrompts before = menus.prompts();
        menus.shutdown();

        assertThatIllegalStateException().isThrownBy(() -> new Prompts(menus, PromptMode.CHAT, BedrockPlayers.NONE,
            timer)).withMessage("the menus were shut down");

        assertThat(menus.prompts()).isSameAs(before);
    }

    @Test
    void theMenusShutdownEndsEveryAskOfTheirPrompts() {
        start(PromptMode.CHAT);
        ask();

        harness.menus.shutdown();
        timer.fire(Prompts.TIMEOUT);

        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("7").isCancelled()).isFalse();
        assertThat(alex.said()).noneMatch(line -> line.endsWith(TIMED_OUT));
        prompts.ask(alex.player, new PromptRequest(Component.text("Again?"), "", new Route(Sample.KIND.id(), 2, null),
            this::judge));
        assertThat(prompts.pending(alex.id)).isNull();
    }

    @Test
    void buildingThePromptsMakesThemThePromptsOfTheirMenus() {
        start(PromptMode.CHAT);

        assertThat(harness.menus.prompts()).isNotSameAs(harness.prompts);
        ask();
        harness.open(alex, Sample.KIND.id());
        assertThat(prompts.pending(alex.id)).isNull();
        assertThat(type("7").isCancelled()).isFalse();
        assertThat(Prompts.KEYS).contains(Menus.ERROR);
    }

    @Test
    void theListenerAndTheAnvilRefuseANullPart() {
        assertThatNullPointerException().isThrownBy(() -> new AnvilInput(null)).withMessage("marker");
        assertThatNullPointerException().isThrownBy(() -> new PaperPromptListener(null)).withMessage("prompts");
    }

    // The harness shows a player with no view open their own inventory, held by the player.
    private static boolean ownScreen(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder(false) == player;
    }

    // Records each delayed task and runs the ones of a delay when a test fires it.
    private static final class ManualTimer implements BiConsumer<Runnable, Duration> {

        private record Waiting(Duration after, Runnable task) {
        }

        private final List<Waiting> waiting = new ArrayList<>();

        @Override
        public void accept(Runnable task, Duration after) {
            waiting.add(new Waiting(after, task));
        }

        List<Duration> waits() {
            return waiting.stream().map(Waiting::after).toList();
        }

        void fire(Duration after) {
            List<Waiting> due = waiting.stream().filter(entry -> entry.after().equals(after)).toList();
            waiting.removeAll(due);
            due.forEach(entry -> entry.task().run());
        }
    }

    // Records each link and runs its action when a test clicks it.
    private static final class FakeLink implements CancelLink {

        final List<ClickEvent<?>> events = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        @Override
        public ClickEvent<?> link(Runnable action) {
            ClickEvent<?> event = ClickEvent.runCommand("/prompt-test " + actions.size());
            events.add(event);
            actions.add(action);
            return event;
        }

        void click(int index) {
            actions.get(index).run();
        }
    }

    // A real AnvilInput and PaperPromptListener over mocked anvil views, recording each anvil opened as its question and
    // starting text. Once installed, the viewer's player opens and closes the anvil as the server does: opening it
    // closes the open anvil or menu view with OPEN_NEW, through the prompt or menu listener, and shows it unless refuse
    // is true; closing it posts the close to the prompt listener.
    private final class RealAnvil {

        final AnvilInput input = new AnvilInput(harness.marker, this::view, this::paper);
        final List<InventoryCloseEvent.Reason> closes = new ArrayList<>();
        final List<String> opens = new ArrayList<>();
        @Nullable String typed;
        @Nullable AnvilView shown;
        boolean refuse;
        private @Nullable Component question;
        private PaperPromptListener listener;

        private AnvilView view(Player player, Component asked) {
            question = asked;
            AnvilView view = mock(AnvilView.class);
            AnvilInventory top = mock(AnvilInventory.class);
            when(view.getTopInventory()).thenReturn(top);
            when(view.getPlayer()).thenReturn(player);
            when(view.getRenameText()).thenAnswer(call -> typed);
            return view;
        }

        private ItemStack paper(String initial) {
            opens.add(MenuHarness.plain(question) + " " + initial);
            return mock(ItemStack.class);
        }

        // The anvil left the screen without its close reaching the prompt listener, as when the listener missed it.
        void replaced() {
            input.forget(alex.id);
            shown = null;
        }

        void install(MenuHarness.Viewer viewer) {
            listener = (PaperPromptListener) prompts.listener();
            Inventory crafting = mock(Inventory.class);
            when(crafting.getHolder(false)).thenReturn(viewer.player);
            FakeView own = new FakeView(crafting, viewer.inventory, viewer.player);
            doAnswer(call -> {
                if (shown != null) {
                    close(InventoryCloseEvent.Reason.OPEN_NEW);
                } else {
                    closeMenu(viewer, InventoryCloseEvent.Reason.OPEN_NEW);
                }
                if (!refuse) {
                    shown = call.getArgument(0);
                }
                return null;
            }).when(viewer.player).openInventory(any(InventoryView.class));
            when(viewer.player.getOpenInventory())
                .thenAnswer(call -> shown != null ? shown : viewer.view != null ? viewer.view : own);
            doAnswer(call -> {
                InventoryCloseEvent.Reason reason = call.getArgument(0);
                if (shown != null) {
                    close(reason);
                } else {
                    closeMenu(viewer, reason);
                }
                return null;
            }).when(viewer.player).closeInventory(any(InventoryCloseEvent.Reason.class));
        }

        private void closeMenu(MenuHarness.Viewer viewer, InventoryCloseEvent.Reason reason) {
            FakeView menu = viewer.view;
            if (menu == null) {
                return;
            }
            viewer.view = null;
            viewer.closes.add(reason);
            harness.listener.onClose(new InventoryCloseEvent(menu, reason));
        }

        void close(InventoryCloseEvent.Reason reason) {
            AnvilView closing = shown;
            shown = null;
            closes.add(reason);
            listener.onAnvilClose(new InventoryCloseEvent(closing, reason));
        }

        InventoryClickEvent click(int rawSlot) {
            InventoryClickEvent event = new InventoryClickEvent(shown, InventoryType.SlotType.RESULT, rawSlot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onAnvilClick(event);
            listener.recancelAnvilClick(event);
            return event;
        }
    }
}
