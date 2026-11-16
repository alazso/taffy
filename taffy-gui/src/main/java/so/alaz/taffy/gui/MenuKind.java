package so.alaz.taffy.gui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A menu id, whether the menu shows a list, and its actions with the lang key base each action's text reads. An
 * action reads its name from {@code <base>.name} and its lore from {@code <base>.lore}. A {@code null} base means the
 * menu supplies the whole icon, so the action has no text of its own.
 *
 * <p>The footer actions {@link #BACK}, {@link #PREVIOUS_PAGE}, {@link #NEXT_PAGE}, {@link #REFRESH} and
 * {@link #CLOSE} fall back to {@code gui.footer.<action>} when their own base has no text.
 *
 * @param id the menu id
 * @param paged whether the menu shows a list
 * @param texts each action with its lang key base, or {@code null} for an action without text, in the order given
 */
public record MenuKind(String id, boolean paged, Map<String, @Nullable String> texts) {

    /** The footer action that opens the menu the viewer came from, or the home menu. */
    public static final String BACK = "back";

    /** The footer action that shows the previous page of the list. */
    public static final String PREVIOUS_PAGE = "previous-page";

    /** The footer action that shows the next page of the list. */
    public static final String NEXT_PAGE = "next-page";

    /** The footer action that reloads what the menu shows. */
    public static final String REFRESH = "refresh";

    /** The footer action that closes the menu. */
    public static final String CLOSE = "close";

    /** The lang key base prefix a footer action falls back to, followed by the action. */
    public static final String FOOTER_TEXT = "gui.footer.";

    private static final Set<String> FOOTER = Set.of(BACK, PREVIOUS_PAGE, NEXT_PAGE, REFRESH, CLOSE);

    /**
     * Copies the actions.
     *
     * @throws NullPointerException if {@code id}, {@code texts} or an action is {@code null}
     */
    public MenuKind {
        Objects.requireNonNull(id, "id");
        texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
        for (String action : texts.keySet()) {
            Objects.requireNonNull(action, "action");
        }
    }

    /**
     * Gives a kind whose actions each read their text from {@code gui.<id>.<action>}.
     *
     * @param id the menu id
     * @param paged whether the menu shows a list
     * @param actions the menu's actions, in order
     * @return the kind
     * @throws NullPointerException if {@code id} or an action is {@code null}
     */
    public static MenuKind of(String id, boolean paged, String... actions) {
        Map<String, @Nullable String> texts = new LinkedHashMap<>();
        for (String action : actions) {
            texts.put(action, "gui." + id + "." + action);
        }
        return new MenuKind(id, paged, texts);
    }

    /**
     * Gives the same kind with {@code action} reading its text from {@code base}.
     *
     * @param action one of this menu's actions
     * @param base the lang key base, or {@code null} for an action whose icon the menu supplies whole
     * @return a copy of this kind with the action's base replaced
     * @throws IllegalArgumentException if this menu has no action {@code action}
     */
    public MenuKind text(String action, @Nullable String base) {
        if (!texts.containsKey(action)) {
            throw new IllegalArgumentException("the " + id + " menu has no action " + action);
        }
        Map<String, @Nullable String> changed = new LinkedHashMap<>(texts);
        changed.put(action, base);
        return new MenuKind(id, paged, changed);
    }

    /**
     * Gives the menu's actions.
     *
     * @return the actions, in the order given
     */
    public Set<String> actions() {
        return texts.keySet();
    }

    /**
     * Tells whether the menu has an action.
     *
     * @param action the action
     * @return whether {@code action} is one of this menu's actions
     */
    public boolean has(String action) {
        return texts.containsKey(action);
    }

    /**
     * Gives the lang key bases tried in order for an action's name and lore: its own base, then
     * {@code gui.footer.<action>} for a footer action.
     *
     * @param action the action
     * @return the bases, empty when the action has no text or the menu has no such action
     */
    public List<String> textBases(String action) {
        String base = texts.get(action);
        if (base == null) {
            return List.of();
        }
        List<String> bases = new ArrayList<>(List.of(base));
        if (footer(action)) {
            bases.add(FOOTER_TEXT + action);
        }
        return List.copyOf(bases);
    }

    static boolean footer(String action) {
        return FOOTER.contains(action);
    }
}
