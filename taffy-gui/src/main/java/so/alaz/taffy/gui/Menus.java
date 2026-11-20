package so.alaz.taffy.gui;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.EntityThread;
import so.alaz.taffy.platform.EntityUnavailableException;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.Messages;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The menu registry and the sessions of open menus.
 *
 * <p>{@link #open(Player, String, Route)}, {@link #open(Player, Route)}, {@link #rerender(MenuSession)} and
 * {@link #load(MenuSession, CompletableFuture, BiConsumer)} run on the viewer's entity thread. On a thread that does
 * not own the viewer, such as the thread that completed a storage future, each throws an
 * {@link IllegalStateException} that names what to call instead, before it changes anything. The {@code *Later}
 * methods are called from any thread and schedule that work for the next tick on the viewer's entity thread through
 * {@link Parts#tasks()}, which is how a {@link ClickHandler} opens, closes and redraws menus. The session map serves
 * {@link #session(UUID)}, a quit and {@link #shutdown()}; listeners find a menu by its inventory holder, the
 * {@link MenuSession}.
 *
 * <p>A plugin may build more than one {@code Menus}. Each listener cancels the clicks and drags in a menu of any of
 * them, and only the menus that opened a session run its button handlers, play its sounds and end it.
 *
 * <p>Opening a menu while the viewer has another open is a hand-off: the previous session's close is not the viewer
 * leaving, so its {@link Menu#closed(MenuSession, boolean)} gets {@code left = false}. A prompt that takes over
 * through {@link Prompts#ask(Player, PromptRequest)} is a hand-off too. A session never opens again once its
 * {@code closed} ran: the menu a hand-off or a prompt opens is a new session, and a prompt whose ask ends with no
 * menu opened, because the player quit, died or has another inventory open, tells
 * {@link PromptRequest#abandoned()} and no menu. Once {@link #shutdown()} ran the menus are stopping and nothing
 * opens again.
 *
 * <p>The menus register their own listener as a listener of {@link Parts#plugin()} when they are built, so a menu
 * never opens without it, and the plugin registers nothing. At disable the plugin calls {@link #shutdown()}, which
 * ends the prompts first, before {@code Scheduler.close}, in the order the {@code so.alaz.taffy.platform} package
 * states, so no click starts work while the plugin stops.
 *
 * <p>A load that fails, a menu that fails to draw in a task {@code openLater} or {@link #rerenderLater(MenuSession)}
 * scheduled, or a {@link Menu#closed(MenuSession, boolean)} that throws is logged with its stack through the plugin's
 * logger and reported to its {@link ErrorReports} under {@code gui.load}, {@code gui.render} or {@code gui.close}. A
 * failed load also tells the viewer the lang key {@link #ERROR} and plays {@link Cue#FAIL} while its session is open.
 * {@link #keys(Collection)} lists the lang keys the menus read.
 */
public final class Menus {

    /** The lang key of the message a viewer is told when a menu's load fails. */
    public static final String ERROR = "gui.error";

    /** A listener with no event handler. */
    static final Listener INERT = new Listener() {
    };

    /**
     * What the menus are built from. Build the parts with
     * {@link #of(Plugin, TextSource, BiConsumer, EntityThread, ErrorReports)} and the methods that take a value, such
     * as {@code Parts.of(...).layouts(layouts).home("main")}; each gives a copy and leaves these parts unchanged.
     */
    public static final class Parts {

        private final Plugin plugin;
        private final Map<String, MenuLayout> layouts;
        private final MenuSettings settings;
        private final TextSource text;
        private final Function<Player, Locale> locales;
        private final Server server;
        private final NamespacedKey preview;
        private final BiConsumer<Player, Runnable> tasks;
        private final EntityThread entity;
        private final Supplier<Map<Cue, Sound>> sounds;
        private final @Nullable String home;
        private final Logger logger;
        private final ErrorReports errors;

        private Parts(Plugin plugin, Map<String, MenuLayout> layouts, MenuSettings settings, TextSource text,
                      Function<Player, Locale> locales, Server server, NamespacedKey preview,
                      BiConsumer<Player, Runnable> tasks, EntityThread entity, Supplier<Map<Cue, Sound>> sounds,
                      @Nullable String home, Logger logger, ErrorReports errors) {
            this.plugin = plugin;
            this.layouts = layouts;
            this.settings = Objects.requireNonNull(settings, "settings");
            this.text = Objects.requireNonNull(text, "text");
            this.locales = Objects.requireNonNull(locales, "locales");
            this.server = Objects.requireNonNull(server, "server");
            this.preview = Objects.requireNonNull(preview, "preview");
            this.tasks = Objects.requireNonNull(tasks, "tasks");
            this.entity = Objects.requireNonNull(entity, "entity");
            this.sounds = Objects.requireNonNull(sounds, "sounds");
            this.home = home;
            this.logger = Objects.requireNonNull(logger, "logger");
            this.errors = Objects.requireNonNull(errors, "errors");
        }

        /**
         * Gives the parts of menus with no layouts, {@link MenuSettings#DEFAULTS}, each player's own client locale
         * ({@code Player::locale}), no sounds and no home menu, on the plugin's server, with the plugin's logger and
         * the preview key {@code new NamespacedKey(plugin, "preview")}.
         *
         * @param plugin the plugin that owns the menus: the menus and their prompts register their listeners
         *     as its listeners
         * @param text gives the text of each lang key the menus and prompts read
         * @param tasks runs work on a player's entity thread on the next tick, and nothing once the player is gone
         *     or the plugin is disabling, such as {@code scheduler::entity}
         * @param entity runs a load's result, and a prompt's answer, cancel and timeout, on the player's entity
         *     thread
         * @param errors where handled failures are reported
         * @return the parts
         * @throws NullPointerException if an argument is {@code null}
         */
        public static Parts of(Plugin plugin, TextSource text, BiConsumer<Player, Runnable> tasks,
                               EntityThread entity, ErrorReports errors) {
            Objects.requireNonNull(plugin, "plugin");
            return new Parts(plugin, Map.of(), MenuSettings.DEFAULTS, text, Player::locale, plugin.getServer(),
                new NamespacedKey(plugin, "preview"), tasks, entity, Map::of, null, plugin.getLogger(), errors);
        }

        /**
         * Gives the parts as {@link #of(Plugin, TextSource, BiConsumer, EntityThread, ErrorReports)} does, with the
         * text read from Taffy's lang files through {@link TextSource#of(Messages)}.
         *
         * @param plugin the plugin that owns the menus
         * @param messages the plugin's lang files
         * @param tasks runs work on a player's entity thread on the next tick
         * @param entity runs a load's result, and a prompt's answer, cancel and timeout, on the player's entity
         *     thread
         * @param errors where handled failures are reported
         * @return the parts
         * @throws NullPointerException if an argument is {@code null}
         */
        public static Parts of(Plugin plugin, Messages messages, BiConsumer<Player, Runnable> tasks,
                               EntityThread entity, ErrorReports errors) {
            return of(plugin, TextSource.of(messages), tasks, entity, errors);
        }

        /**
         * Gives the plugin that owns the menus, whose listeners the menus and their prompts register.
         *
         * @return the plugin
         */
        public Plugin plugin() {
            return plugin;
        }

        /**
         * Gives the layout of each menu, keyed by menu id.
         *
         * @return the layouts; unmodifiable
         */
        public Map<String, MenuLayout> layouts() {
            return layouts;
        }

        /**
         * Gives the limits and items every menu shares.
         *
         * @return the settings
         */
        public MenuSettings settings() {
            return settings;
        }

        /**
         * Gives what gives the text of each lang key the menus and prompts read.
         *
         * @return the text source
         */
        public TextSource text() {
            return text;
        }

        /**
         * Gives the locale a player's menu and prompt text renders in.
         *
         * @return the locale function
         */
        public Function<Player, Locale> locales() {
            return locales;
        }

        /**
         * Gives the server, which creates each menu inventory, tells whether the calling thread owns a player and
         * registers the listeners.
         *
         * @return the server
         */
        public Server server() {
            return server;
        }

        /**
         * Gives the key every stack a menu or prompt anvil shows carries.
         *
         * @return the preview key
         */
        public NamespacedKey preview() {
            return preview;
        }

        /**
         * Gives what runs work on a player's entity thread on the next tick.
         *
         * @return the next-tick tasks
         */
        public BiConsumer<Player, Runnable> tasks() {
            return tasks;
        }

        /**
         * Gives what runs a load's result, and a prompt's answer, cancel and timeout, on the player's entity thread.
         *
         * @return the entity thread
         */
        public EntityThread entity() {
            return entity;
        }

        /**
         * Gives the sound of each {@link Cue}, read each time one plays.
         *
         * @return the supplier of the sounds
         */
        public Supplier<Map<Cue, Sound>> sounds() {
            return sounds;
        }

        /**
         * Gives the id of the menu the back button opens when a menu has no back route.
         *
         * @return the home menu, or {@code null} for none
         */
        public @Nullable String home() {
            return home;
        }

        /**
         * Gives the logger handled failures are logged through.
         *
         * @return the logger
         */
        public Logger logger() {
            return logger;
        }

        /**
         * Gives where handled failures are reported.
         *
         * @return the error reports
         */
        public ErrorReports errors() {
            return errors;
        }

        /**
         * Gives these parts with other layouts.
         *
         * @param layouts the layout of each menu, keyed by menu id; each key equals its layout's
         *     {@link MenuLayout#menu()}
         * @return a copy of these parts with {@code layouts} replaced by a copy of the map
         * @throws NullPointerException if {@code layouts}, a key or a layout is {@code null}
         * @throws IllegalArgumentException if a key differs from its layout's {@link MenuLayout#menu()}
         */
        public Parts layouts(Map<String, MenuLayout> layouts) {
            return new Parts(plugin, checked(layouts), settings, text, locales, server, preview, tasks, entity, sounds,
                home, logger, errors);
        }

        /**
         * Gives these parts with other settings.
         *
         * @param settings the limits and items every menu shares
         * @return a copy of these parts with {@code settings} replaced
         * @throws NullPointerException if {@code settings} is {@code null}
         */
        public Parts settings(MenuSettings settings) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts with another locale function.
         *
         * @param locales the locale a player's menu and prompt text renders in
         * @return a copy of these parts with {@code locales} replaced
         * @throws NullPointerException if {@code locales} is {@code null}
         */
        public Parts locales(Function<Player, Locale> locales) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts with other sounds.
         *
         * @param sounds the sound of each {@link Cue}, read each time one plays; a cue it does not map plays nothing
         * @return a copy of these parts with {@code sounds} replaced
         * @throws NullPointerException if {@code sounds} is {@code null}
         */
        public Parts sounds(Supplier<Map<Cue, Sound>> sounds) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts on another server.
         *
         * @param server the server, which creates each menu inventory, tells whether the calling thread owns a
         *     player and registers the listeners
         * @return a copy of these parts with {@code server} replaced
         * @throws NullPointerException if {@code server} is {@code null}
         */
        public Parts server(Server server) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts with another preview key.
         *
         * @param preview the key every stack a menu or prompt anvil shows carries; a plugin that must recognise a
         *     menu copy in its own checks keeps it
         * @return a copy of these parts with {@code preview} replaced
         * @throws NullPointerException if {@code preview} is {@code null}
         */
        public Parts preview(NamespacedKey preview) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts with another logger.
         *
         * @param logger the logger handled failures are logged through
         * @return a copy of these parts with {@code logger} replaced
         * @throws NullPointerException if {@code logger} is {@code null}
         */
        public Parts logger(Logger logger) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }

        /**
         * Gives these parts with another home menu.
         *
         * @param home the id of the menu the back button opens when a menu has no back route, or {@code null} for
         *     none
         * @return a copy of these parts with {@code home} replaced
         */
        public Parts home(@Nullable String home) {
            return new Parts(plugin, layouts, settings, text, locales, server, preview, tasks, entity, sounds, home,
                logger, errors);
        }
    }

    private final Parts parts;
    private final LongSupplier nanos;
    private final PreviewMarker marker;
    private final Inventories inventories;
    private final Predicate<Player> ownedHere;
    private final Predicate<Material> item;
    private final Feedback feedback;
    private final MenuText text;
    private final Debounce debounce;
    private final ViewMemory memory = new ViewMemory();
    private final Map<String, Menu<?>> menus = new ConcurrentHashMap<>();
    private final Map<UUID, MenuSession<?>> sessions = new ConcurrentHashMap<>();
    private volatile Map<String, MenuLayout> layouts;
    private volatile MenuPrompts prompts = MenuPrompts.NONE;
    private volatile boolean stopping;
    private final MenuListener listener;
    private final Consumer<Listener> registrar;

    /**
     * Creates the menus with no menu registered and registers their listener with the plugin manager of
     * {@link Parts#server()} as a listener of {@link Parts#plugin()}. The listener keeps menu views safe, dispatches
     * button clicks, ends the session of a closed menu and forgets what the menus and prompts keep for a player who
     * quit. Each menu inventory comes from the server, and every stack drawn into it carries
     * {@link Parts#preview()}. Call while the plugin is enabled, such as in {@code onEnable}.
     *
     * @param parts what the menus are built from
     * @throws NullPointerException if {@code parts} is {@code null}
     * @throws org.bukkit.plugin.IllegalPluginAccessException if the plugin is not enabled, as the server refuses a
     *     listener of such a plugin; no menus exist then
     */
    public Menus(Parts parts) {
        this(parts, marker -> new PaperInventories(parts.server(), marker),
            player -> parts.server().isOwnedByCurrentRegion(player), Menus::showable, System::nanoTime,
            listener -> parts.server().getPluginManager().registerEvents(listener, parts.plugin()));
    }

    Menus(Parts parts, Function<PreviewMarker, Inventories> inventories, Predicate<Player> ownedHere,
          Predicate<Material> item, LongSupplier nanos, Consumer<Listener> registrar) {
        this.parts = Objects.requireNonNull(parts, "parts");
        this.registrar = registrar;
        this.item = item;
        this.nanos = nanos;
        this.marker = new PreviewMarker(parts.preview());
        this.inventories = inventories.apply(marker);
        this.ownedHere = ownedHere;
        this.feedback = new Feedback(parts.sounds());
        this.layouts = parts.layouts();
        this.text = new MenuText(parts.text());
        this.debounce = new Debounce(() -> parts.settings().clickDebounce(), nanos);
        this.listener = new MenuListener(this);
        registrar.accept(listener);
    }

    /**
     * Gives the lang keys the menus of these kinds read with no other text to fall back to: {@link #ERROR},
     * {@link Frame#LOADING} when a kind shows a list, each kind's {@code gui.<id>.title}, and for each action with text
     * the name key of the last base {@link MenuKind#textBases(String)} gives, which for a footer action is
     * {@code gui.footer.<action>.name}. Lore, the empty state and a menu's own footer text are optional, and a layout
     * that writes a title or an item name needs no key for it. A plugin test can check its bundled English file
     * against these and {@link Prompts#KEYS}.
     *
     * @param kinds the kinds of the plugin's menus
     * @return the keys in order, each once
     * @throws NullPointerException if {@code kinds} or a kind is {@code null}
     */
    public static List<String> keys(Collection<MenuKind> kinds) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(ERROR);
        if (kinds.stream().anyMatch(MenuKind::paged)) {
            keys.add(Frame.LOADING);
        }
        for (MenuKind kind : kinds) {
            keys.add("gui." + kind.id() + ".title");
            for (String action : kind.actions()) {
                List<String> bases = kind.textBases(action);
                if (!bases.isEmpty()) {
                    keys.add(bases.getLast() + ".name");
                }
            }
        }
        return List.copyOf(keys);
    }

    // Material.isItem reads the item registry, which exists only on a running server.
    private static boolean showable(Material material) {
        return material.isItem() && !material.isAir();
    }

    void prompts(MenuPrompts prompts) {
        this.prompts = prompts;
    }

    MenuPrompts prompts() {
        return prompts;
    }

    /**
     * Registers a menu under its kind's id.
     *
     * @param menu the menu
     * @throws IllegalArgumentException if no layout was given for the menu's id, the layout does not fit the menu's
     *     kind (a kind that shows a list with a layout that has none, or an item whose action is neither one of the
     *     kind's actions nor a footer action), a material is not an item a slot can show (the material of a layout
     *     item, the layout's filler or empty state, or the filler or loading item of the {@link MenuSettings}; air
     *     is no such item), or a menu is already registered under it
     */
    public void register(Menu<?> menu) {
        String id = menu.kind().id();
        MenuLayout layout = layouts.get(id);
        if (layout == null) {
            throw new IllegalArgumentException("no layout was given for the " + id + " menu");
        }
        fit(menu.kind(), layout);
        materials(layout);
        if (menus.putIfAbsent(id, menu) != null) {
            throw new IllegalArgumentException("the " + id + " menu is already registered");
        }
    }

    /**
     * Tells whether a menu is registered.
     *
     * @param menu the id of the menu
     * @return whether a menu is registered under {@code menu}
     */
    public boolean registered(String menu) {
        return menus.containsKey(menu);
    }

    /**
     * Opens a menu in its initial state now. Call on the viewer's entity thread, outside an inventory event.
     *
     * @param viewer the player to show the menu to
     * @param menu the id of the menu
     * @param back the route the back button returns to, or {@code null} for none
     * @throws IllegalArgumentException if no menu is registered under {@code menu}
     * @throws IllegalStateException if the calling thread does not own the viewer and {@link #shutdown()} did not
     *     run; nothing is opened, closed or ended then, and {@link #openLater(Player, String, Route)} opens from any
     *     thread
     */
    public void open(Player viewer, String menu, @Nullable Route back) {
        open(viewer, menu(menu), back);
    }

    /**
     * Opens {@code route.menu()} in {@code route.state()} now. Call on the viewer's entity thread, outside an
     * inventory event.
     *
     * @param viewer the player to show the menu to
     * @param route the menu, its state and its back route
     * @throws IllegalArgumentException if no menu is registered under the route's menu, or the route's state is not
     *     an instance of that menu's {@link Menu#stateType()}
     * @throws IllegalStateException if the calling thread does not own the viewer and {@link #shutdown()} did not
     *     run; nothing is opened, closed or ended then, and {@link #openLater(Player, Route)} opens from any thread
     */
    public void open(Player viewer, Route route) {
        reopen(viewer, menu(route), route);
    }

    /**
     * Opens the route as {@link #open(Player, Route)} does and tells whether the viewer now sees its menu:
     * {@code false} when the server refused to open the inventory or {@link #shutdown()} ran.
     */
    boolean opens(Player viewer, Route route) {
        return reopen(viewer, menu(route), route);
    }

    /**
     * Opens {@code route.menu()} in {@code route.state()} on the next tick, on the viewer's entity thread.
     *
     * @param viewer the player to show the menu to
     * @param route the menu, its state and its back route
     * @throws IllegalArgumentException if no menu is registered under the route's menu, or the route's state is not
     *     an instance of that menu's {@link Menu#stateType()}
     */
    public void openLater(Player viewer, Route route) {
        menu(route);
        later(viewer, () -> open(viewer, route));
    }

    /**
     * Opens a menu in its initial state on the next tick, on the viewer's entity thread.
     *
     * @param viewer the player to show the menu to
     * @param menu the id of the menu
     * @param back the route the back button returns to, or {@code null} for none
     * @throws IllegalArgumentException if no menu is registered under {@code menu}
     */
    public void openLater(Player viewer, String menu, @Nullable Route back) {
        menu(menu);
        later(viewer, () -> open(viewer, menu, back));
    }

    /**
     * Draws the session again in place; nothing happens once it closed. Call on the viewer's entity thread.
     *
     * @param session the session to draw
     * @throws IllegalStateException if the session is open and the calling thread does not own its viewer; nothing
     *     is drawn then, and {@link #rerenderLater(MenuSession)} draws from any thread
     */
    public void rerender(MenuSession<?> session) {
        if (session.closed()) {
            return;
        }
        onViewerThread(session.viewer(), "Menus.rerender", "use Menus.rerenderLater");
        paint(session);
    }

    /**
     * Draws the session again on the next tick, on the viewer's entity thread, unless it closed by then.
     *
     * @param session the session to draw
     */
    public void rerenderLater(MenuSession<?> session) {
        later(session.viewer(), () -> rerender(session));
    }

    /**
     * Closes the viewer's menu on the next tick, when one is open then.
     *
     * @param viewer the player whose menu closes
     */
    public void closeLater(Player viewer) {
        parts.tasks().accept(viewer, () -> {
            if (sessions.containsKey(viewer.getUniqueId())) {
                viewer.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
        });
    }

    /**
     * Runs {@code apply} on the viewer's entity thread once {@code source} completes and draws the session again,
     * unless the session closed or a later load started meanwhile. Call on the viewer's entity thread, such as from
     * {@link Menu#opened(MenuSession, Player)} or a {@link ClickHandler}. A load the handler of an exclusive button
     * starts keeps the session {@link MenuSession#busy()} once the handler returned, with no call in the handler.
     * {@link MenuSession#busy()}, a handler's own {@link MenuSession#busy(boolean)} hold included, clears when the load
     * applies, fails or is cancelled, unless a later load started. A failure of {@code source} or {@code apply} is
     * logged with its stack and reported; while the session is open and no later load started, the viewer is also told
     * {@link #ERROR} and hears {@link Cue#FAIL}. A
     * {@code source} that was cancelled is no failure: nothing is logged, reported or told. When the viewer left the
     * server, the viewer is told nothing; a failure of {@code source} itself is still logged and reported. Once
     * {@link #shutdown()} ran, a load that ends without applying does nothing more.
     *
     * @param session the session the load is for
     * @param source the value being loaded, completed on any thread
     * @param apply puts the value into the session, on the viewer's entity thread
     * @param <S> the type of the state the menu shows
     * @param <T> the type of the loaded value
     * @throws IllegalStateException if the calling thread does not own the session's viewer; no load starts then. Call
     *     from {@link Menu#opened(MenuSession, Player)} or a {@link ClickHandler}, or hop to the viewer's thread
     *     through {@link EntityThread#call}
     */
    public <S, T> void load(MenuSession<S> session, CompletableFuture<T> source, BiConsumer<MenuSession<S>, T> apply) {
        onViewerThread(session.viewer(), "Menus.load",
            "call it from Menu.opened or a ClickHandler, or hop there through EntityThread.call");
        long token = session.nextLoad();
        UUID viewer = session.viewer().getUniqueId();
        source.thenCompose(value -> parts.entity().call(viewer, player -> {
            if (!session.closed() && session.loadToken() == token) {
                session.loadEnded();
                apply.accept(session, value);
                rerender(session);
            }
            return null;
        })).whenComplete((done, failure) -> {
            Throwable cause = unwrap(failure);
            if (cause == null || cause instanceof EntityUnavailableException || stopping) {
                return;
            }
            boolean failed = !(cause instanceof CancellationException);
            if (failed) {
                parts.logger().log(Level.WARNING, "gui: " + session.id() + " could not load for "
                    + session.viewer().getName() + ": " + cause, cause);
                parts.errors().report("gui.load", cause);
            }
            parts.entity().call(viewer, player -> {
                if (session.loadToken() != token) {
                    return null;
                }
                session.loadEnded();
                if (failed && !session.closed()) {
                    player.sendMessage(parts.text().render(parts.locales().apply(player), ERROR));
                    feedback.play(player, Cue.FAIL);
                }
                return null;
            });
        });
    }

    /**
     * Gives the viewer's open session.
     *
     * @param viewer the id of the viewer
     * @return the session, or empty when the viewer has no menu open
     */
    public Optional<MenuSession<?>> session(UUID viewer) {
        return Optional.ofNullable(sessions.get(viewer));
    }

    /**
     * Gives the menu text, for drawing icons outside a render.
     *
     * @return the text every frame reads
     */
    public MenuText text() {
        return text;
    }

    /**
     * Gives what each viewer last chose in each menu. A quit forgets a viewer's choices.
     *
     * @return the view memory
     */
    public ViewMemory memory() {
        return memory;
    }

    /**
     * Plays the sound {@link Parts#sounds()} maps the cue to at the time, and nothing for a cue it does not map. Call
     * on the player's entity thread.
     *
     * @param player the player who hears the sound, from where they stand
     * @param cue the moment to mark
     */
    public void play(Player player, Cue cue) {
        feedback.play(player, cue);
    }

    /**
     * Gives a listener with no event handler, so that registering it does nothing.
     *
     * @return the same listener on every call
     * @deprecated the menus register their own listener when they are built, see {@link #Menus(Parts)}; remove the
     *     {@code registerEvents} call that took this listener
     */
    @Deprecated
    public Listener listener() {
        return INERT;
    }

    /** Registers a listener of the prompts built over these menus as a listener of the menus' plugin. */
    void register(Listener listener) {
        registrar.accept(listener);
    }

    MenuListener menuListener() {
        return listener;
    }

    /**
     * Replaces the layouts. Open sessions keep the layout they opened with; the next open uses the new one. The
     * {@link MenuSettings} stay the ones given in {@link Parts}. A refused map leaves the layouts as they were.
     *
     * @param layouts the layout of each menu, keyed by menu id
     * @throws NullPointerException if {@code layouts}, a key or a layout is {@code null}
     * @throws IllegalArgumentException if a key differs from its layout's {@link MenuLayout#menu()}, a registered
     *     menu has no layout, or a registered menu's layout does not fit its kind or holds a material that is not
     *     an item, as {@link #register(Menu)} requires
     */
    public void reload(Map<String, MenuLayout> layouts) {
        Map<String, MenuLayout> copy = checked(layouts);
        for (Menu<?> menu : menus.values()) {
            MenuLayout layout = copy.get(menu.kind().id());
            if (layout == null) {
                throw new IllegalArgumentException("no layout was given for the " + menu.kind().id() + " menu");
            }
            fit(menu.kind(), layout);
            materials(layout);
        }
        this.layouts = copy;
    }

    /** Tells whether {@link #shutdown()} ran. */
    boolean stopping() {
        return stopping;
    }

    /**
     * Runs {@code replace} (an open of another view) with the viewer's open session marked as handed off, so its close
     * during {@code replace} is not the viewer leaving. If {@code replace} returns {@code false} the viewer is left
     * without a menu, so a session that closed during {@code replace} gets its closed callback with
     * {@code left = true}. Call on the viewer's entity thread, outside an inventory event. Returns what
     * {@code replace} returned.
     */
    boolean handoff(Player viewer, BooleanSupplier replace) {
        MenuSession<?> previous = sessions.get(viewer.getUniqueId());
        if (previous == null) {
            return replace.getAsBoolean();
        }
        previous.handoff(true);
        boolean replaced = false;
        try {
            replaced = replace.getAsBoolean();
            return replaced;
        } finally {
            previous.handoff(false);
            if (previous.takeClosedInHandoff()) {
                notifyClosed(previous, !replaced && !stopping);
            }
        }
    }

    /** The session's inventory closed, for any reason, as its close event or shutdown reports it. */
    void closed(MenuSession<?> session) {
        sessions.remove(session.viewer().getUniqueId(), session);
        if (session.closed()) {
            return;
        }
        if (session.close()) {
            return;
        }
        notifyClosed(session, !stopping);
    }

    /** Forgets everything kept for a viewer who quit: the click debounce, the view memory and the session. */
    void forget(UUID viewer) {
        debounce.forget(viewer);
        memory.forget(viewer);
        sessions.remove(viewer);
    }

    /**
     * Ends every ask of the prompts built over these menus without a message, then closes every open menu now, on the
     * calling thread, for each viewer that thread owns, and runs each menu's closed step with {@code left = false}
     * itself: Paper marks the plugin disabled before {@code onDisable} and skips its listeners, so no close event
     * arrives. Each viewer whose menu it closed is sent their inventory again through
     * {@link Player#updateInventory()}. The menus close even when ending the prompts throws. Nothing opens and nothing
     * is asked afterwards, and
     * a second call does nothing. The menu or prompt anvil of a viewer the calling thread does not own is forgotten
     * without its closed step and stays open. While the plugin is still enabled the listener keeps cancelling the
     * clicks in that menu and runs no handler; once the plugin is disabled no listener cancels its clicks. Its
     * inventory is emptied from the calling thread, so such a viewer is left with an empty menu and has no copy to
     * take. On Paper the disabling thread owns every player, so this happens only on Folia.
     */
    public void shutdown() {
        if (stopping) {
            return;
        }
        try {
            prompts.shutdown();
        } finally {
            stopping = true;
            for (MenuSession<?> session : List.copyOf(sessions.values())) {
                Player viewer = session.viewer();
                if (session.closed()) {
                    continue;
                }
                if (viewer.isOnline() && ownedHere.test(viewer)) {
                    viewer.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
                    closed(session);
                    viewer.updateInventory();
                } else {
                    session.getInventory().clear();
                }
            }
            sessions.clear();
        }
    }

    PreviewMarker marker() {
        return marker;
    }

    TextSource source() {
        return parts.text();
    }

    Function<Player, Locale> locales() {
        return parts.locales();
    }

    EntityThread entity() {
        return parts.entity();
    }

    Predicate<Player> ownedHere() {
        return ownedHere;
    }

    Logger logger() {
        return parts.logger();
    }

    ErrorReports errors() {
        return parts.errors();
    }

    Feedback feedback() {
        return feedback;
    }

    Debounce debounce() {
        return debounce;
    }

    MenuSettings settings() {
        return parts.settings();
    }

    @Nullable String home() {
        return parts.home();
    }

    BiConsumer<Player, Runnable> tasks() {
        return parts.tasks();
    }

    private void later(Player viewer, Runnable task) {
        parts.tasks().accept(viewer, () -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                parts.logger().log(Level.WARNING, "gui: a menu failed to draw for " + viewer.getName() + ": "
                    + failure, failure);
                parts.errors().report("gui.render", failure);
            }
        });
    }

    private <S> void open(Player viewer, Menu<S> menu, @Nullable Route back) {
        if (stopping) {
            return;
        }
        onViewerThread(viewer, "Menus.open", "use Menus.openLater");
        start(viewer, menu, menu.initial(viewer), back);
    }

    @SuppressWarnings("unchecked")
    private <S> boolean reopen(Player viewer, Menu<S> menu, Route route) {
        return start(viewer, menu, (S) route.state(), route.back());
    }

    /** Opens the menu and tells whether the viewer now sees it: not once shutdown ran or when the server refused. */
    private <S> boolean start(Player viewer, Menu<S> menu, S state, @Nullable Route back) {
        if (stopping) {
            return false;
        }
        onViewerThread(viewer, "Menus.open", "use Menus.openLater");
        prompts.end(viewer.getUniqueId());
        MenuLayout layout = layouts.get(menu.kind().id());
        MenuSession<S> session = new MenuSession<>(this, viewer, menu, layout, state, back, nanos.getAsLong());
        Locale locale = parts.locales().apply(viewer);
        Component title = menu.title(new Frame<>(this, session, locale));
        session.attach(inventories.create(session, layout.size(), title));
        paint(session);
        if (!handoff(viewer, () -> shows(viewer.openInventory(session.getInventory()), session))) {
            session.close();
            return false;
        }
        sessions.put(viewer.getUniqueId(), session);
        menu.opened(session, viewer);
        return true;
    }

    /** A refused open returns null when nothing was open before, else the viewer's own inventory view. */
    private static boolean shows(@Nullable InventoryView view, MenuSession<?> session) {
        return view != null && view.getTopInventory() == session.getInventory();
    }

    private <S> void paint(MenuSession<S> session) {
        Frame<S> frame = new Frame<>(this, session, parts.locales().apply(session.viewer()));
        frame.decorations();
        session.menu().render(frame);
        Material filler = session.layout().filler() == null ? parts.settings().filler() : session.layout().filler();
        Canvas canvas = session.canvas();
        if (canvas == null) {
            throw new IllegalStateException("the " + session.id() + " menu has no inventory yet");
        }
        session.rendered(frame.paint(canvas, filler), nanos.getAsLong());
    }

    private void onViewerThread(Player viewer, String method, String instead) {
        if (!ownedHere.test(viewer)) {
            throw new IllegalStateException(method + " must run on the viewer's entity thread; " + instead);
        }
    }

    private <S> void notifyClosed(MenuSession<S> session, boolean left) {
        try {
            session.menu().closed(session, left);
        } catch (RuntimeException failure) {
            parts.logger().log(Level.WARNING, "gui: " + session.id() + " failed to close for "
                + session.viewer().getName() + ": " + failure, failure);
            parts.errors().report("gui.close", failure);
        }
    }

    private Menu<?> menu(String id) {
        Menu<?> menu = menus.get(id);
        if (menu == null) {
            throw new IllegalArgumentException("no menu is registered as " + id);
        }
        return menu;
    }

    private Menu<?> menu(Route route) {
        Menu<?> menu = menu(route.menu());
        Class<?> type = menu.stateType();
        if (!type.isInstance(route.state())) {
            throw new IllegalArgumentException("the " + route.menu() + " menu shows a state of the type "
                + type.getName() + ", not " + route.state().getClass().getName());
        }
        return menu;
    }

    private static void fit(MenuKind kind, MenuLayout layout) {
        if (kind.paged() && layout.pager() == null) {
            throw new IllegalArgumentException("the " + kind.id() + " menu shows a list, but its layout has none");
        }
        for (ItemLayout item : layout.items().values()) {
            String action = item.action();
            if (action != null && !kind.has(action) && !MenuKind.footer(action)) {
                throw new IllegalArgumentException("item " + item.id() + " of the " + kind.id() + " menu has the action "
                    + action + ", which the menu does not have");
            }
        }
    }

    private void materials(MenuLayout layout) {
        MenuSettings settings = parts.settings();
        item("the filler of the menu settings", settings.filler());
        item("the loading item of the menu settings", settings.loadingItem());
        String menu = "the " + layout.menu() + " menu";
        if (layout.filler() != null) {
            item("the filler of " + menu, layout.filler());
        }
        PagerLayout pager = layout.pager();
        if (pager != null && pager.emptyMaterial() != null) {
            item("the empty state of " + menu, pager.emptyMaterial());
        }
        for (ItemLayout placed : layout.items().values()) {
            item("item " + placed.id() + " of " + menu, placed.material());
        }
    }

    private void item(String what, Material material) {
        if (!item.test(material)) {
            throw new IllegalArgumentException(what + " has the material " + material + ", which is not an item");
        }
    }

    private static Map<String, MenuLayout> checked(Map<String, MenuLayout> layouts) {
        Map<String, MenuLayout> copy = Map.copyOf(layouts);
        copy.forEach((id, layout) -> {
            if (!id.equals(layout.menu())) {
                throw new IllegalArgumentException("the layout given for the " + id + " menu is the layout of the "
                    + layout.menu() + " menu");
            }
        });
        return copy;
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
