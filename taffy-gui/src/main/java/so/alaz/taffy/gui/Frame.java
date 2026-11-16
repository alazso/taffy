package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * One render of a menu session. The menu places its actions, list rows and states; every slot left untouched shows
 * the filler. Buttons exist only for the slots this render placed with a handler. A frame is used on the viewer's
 * entity thread, inside {@link Menu#render(Frame)} or {@link Menu#title(Frame)}.
 *
 * <p>The plugin's lang files hold the text a frame reads: {@link #LOADING} for the loading item, and
 * {@code gui.<id>.empty.name} and {@code gui.<id>.empty.lore} for a menu's empty state.
 *
 * @param <S> the type of the state the menu shows
 */
public final class Frame<S> {

    /** The lang key of the loading item's name. */
    public static final String LOADING = "gui.common.loading.name";

    private final Menus menus;
    private final MenuSession<S> session;
    private final Locale locale;
    private final @Nullable Icon[] icons;
    private final Map<Integer, Button> buttons = new HashMap<>();

    Frame(Menus menus, MenuSession<S> session, Locale locale) {
        this.menus = menus;
        this.session = session;
        this.locale = locale;
        this.icons = new Icon[session.layout().size()];
    }

    /**
     * Gives the state this render shows.
     *
     * @return the session's state
     */
    public S state() {
        return session.state();
    }

    /**
     * Gives the player who sees the menu.
     *
     * @return the viewer
     */
    public Player viewer() {
        return session.viewer();
    }

    /**
     * Gives the locale the text is rendered in.
     *
     * @return the viewer's locale
     */
    public Locale locale() {
        return locale;
    }

    /**
     * Gives the layout the session opened with.
     *
     * @return the layout
     */
    public MenuLayout layout() {
        return session.layout();
    }

    /**
     * Gives the menu's kind.
     *
     * @return the kind
     */
    public MenuKind kind() {
        return session.menu().kind();
    }

    /**
     * Gives the session this render draws.
     *
     * @return the session
     */
    public MenuSession<S> session() {
        return session;
    }

    /**
     * Gives the menu text.
     *
     * @return the text of the menus
     */
    public MenuText text() {
        return menus.text();
    }

    /**
     * Gives the menus, for a handler that opens another menu.
     *
     * @return the menus
     */
    public Menus menus() {
        return menus;
    }

    /**
     * Places every enabled layout item with this action that the viewer has the permission for. The text comes from
     * the action's lang key bases with the spec's placeholders, then the spec's extra lore; a spec icon replaces the
     * whole icon.
     *
     * @param action one of the menu's actions
     * @param spec how the action renders
     * @throws IllegalArgumentException if the menu's kind has no action {@code action}
     */
    public void action(String action, Spec spec) {
        if (!kind().has(action)) {
            throw new IllegalArgumentException("the " + kind().id() + " menu has no action " + action);
        }
        for (ItemLayout item : layout().items(action)) {
            if (!shown(item)) {
                continue;
            }
            Icon icon = spec.icon();
            if (icon == null) {
                TagResolver[] placeholders = spec.placeholders().toArray(TagResolver[]::new);
                List<String> bases = kind().textBases(action);
                List<Component> lore = new ArrayList<>(text().lore(locale, item, bases, placeholders));
                lore.addAll(spec.extraLore());
                icon = new Icon.Button(item.material(), spec.amount(), text().name(locale, item, bases, placeholders),
                    lore, spec.glint());
            }
            for (int slot : item.slots()) {
                place(slot, icon, spec.handler() == null ? null
                    : new Button(icon, spec.clicks(), item.id(), spec.exclusive(), spec.handler()));
            }
        }
    }

    /**
     * Fills the first {@link #capacity()} list slots with the page of entries nearest {@code requestedPage}. A row's
     * button key is {@code row:<slot>}.
     *
     * @param entries every entry of the list, in order
     * @param requestedPage the index of the page asked for, counting from 0
     * @return the page shown
     * @throws IllegalStateException if the layout has no list
     */
    public Pager.Page list(List<Entry> entries, int requestedPage) {
        PagerLayout pager = pager();
        Pager.Page page = Pager.page(entries.size(), capacity(pager), requestedPage);
        for (int index = page.from(); index < page.to(); index++) {
            int slot = pager.slots().get(index - page.from());
            Entry entry = entries.get(index);
            place(slot, entry.icon(), entry.handler() == null ? null
                : new Button(entry.icon(), entry.clicks(), "row:" + slot, entry.exclusive(), entry.handler()));
        }
        return page;
    }

    /**
     * Gives how many rows one page of the list shows: the smaller of the page size and the number of list slots.
     *
     * @return the capacity of a page
     * @throws IllegalStateException if the layout has no list
     */
    public int capacity() {
        return capacity(pager());
    }

    /**
     * Places the loading item, named by {@link #LOADING}, in the first {@link #capacity()} list slots; clicks on them
     * do nothing.
     *
     * @throws IllegalStateException if the layout has no list
     */
    public void loading() {
        PagerLayout pager = pager();
        Icon icon = new Icon.Button(menus.settings().loadingItem(), 1, text().label(locale, LOADING), List.of(),
            false);
        for (int slot : pager.slots().subList(0, capacity(pager))) {
            place(slot, icon, null);
        }
    }

    /**
     * Places the empty state in the list's empty slot, named and described by {@code gui.<id>.empty.name} and
     * {@code gui.<id>.empty.lore} where the lang files hold them. Places nothing when the list has no empty slot.
     *
     * @throws IllegalStateException if the layout has no list
     */
    public void empty() {
        PagerLayout pager = pager();
        Integer slot = pager.emptySlot();
        if (slot == null) {
            return;
        }
        String base = "gui." + kind().id() + ".empty";
        Component name = text().has(locale, base + ".name") ? text().label(locale, base + ".name") : null;
        List<Component> lore = text().has(locale, base + ".lore") ? text().lines(locale, base + ".lore") : List.of();
        Material material = pager.emptyMaterial() == null ? Material.PAPER : pager.emptyMaterial();
        place(slot, new Icon.Button(material, 1, name, lore, false), null);
    }

    /**
     * Draws the footer actions {@link MenuKind#BACK}, {@link MenuKind#PREVIOUS_PAGE}, {@link MenuKind#NEXT_PAGE},
     * {@link MenuKind#REFRESH} and {@link MenuKind#CLOSE} wherever the layout places them. A footer action the menu's
     * kind does not have, or that does not apply, shows filler.
     *
     * <p>Back opens the session's back route. Without one it opens the home menu named in {@link Menus.Parts#home()},
     * unless there is no home, the session is the home menu or the home menu is not registered. The page arrows read
     * {@code <page>} (counting from 1) and {@code <pages>} in their text. Close closes the menu on the next tick.
     *
     * @param page the page the list shows, or {@code null} on a menu without a list
     * @param turn gives the state that shows the requested page, or {@code null} on a menu without a list
     * @param refresh what refresh does, or {@code null} when the menu has nothing to reload; it is exclusive
     */
    public void footer(Pager.@Nullable Page page, @Nullable IntFunction<S> turn, @Nullable ClickHandler refresh) {
        Footer.draw(this, page, turn, refresh);
    }

    /** Places the layout items without an action, shown as written. */
    void decorations() {
        for (ItemLayout item : layout().items().values()) {
            if (item.action() != null || !shown(item)) {
                continue;
            }
            Icon icon = new Icon.Button(item.material(), 1, text().name(locale, item, List.of()),
                text().lore(locale, item, List.of()), false);
            for (int slot : item.slots()) {
                place(slot, icon, null);
            }
        }
    }

    /** Writes every slot, the filler where nothing was placed, and returns the buttons. */
    Map<Integer, Button> paint(Canvas canvas, Material filler) {
        Icon blank = new Icon.Filler(filler);
        for (int slot = 0; slot < icons.length; slot++) {
            canvas.put(slot, icons[slot] == null ? blank : icons[slot]);
        }
        return buttons;
    }

    private boolean shown(ItemLayout item) {
        return item.enabled() && (item.permission() == null || session.viewer().hasPermission(item.permission()));
    }

    private void place(int slot, Icon icon, @Nullable Button button) {
        if (slot < 0 || slot >= icons.length) {
            return;
        }
        icons[slot] = icon;
        if (button == null) {
            buttons.remove(slot);
        } else {
            buttons.put(slot, button);
        }
    }

    private PagerLayout pager() {
        PagerLayout pager = layout().pager();
        if (pager == null) {
            throw new IllegalStateException("the " + kind().id() + " menu has no list");
        }
        return pager;
    }

    private int capacity(PagerLayout pager) {
        return Pager.capacity(menus.settings().pageSize(), pager.slots().size());
    }

    /**
     * How a layout action renders.
     *
     * @param placeholders the values the action's text reads
     * @param extraLore lore lines added after the lang lore
     * @param glint whether the icon glints
     * @param icon a whole icon that replaces the layout's item and text, or {@code null} to draw the layout's item
     * @param amount the stack amount
     * @param clicks the click types the button acts on; only those in {@link ClickGuard#ANY_PLAIN} ever reach it
     * @param exclusive whether the button is dropped while an exclusive action of the session is in flight
     * @param handler what a click does, or {@code null} for display only
     */
    public record Spec(List<TagResolver> placeholders, List<Component> extraLore, boolean glint, @Nullable Icon icon,
                       int amount, Set<ClickType> clicks, boolean exclusive, @Nullable ClickHandler handler) {

        /**
         * Copies the lists and the clicks.
         *
         * @throws NullPointerException if {@code placeholders}, {@code extraLore}, {@code clicks} or one of their
         *     elements is {@code null}
         */
        public Spec {
            placeholders = List.copyOf(placeholders);
            extraLore = List.copyOf(extraLore);
            clicks = Set.copyOf(clicks);
        }

        /**
         * Gives a spec that shows the action and takes no click.
         *
         * @return a display-only spec
         */
        public static Spec display() {
            return new Spec(List.of(), List.of(), false, null, 1, Set.of(), false, null);
        }

        /**
         * Gives a spec that acts on any plain left or right click, shift included.
         *
         * @param handler what a click does
         * @return the spec
         * @throws NullPointerException if {@code handler} is {@code null}
         */
        public static Spec click(ClickHandler handler) {
            return new Spec(List.of(), List.of(), false, null, 1, ClickGuard.ANY_PLAIN, false,
                Objects.requireNonNull(handler, "handler"));
        }

        /**
         * Gives this spec with more placeholders.
         *
         * @param added the values to add
         * @return a copy with {@code added} after the placeholders it had
         */
        public Spec with(TagResolver... added) {
            List<TagResolver> all = new ArrayList<>(placeholders);
            all.addAll(List.of(added));
            return new Spec(all, extraLore, glint, icon, amount, clicks, exclusive, handler);
        }

        /**
         * Gives this spec with other extra lore.
         *
         * @param lines the lines added after the lang lore
         * @return a copy with {@code lines} as its extra lore
         */
        public Spec lore(List<Component> lines) {
            return new Spec(placeholders, lines, glint, icon, amount, clicks, exclusive, handler);
        }

        /**
         * Gives this spec with the glint on or off.
         *
         * @param on whether the icon glints
         * @return a copy with the glint set
         */
        public Spec glint(boolean on) {
            return new Spec(placeholders, extraLore, on, icon, amount, clicks, exclusive, handler);
        }

        /**
         * Gives this spec with a whole icon.
         *
         * @param whole the icon that replaces the layout's item and text
         * @return a copy with the icon set
         */
        public Spec icon(Icon whole) {
            return new Spec(placeholders, extraLore, glint, whole, amount, clicks, exclusive, handler);
        }

        /**
         * Gives this spec with another stack amount.
         *
         * @param count the stack amount
         * @return a copy with the amount set
         */
        public Spec amount(int count) {
            return new Spec(placeholders, extraLore, glint, icon, count, clicks, exclusive, handler);
        }

        /**
         * Gives this spec acting on other click types.
         *
         * @param accepted the click types the button acts on; only those in {@link ClickGuard#ANY_PLAIN} ever
         *     reach it
         * @return a copy with the clicks set
         */
        public Spec clicks(Set<ClickType> accepted) {
            return new Spec(placeholders, extraLore, glint, icon, amount, accepted, exclusive, handler);
        }

        /**
         * Gives this spec as exclusive.
         *
         * @return a copy that is dropped while an exclusive action of the session is in flight
         */
        public Spec exclusively() {
            return new Spec(placeholders, extraLore, glint, icon, amount, clicks, true, handler);
        }
    }

    /**
     * One list row.
     *
     * @param icon what the row shows
     * @param handler what a click does, or {@code null} for display only
     * @param clicks the click types the row acts on; only those in {@link ClickGuard#ANY_PLAIN} ever reach it
     * @param exclusive whether the row is dropped while an exclusive action of the session is in flight
     */
    public record Entry(Icon icon, @Nullable ClickHandler handler, Set<ClickType> clicks, boolean exclusive) {

        /**
         * Checks the icon and copies the clicks.
         *
         * @throws NullPointerException if {@code icon}, {@code clicks} or a click is {@code null}
         */
        public Entry {
            Objects.requireNonNull(icon, "icon");
            clicks = Set.copyOf(clicks);
        }

        /**
         * Gives a row that shows the icon and takes no click.
         *
         * @param icon what the row shows
         * @return a display-only row
         */
        public static Entry display(Icon icon) {
            return new Entry(icon, null, Set.of(), false);
        }

        /**
         * Gives a row that acts on any plain left or right click, shift included.
         *
         * @param icon what the row shows
         * @param handler what a click does
         * @return the row
         * @throws NullPointerException if {@code handler} is {@code null}
         */
        public static Entry click(Icon icon, ClickHandler handler) {
            return new Entry(icon, Objects.requireNonNull(handler, "handler"), ClickGuard.ANY_PLAIN, false);
        }

        /**
         * Gives this row as exclusive.
         *
         * @return a copy that is dropped while an exclusive action of the session is in flight
         */
        public Entry exclusively() {
            return new Entry(icon, handler, clicks, true);
        }
    }
}
