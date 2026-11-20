package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.EntityUnavailableException;
import so.alaz.taffy.platform.ErrorReports;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Asks a player for one line of text in place of a menu, and opens a menu with the answer.
 *
 * <p>A player types the answer into chat, or, for {@link PromptMode#ANVIL}, into the name field of an anvil the
 * prompts build. Bedrock players always type into chat. Each ask has a token; the chat line, the anvil's submit, the
 * cancel link and the timer each take the pending ask by its token in one atomic step, so only one of them acts on
 * it. Typing the cancel word cancels. An answer is
 * stripped of surrounding whitespace and cut to {@link #MAX_LENGTH} characters. A refused answer keeps the ask waiting
 * until its first deadline, {@link #TIMEOUT} after it was asked. Submit, cancel and timeout open a menu again unless
 * the player is dead or offline, has another plugin's inventory open, or the plugin is stopping; an ask ended by
 * another menu, another plugin's inventory, a newer ask or a quit opens nothing.
 *
 * <p>The plugin's lang files hold the text under these keys: {@link #QUESTION} (the question, with the
 * {@code <question>} value), {@link #HINT} (the chat hint, with the cancel word as {@code <word>}),
 * {@link #CANCEL_BUTTON} (the clickable cancel link put after the hint), {@link #CANCEL_WORD} (the word that cancels,
 * {@link #DEFAULT_CANCEL_WORD} when the key is absent or blank; the English word always works too),
 * {@link #CANCELLED}, {@link #TIMED_OUT}, {@link #REFUSED} (with the refusal as {@code <reason>} and the typed text as
 * {@code <answer>}) and {@link Menus#ERROR}; {@link #KEYS} lists them. No prefix is put in front of these messages: a message carries the
 * plugin's prefix only where its text writes {@code <prefix>}.
 *
 * <p>An answer function that throws a {@link RuntimeException} or returns {@code null} is logged, reported to
 * {@link ErrorReports} under {@code gui.prompt}, and the player is told {@link Menus#ERROR} and taken back to the
 * origin. A failure while running work on the player's entity thread is logged and reported under {@code gui.prompt}
 * as well, except that the player having left is not a failure.
 *
 * <p>An answer function returns an {@link Answer.Later} to decide off the player's entity thread. The ask then waits
 * for the future, which completes on any thread, and its result is applied on the player's entity thread. Until then
 * no chat line and no anvil submit is taken; the timer, the cancel link, a closed anvil, another menu, a newer ask, a
 * quit and {@link Menus#shutdown()} still end the ask, and the result of a future that completes after that is
 * dropped. A future that fails or completes with {@code null} is handled as an answer function that throws, and a
 * cancelled future opens the origin again with no message.
 *
 * <p>The prompts read the text source, the locale function, the entity-thread seams, the thread-ownership check, the
 * logger and the {@link ErrorReports} of the menus they are built over. Building the prompts makes them the prompts
 * of those menus, so opening a menu ends a pending ask; a second {@code Prompts} over the same menus, or prompts over
 * menus that were shut down, are refused. Building the prompts also registers their listener, which reads the chat
 * answers and guards the prompt anvil, as a listener of the menus' plugin, so the plugin registers nothing.
 *
 * <p>At disable, {@link Menus#shutdown()} ends every ask, in the order the {@code so.alaz.taffy.platform} package
 * states.
 */
public final class Prompts {

    /** How long an ask waits for its answer. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** The most characters of an answer that are kept. */
    public static final int MAX_LENGTH = 256;
    /** The cancel word, used when the lang files give none, and accepted in every locale. */
    public static final String DEFAULT_CANCEL_WORD = "cancel";
    /** The raw slot of an anvil's result, whose click submits the name field. */
    static final int ANVIL_RESULT_SLOT = 2;
    /** The lang key of the question message. */
    public static final String QUESTION = "gui.prompt.question";
    /** The lang key of the chat hint. */
    public static final String HINT = "gui.prompt.hint";
    /** The lang key of the cancel link's text. */
    public static final String CANCEL_BUTTON = "gui.prompt.cancel-button";
    /** The lang key of the word that cancels a chat ask. */
    public static final String CANCEL_WORD = "gui.prompt.cancel-word";
    /** The lang key of the message sent when an ask is cancelled. */
    public static final String CANCELLED = "gui.prompt.cancelled";
    /** The lang key of the message sent when an ask times out. */
    public static final String TIMED_OUT = "gui.prompt.timed-out";
    /** The lang key of the message sent when an answer is refused. */
    public static final String REFUSED = "gui.prompt.refused";
    /** Every lang key prompts read, {@link Menus#ERROR} included. */
    public static final List<String> KEYS = List.of(QUESTION, HINT, CANCEL_BUTTON, CANCEL_WORD, CANCELLED, TIMED_OUT,
        REFUSED, Menus.ERROR);

    private final Menus menus;
    private final PromptMode mode;
    private final BedrockPlayers bedrock;
    private final BiConsumer<Runnable, Duration> timer;
    private final @Nullable AnvilInput anvil;
    private final CancelLink cancelLink;
    private final Predicate<Player> ownScreen;
    private final LongSupplier nanos;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> judging = new ConcurrentHashMap<>();
    private final Set<UUID> legacyLines = ConcurrentHashMap.newKeySet();
    private final AtomicLong tokens = new AtomicLong();
    private volatile boolean stopping;
    private final PaperPromptListener listener;

    /**
     * Creates the prompts with no ask pending, registers their listener as a listener of the menus' plugin and makes
     * them the prompts that opening one of {@code menus} ends. For {@link PromptMode#ANVIL} the prompts build their
     * anvil, whose paper carries the menus' preview key.
     *
     * @param menus the plugin's menus, whose text source, locale function, entity-thread seams, thread-ownership
     *     check, logger and error reports the prompts use
     * @param mode where players type their answers. It holds for the life of the prompts, so a changed mode takes
     *     effect only in prompts built anew
     * @param bedrock which players joined from Bedrock Edition, or {@link BedrockPlayers#NONE}
     * @param timer runs a task on any thread after a delay, such as {@code scheduler::delayedAsync}; a task it refuses
     *     at disable is not needed, since {@link Menus#shutdown()} ends every ask
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if prompts were already built over {@code menus}, or {@link Menus#shutdown()} ran
     */
    public Prompts(Menus menus, PromptMode mode, BedrockPlayers bedrock, BiConsumer<Runnable, Duration> timer) {
        this(menus, mode, bedrock, timer, anvilFor(menus, mode), new PaperCancelLink(), Prompts::ownScreen,
            System::nanoTime);
    }

    Prompts(Menus menus, PromptMode mode, BedrockPlayers bedrock, BiConsumer<Runnable, Duration> timer,
            @Nullable AnvilInput anvil, CancelLink cancelLink, Predicate<Player> ownScreen, LongSupplier nanos) {
        Objects.requireNonNull(menus, "menus");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(bedrock, "bedrock");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(cancelLink, "cancelLink");
        Objects.requireNonNull(ownScreen, "ownScreen");
        if (menus.stopping()) {
            throw new IllegalStateException("the menus were shut down");
        }
        if (menus.prompts() instanceof Link) {
            throw new IllegalStateException("the menus already have prompts");
        }
        this.menus = menus;
        this.mode = mode;
        this.bedrock = bedrock;
        this.timer = timer;
        this.anvil = anvil;
        this.cancelLink = cancelLink;
        this.ownScreen = ownScreen;
        this.nanos = nanos;
        this.listener = new PaperPromptListener(this);
        menus.register(listener);
        menus.prompts(new Link());
    }

    private static @Nullable AnvilInput anvilFor(Menus menus, PromptMode mode) {
        Objects.requireNonNull(menus, "menus");
        return Objects.requireNonNull(mode, "mode") == PromptMode.ANVIL ? new AnvilInput(menus.marker()) : null;
    }

    // Whether the player sees their own crafting or creative screen, and no other inventory. InventoryType loads only
    // on a running server.
    static boolean ownScreen(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    /**
     * Gives a listener with no event handler, so that registering it does nothing.
     *
     * @return the same listener on every call
     * @deprecated the prompts register their own listener when they are built; remove the {@code registerEvents}
     *     call that took this listener
     */
    @Deprecated
    public Listener listener() {
        return Menus.INERT;
    }

    PaperPromptListener promptListener() {
        return listener;
    }

    /**
     * Asks the player in place of the menu they have open. Replaces any ask still pending for the player without a
     * message. Nothing happens once {@link Menus#shutdown()} ran, or when the server refused to open the anvil. Call on
     * the player's entity thread, outside an inventory event; a {@link ClickHandler} calls
     * {@link #askLater(Player, PromptRequest)} instead.
     *
     * @param player the player to ask
     * @param request the question
     * @throws NullPointerException if {@code request} is {@code null}; nothing is closed or replaced then
     */
    public void ask(Player player, PromptRequest request) {
        Objects.requireNonNull(request, "request");
        if (stopping) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean onBedrock = bedrock.bedrock(id);
        PromptMode shown = !onBedrock && mode == PromptMode.ANVIL && anvil != null ? PromptMode.ANVIL
            : PromptMode.CHAT;
        end(id);
        Pending entry = new Pending(tokens.incrementAndGet(), shown, request, nanos.getAsLong() + TIMEOUT.toNanos(),
            player);
        pending.put(id, entry);
        if (!show(player, entry)) {
            pending.remove(id, entry);
            return;
        }
        timer.accept(() -> expire(id, entry.token()), TIMEOUT);
        if (shown == PromptMode.ANVIL) {
            return;
        }
        Locale locale = menus.locales().apply(player);
        player.sendMessage(menus.source().render(locale, QUESTION,
            Placeholder.component("question", request.question())));
        Component hint = menus.source().render(locale, HINT, Placeholder.unparsed("word", cancelWord(locale)));
        if (!onBedrock) {
            hint = hint.append(Component.space()).append(menus.source().render(locale, CANCEL_BUTTON)
                .clickEvent(cancelLink.link(() -> cancel(id, entry.token()))));
        }
        player.sendMessage(hint);
    }

    /**
     * Asks the player as {@link #ask(Player, PromptRequest)} does, on the next tick on the player's entity thread. This
     * is how a {@link ClickHandler} asks from inside a click. Nothing happens when {@link Menus#shutdown()} ran by
     * then.
     *
     * @param player the player to ask
     * @param request the question
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public void askLater(Player player, PromptRequest request) {
        Objects.requireNonNull(request, "request");
        menus.tasks().accept(player, () -> ask(player, request));
    }

    @Nullable Pending pending(UUID player) {
        return pending.get(player);
    }

    @Nullable AnvilInput anvil() {
        return anvil;
    }

    /** The click on an anvil prompt's slot; the result slot submits the name field's text. */
    void anvilClick(UUID player, long token, int rawSlot, @Nullable String renameText) {
        if (rawSlot != ANVIL_RESULT_SLOT) {
            return;
        }
        Pending taken = take(player, token);
        if (taken != null) {
            answer(player, taken, renameText == null ? "" : renameText);
        }
    }

    /**
     * A prompt's anvil closed while its ask may still be pending. Another inventory opening over it ends the ask
     * with no message and opens nothing; any other close cancels it.
     */
    void surfaceClosed(UUID player, long token, InventoryCloseEvent.Reason reason) {
        if (reason == InventoryCloseEvent.Reason.OPEN_NEW) {
            takeWaiting(player, token);
            return;
        }
        cancel(player, token);
    }

    /** Any thread: ends the ask with the cancelled message and opens its menu again. */
    void cancel(UUID player, long token) {
        Pending taken = takeWaiting(player, token);
        if (taken != null) {
            hop(player, live -> finish(live, taken, CANCELLED, taken.request().origin()));
        }
    }

    /** Any thread: ends the ask with the timed-out message and opens its menu again. */
    void expire(UUID player, long token) {
        Pending taken = takeWaiting(player, token);
        if (taken != null) {
            hop(player, live -> finish(live, taken, TIMED_OUT, taken.request().origin()));
        }
    }

    /**
     * Ends the player's ask, pending or waiting for its {@link Answer.Later}, without a message and opens nothing.
     * Call on the player's entity thread.
     */
    void end(UUID player) {
        Pending entry = pending.remove(player);
        if (entry != null) {
            closeSurface(entry.player(), entry);
        }
        Pending judged = judging.remove(player);
        if (judged != null) {
            closeSurface(judged.player(), judged);
        }
    }

    /** Forgets the ask of a player who quit, and what the anvil keeps for them. */
    void quit(UUID player) {
        pending.remove(player);
        judging.remove(player);
        legacyLines.remove(player);
        if (anvil != null) {
            anvil.forget(player);
        }
    }

    /** Tells whether the anvil of these prompts opened the view and still shows it. */
    boolean owns(InventoryView view) {
        return anvil != null && anvil.owns(view);
    }

    /**
     * Ends every ask without a message and opens nothing, then or later. An anvil still showing, including one whose
     * answer is still being judged, is taken away from each online player the calling thread owns. The anvil of a
     * player the calling thread does not own is emptied from the calling thread, forgotten and stays open, and once
     * the plugin is disabled no listener cancels its clicks.
     */
    void shutdown() {
        stopping = true;
        pending.clear();
        judging.clear();
        if (anvil != null) {
            anvil.shutdown(player -> player.isOnline() && menus.ownedHere().test(player));
        }
    }

    /** Takes the player's ask when it waits for a chat line. */
    @Nullable Pending takeChat(UUID player) {
        Pending[] taken = new Pending[1];
        pending.computeIfPresent(player, (id, entry) -> {
            if (entry.mode() != PromptMode.CHAT) {
                return entry;
            }
            taken[0] = entry;
            return null;
        });
        return taken[0];
    }

    /**
     * Takes the player's ask for a line the legacy chat event carries, and remembers the line as taken until
     * {@link #takenByLegacy(UUID)} asks for it or the player's next legacy line arrives.
     */
    @Nullable Pending takeLegacyChat(UUID player) {
        legacyLines.remove(player);
        Pending taken = takeChat(player);
        if (taken != null) {
            legacyLines.add(player);
        }
        return taken;
    }

    /** Tells, once, whether the legacy chat event took the line the player's modern chat event now carries. */
    boolean takenByLegacy(UUID player) {
        return legacyLines.remove(player);
    }

    /** Judges text for an ask the caller took, on the player's entity thread. */
    void answer(UUID player, Pending taken, String text) {
        String answer = clean(text);
        hop(player, live -> settle(live, taken, answer));
    }

    /** The text without surrounding whitespace, cut to {@link #MAX_LENGTH} characters. */
    static String clean(String text) {
        String stripped = text.strip();
        if (stripped.length() <= MAX_LENGTH) {
            return stripped;
        }
        int end = Character.isHighSurrogate(stripped.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return stripped.substring(0, end);
    }

    private @Nullable Pending take(UUID player, long token) {
        Pending[] taken = new Pending[1];
        pending.computeIfPresent(player, (id, entry) -> {
            if (entry.token() != token) {
                return entry;
            }
            taken[0] = entry;
            return null;
        });
        return taken[0];
    }

    /** Takes the ask with the token, whether it waits for an answer or for its {@link Answer.Later}. */
    private @Nullable Pending takeWaiting(UUID player, long token) {
        Pending taken = take(player, token);
        if (taken != null) {
            return taken;
        }
        Pending judged = judging.get(player);
        return judged != null && judged.token() == token && judging.remove(player, judged) ? judged : null;
    }

    private boolean show(Player player, Pending entry) {
        PromptRequest request = entry.request();
        return switch (entry.mode()) {
            case CHAT -> menus.handoff(player, () -> {
                player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
                return true;
            });
            case ANVIL -> menus.handoff(player, () -> surface(entry).open(player, request.question(),
                request.initial(), entry.token()));
        };
    }

    private void settle(Player player, Pending taken, String text) {
        Locale locale = menus.locales().apply(player);
        if (text.equalsIgnoreCase(cancelWord(locale)) || text.equalsIgnoreCase(DEFAULT_CANCEL_WORD)) {
            finish(player, taken, CANCELLED, taken.request().origin());
            return;
        }
        Answer result;
        try {
            result = Objects.requireNonNull(taken.request().answer().apply(text), "the answer function returned null");
        } catch (RuntimeException failure) {
            failed(player, taken, failure);
            return;
        }
        decide(player, taken, text, result);
    }

    private void decide(Player player, Pending taken, String text, Answer result) {
        switch (result) {
            case Answer.Accept accept -> finish(player, taken, null, accept.next());
            case Answer.Retry retry -> {
                player.sendMessage(menus.source().render(menus.locales().apply(player), REFUSED,
                    Placeholder.component("reason", retry.reason()), Placeholder.unparsed("answer", text)));
                rearm(player, taken);
            }
            case Answer.Later later -> await(player.getUniqueId(), taken, text, later.answer());
        }
    }

    /**
     * Keeps the ask until {@code answer} completes, unless the plugin is stopping, a menu opened or a newer ask took
     * its place, then applies the result on the player's entity thread while the ask is still the one kept.
     */
    private void await(UUID id, Pending taken, String text, CompletableFuture<Answer> answer) {
        if (stopping || menus.session(id).isPresent() || pending.containsKey(id)
            || judging.putIfAbsent(id, taken) != null) {
            return;
        }
        answer.whenComplete((result, failure) -> hop(id, live -> {
            if (!judging.remove(id, taken)) {
                return;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
            if (cause instanceof CancellationException) {
                finish(live, taken, null, taken.request().origin());
            } else if (cause != null) {
                failed(live, taken, cause);
            } else if (result == null) {
                failed(live, taken, new NullPointerException("the answer future completed with null"));
            } else {
                decide(live, taken, text, result);
            }
        }));
    }

    private void failed(Player player, Pending taken, Throwable failure) {
        menus.logger().log(Level.WARNING, "gui: a prompt answer failed for " + player.getName() + ": " + failure,
            failure);
        menus.errors().report("gui.prompt", failure);
        player.sendMessage(menus.source().render(menus.locales().apply(player), Menus.ERROR));
        finish(player, taken, null, taken.request().origin());
    }

    /**
     * Puts a refused ask back under its token with its first deadline, unless a newer ask or a menu took its place. An
     * anvil no longer showing comes back on the next tick; with another inventory open then, the ask ends.
     */
    private void rearm(Player player, Pending entry) {
        UUID id = player.getUniqueId();
        if (stopping || menus.session(id).isPresent() || judging.containsKey(id)
            || pending.putIfAbsent(id, entry) != null) {
            return;
        }
        Duration left = Duration.ofNanos(entry.deadline() - nanos.getAsLong());
        if (!left.isPositive()) {
            expire(id, entry.token());
            return;
        }
        timer.accept(() -> expire(id, entry.token()), left);
        if (entry.mode() != PromptMode.CHAT && !surface(entry).showing(id, entry.token())) {
            menus.tasks().accept(player, () -> {
                if (pending.get(id) == entry && !surface(entry).showing(id, entry.token())
                    && (!ownScreen.test(player) || !show(player, entry))) {
                    pending.remove(id, entry);
                }
            });
        }
    }

    private void finish(Player player, Pending entry, @Nullable String key, Route next) {
        closeSurface(player, entry);
        if (key != null) {
            player.sendMessage(menus.source().render(menus.locales().apply(player), key));
        }
        if (stopping || player.isDead() || !player.isOnline() || !ownScreen.test(player)) {
            return;
        }
        menus.open(player, next);
    }

    private void closeSurface(Player player, Pending entry) {
        if (entry.mode() != PromptMode.CHAT) {
            surface(entry).close(player, entry.token());
        }
    }

    private AnvilInput surface(Pending entry) {
        return Objects.requireNonNull(anvil, entry.mode().name());
    }

    private String cancelWord(Locale locale) {
        if (!menus.source().has(locale, CANCEL_WORD)) {
            return DEFAULT_CANCEL_WORD;
        }
        String word = PlainTextComponentSerializer.plainText().serialize(menus.source().render(locale, CANCEL_WORD))
            .strip();
        return word.isEmpty() ? DEFAULT_CANCEL_WORD : word;
    }

    private void hop(UUID player, Consumer<Player> task) {
        menus.entity().call(player, live -> {
            task.accept(live);
            return null;
        }).whenComplete((done, failure) -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
            if (cause != null && !(cause instanceof EntityUnavailableException)) {
                menus.logger().log(Level.WARNING, "gui: a prompt for " + player + " failed: " + cause, cause);
                menus.errors().report("gui.prompt", cause);
            }
        });
    }

    /** The link that makes these prompts the prompts of their menus. */
    private final class Link implements MenuPrompts {

        @Override
        public void end(UUID player) {
            Prompts.this.end(player);
        }

        @Override
        public void quit(UUID player) {
            Prompts.this.quit(player);
        }

        @Override
        public boolean owns(InventoryView view) {
            return Prompts.this.owns(view);
        }

        @Override
        public void shutdown() {
            Prompts.this.shutdown();
        }
    }
}
