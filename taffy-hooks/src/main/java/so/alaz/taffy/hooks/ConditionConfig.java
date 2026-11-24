package so.alaz.taffy.hooks;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of a config list of conditions, as a {@link ConditionType} reads it: the keys beside {@code type} and
 * {@code deny}, and the place the entry's errors go. An instance is used inside {@link ConditionType#create} only
 * and on the thread that called {@link ConditionRegistry#build}.
 *
 * @since 0.4.14
 */
public final class ConditionConfig {

    private final String type;
    private final String path;
    private final Map<String, Object> values;
    private final @Nullable String deny;
    private final ConditionProblems problems;
    private final ConditionRegistry.Denials denials;
    private int errors;

    ConditionConfig(String type, String path, Map<String, Object> values, @Nullable String deny,
                    ConditionProblems problems, ConditionRegistry.Denials denials) {
        this.type = type;
        this.path = path;
        this.values = values;
        this.deny = deny;
        this.problems = problems;
        this.denials = denials;
    }

    /**
     * The name the type is registered under, in lower case.
     *
     * @return the type name
     * @since 0.4.14
     */
    public String type() {
        return type;
    }

    /**
     * The path of the entry, such as {@code conditions[1]}.
     *
     * @return the path every error of the entry starts with
     * @since 0.4.14
     */
    public String path() {
        return path;
    }

    /**
     * The raw value of a key of the entry.
     *
     * @param key the key inside the entry
     * @return the value as the file's parser gave it, with a group of keys as a {@link Map} or a
     *     {@link ConfigurationSection}, or {@code null} when the entry has no such key
     * @throws NullPointerException if {@code key} is {@code null}
     * @since 0.4.14
     */
    public @Nullable Object value(String key) {
        return values.get(Objects.requireNonNull(key, "key"));
    }

    /**
     * The text of a key that may be left out. A number and a boolean are given as {@link String#valueOf(Object)}
     * writes them. A list or a group of keys is the error {@code is a list; expected a single value} or
     * {@code is a group of keys; expected a single value}.
     *
     * @param key the key inside the entry
     * @return the text, or {@code null} when the key is absent or holds an error
     * @throws NullPointerException if {@code key} is {@code null}
     * @since 0.4.14
     */
    public @Nullable String text(String key) {
        Object value = value(key);
        if (value == null) {
            return null;
        }
        if (!scalar(value)) {
            error(key, "is " + ConditionRegistry.describe(value) + "; expected a single value", null);
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * The text of a key the entry must have, read as {@link #text(String)} reads it. An absent key and a blank text
     * are the error {@code is missing}.
     *
     * @param key the key inside the entry
     * @return the text, or {@code null} after an error
     * @throws NullPointerException if {@code key} is {@code null}
     * @since 0.4.14
     */
    public @Nullable String requiredText(String key) {
        if (value(key) == null) {
            error(key, "is missing", null);
            return null;
        }
        String text = text(key);
        if (text != null && text.isBlank()) {
            error(key, "is missing", null);
            return null;
        }
        return text;
    }

    /**
     * The texts of a key that holds a list, each read as {@link #text(String)} reads a single value. A value that is
     * not a list is the error {@code is <value>; expected a list}, and an entry of the list that is empty, a list or
     * a group of keys is an error at {@code <key>[<index>]}.
     *
     * @param key the key inside the entry
     * @return the texts in file order as an unmodifiable list, empty when the key is absent or holds an error
     * @throws NullPointerException if {@code key} is {@code null}
     * @since 0.4.14
     */
    public List<String> texts(String key) {
        Object value = value(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            error(key, "is " + ConditionRegistry.describe(value) + "; expected a list", null);
            return List.of();
        }
        List<String> texts = new ArrayList<>(list.size());
        boolean bad = false;
        int index = 0;
        for (Object entry : list) {
            String at = key + "[" + index++ + "]";
            if (entry == null || !scalar(entry)) {
                error(at, "is " + ConditionRegistry.describe(entry) + "; expected a single value", null);
                bad = true;
            } else {
                texts.add(String.valueOf(entry));
            }
        }
        return bad ? List.of() : List.copyOf(texts);
    }

    /**
     * Passes one error of the entry to the {@link ConditionProblems} given to {@link ConditionRegistry#build}, at
     * the path {@code <entry>.<key>}. An entry with an error is left out of the built list.
     *
     * @param key the key inside the entry that is wrong
     * @param message what is wrong, as it reads after the path, without a full stop at the end
     * @param fix what the owner has to do, without a full stop at the end, or {@code null}
     * @throws NullPointerException if {@code key} or {@code message} is {@code null}
     * @since 0.4.14
     */
    public void error(String key, String message, @Nullable String fix) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        errors++;
        problems.error(path + "." + key, message, fix);
    }

    /**
     * The denial of this entry: its {@code deny} text when it has one, and otherwise the message of {@code key}.
     *
     * @param key the message key of the type's default denial, such as {@code condition.permission}
     * @return the denial, for the condition to keep
     * @throws NullPointerException if {@code key} is {@code null}
     * @since 0.4.14
     */
    public ConditionDenial denial(String key) {
        return new ConditionDenial(deny, Objects.requireNonNull(key, "key"), denials);
    }

    int errors() {
        return errors;
    }

    void entryError(String message) {
        errors++;
        problems.error(path, message, null);
    }

    static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
