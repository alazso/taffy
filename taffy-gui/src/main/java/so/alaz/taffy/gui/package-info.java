/**
 * Chest menus for Paper plugins.
 *
 * <p>A {@link so.alaz.taffy.gui.MenuLayout} describes how one menu looks: its rows, filler, the
 * {@link so.alaz.taffy.gui.PagerLayout} its list fills and the {@link so.alaz.taffy.gui.ItemLayout} items it places.
 * Each of these, the {@link so.alaz.taffy.gui.MenuSettings} and {@link so.alaz.taffy.gui.Menus.Parts} is built
 * through a static factory that takes what has no default ({@code MenuLayout.of}, {@code PagerLayout.of},
 * {@code ItemLayout.of}, {@code Menus.Parts.of}, and {@code MenuSettings.DEFAULTS} as the settings to start from)
 * and methods named after each other value, which give a copy with that value replaced. None has a public
 * constructor. The plugin builds layouts in code or from files it reads itself; the library reads no file. A
 * {@link so.alaz.taffy.gui.MenuKind} names a menu's actions and the lang keys their text reads, and
 * {@link so.alaz.taffy.gui.Pager} gives the page to show for a requested page, a list size and the number of entries
 * a page holds.
 *
 * <p>An {@link so.alaz.taffy.gui.Icon} describes what one slot shows: a button, a filler, a copy of a real item or an
 * item drawn from its saved description. {@link so.alaz.taffy.gui.MenuText} gives a menu's title and each item's name
 * and lore, from the layout when it sets them and from the plugin's lang files otherwise. The menus draw each icon
 * into an inventory the server creates, through the item meta: the name, lore, tooltip, hidden detail lines, glint and
 * amount the icon describes. Every stack a menu or prompt anvil shows carries the plugin's preview key
 * ({@link so.alaz.taffy.gui.Menus.Parts#preview()}), so a copy that leaves the menu can be told apart from a real
 * item.
 *
 * <p>{@link so.alaz.taffy.gui.ClickGuard} names the click types a button acts on.
 * {@link so.alaz.taffy.gui.Menus#play(org.bukkit.entity.Player, so.alaz.taffy.gui.Cue)} plays the sound
 * {@link so.alaz.taffy.gui.Menus.Parts#sounds()} maps to a {@link so.alaz.taffy.gui.Cue}, and
 * {@link so.alaz.taffy.gui.ViewMemory} keeps what each viewer last chose in each menu until they quit.
 *
 * <p>{@link so.alaz.taffy.gui.Menus} runs the menus. The plugin builds one at enable from
 * {@link so.alaz.taffy.gui.Menus.Parts}. {@code Menus.Parts.of} takes the server, the plugin's preview key
 * ({@code new NamespacedKey(plugin, "preview")}), its logger, its text, the entity-thread seams
 * ({@code scheduler::entity} and {@code EntityThread.on(scheduler, server)}) and its {@code ErrorReports}; the
 * layout of each menu keyed by its id, the {@link so.alaz.taffy.gui.MenuSettings}, how a player's locale is chosen, a
 * supplier of the sounds and the id of the home menu are set on the result and default to no layouts,
 * {@code MenuSettings.DEFAULTS}, {@code Player::locale}, no sounds and no home menu. The text is a
 * {@link so.alaz.taffy.gui.TextSource}, a function from a locale, a lang key and its values to a component;
 * {@code Menus.Parts.of} also takes Taffy's {@code Messages} in its place. It then registers each
 * {@link so.alaz.taffy.gui.Menu}; a menu with no layout, or with a layout that does not fit its kind, is refused.
 *
 * <p>A menu opens on the viewer's entity thread: {@link so.alaz.taffy.gui.Menus#open(org.bukkit.entity.Player,
 * so.alaz.taffy.gui.Route)} opens it now, and {@code openLater} on the next tick, which is how a
 * {@link so.alaz.taffy.gui.ClickHandler} opens one from inside a click. Each open creates a
 * {@link so.alaz.taffy.gui.MenuSession}, which holds the state and is the inventory's holder, and renders it: the menu
 * places its actions, list rows and states on a {@link so.alaz.taffy.gui.Frame}, and every slot left untouched shows
 * the filler. {@link so.alaz.taffy.gui.Frame#footer} draws back, the page arrows, refresh and close.
 *
 * <p>The plugin registers {@link so.alaz.taffy.gui.Menus#listener()} at enable. It cancels every click and drag in a
 * menu view, passes a plain click by the menu's viewer to the button in the clicked slot once the menu is past its
 * opening window, the button is not held back by a busy session and its own click debounce passed, and deletes menu
 * copies it finds outside menus. A close ends the session and a quit forgets what the menus and prompts kept for the
 * player.
 *
 * <p>A menu loads slow data through {@code Menus.load}: the source completes on any thread, the value is applied on
 * the viewer's entity thread and the session is drawn again, unless it closed or a later load started. A failed load is
 * logged, reported under {@code gui.load}, told to the viewer as {@link so.alaz.taffy.gui.Menus#ERROR} and marked with
 * {@link so.alaz.taffy.gui.Cue#FAIL}.
 *
 * <p>{@link so.alaz.taffy.gui.Prompts} asks a player for one line of text in place of a menu and opens a menu with
 * the answer: a {@link so.alaz.taffy.gui.PromptRequest} holds the question, the menu a cancel or timeout returns to
 * and the function that judges the text into an {@link so.alaz.taffy.gui.Answer}. The plugin builds the prompts at
 * enable with {@code new Prompts(menus, mode, bedrock, scheduler::delayedAsync)}; they read everything else from the
 * menus, and building them links them to the menus, so that opening a menu ends a pending prompt. The player types
 * into chat, or, for {@link so.alaz.taffy.gui.PromptMode#ANVIL}, into an anvil the prompts build; Bedrock players
 * answer in chat. The plugin registers {@link so.alaz.taffy.gui.Prompts#listener()} at enable, which reads the chat
 * answers, cancels every click and drag on the prompt anvil, submits its result slot and ends the ask when the anvil
 * closes. A {@link so.alaz.taffy.gui.ClickHandler} asks through {@link so.alaz.taffy.gui.Prompts#askLater}, on the
 * next tick. No prefix is put in front of the prompt text unless the text writes {@code <prefix>}.
 *
 * <p>No lang file ships with the library. {@link so.alaz.taffy.gui.Menus#keys(java.util.Collection)} lists the lang
 * keys the plugin's menus read with no other text to fall back to, and {@link so.alaz.taffy.gui.Prompts#KEYS} the
 * keys of the prompts, so a plugin test can check its bundled English file against both.
 *
 * <p>On reload, {@code Messages.reload} (or new text behind the plugin's own
 * {@link so.alaz.taffy.gui.TextSource}) brings new text to every menu and prompt drawn afterwards,
 * {@link so.alaz.taffy.gui.Menus#reload(java.util.Map)} new layouts to every menu opened afterwards, and a new map
 * behind the sounds supplier new sounds to the next cue played. The {@link so.alaz.taffy.gui.MenuSettings}, the home
 * menu, everything else in {@link so.alaz.taffy.gui.Menus.Parts} and the {@link so.alaz.taffy.gui.PromptMode} hold
 * until the plugin builds them anew, so they are restart settings.
 *
 * <p>At disable the plugin calls {@link so.alaz.taffy.gui.Menus#shutdown()}, which ends every prompt first, before
 * {@code Scheduler.close}, in the order the {@code so.alaz.taffy.platform} package states. Shutdown closes the menus
 * and anvils of the viewers the calling thread owns and runs the menus' closed steps itself, since no close event
 * arrives while the plugin disables. The menus and anvils of viewers the calling thread does not own are forgotten and
 * stay open with no listener guarding them. Such a viewer can take the copies that menu shows; each carries
 * {@link so.alaz.taffy.gui.Menus.Parts#preview()}, and the listener of a later enable deletes one when a click meets it
 * outside a menu. On Paper the disabling thread owns every player, so this happens only on Folia.
 */
package so.alaz.taffy.gui;
