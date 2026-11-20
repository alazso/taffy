package so.alaz.taffy.platform;

import com.google.gson.Gson;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Renders a Discord webhook payload template (a nested map of strings, numbers, booleans, lists, and
 * maps, as read from config) into a JSON body. A value is a {@code String}, a {@code Number}, a
 * {@code Boolean}, {@code null}, a {@code Map}, a {@code List} or a Bukkit {@code ConfigurationSection},
 * which is read as the map of its own keys. Any other type is refused. Every string value is passed
 * through {@code resolve} (the caller's value substitution) first; the structure is then serialized
 * with Gson, so a resolved value cannot change the JSON structure. The body is in the form
 * {@link DiscordWebhook#postBody} sends.
 *
 * <p>A template with no {@code allowed_mentions} key at its top level is rendered with
 * {@code "allowed_mentions": {"parse": []}} added, so no mention in a resolved value pings. A template
 * that has the key keeps its own value, whatever it is, which is how a server owner allows mentions.
 */
public final class WebhookPayload {

    private static final Gson GSON = new Gson();

    private static final String ALLOWED_MENTIONS = "allowed_mentions";

    private WebhookPayload() {
    }

    /**
     * Resolves every string in {@code template} via {@code resolve}, then serializes to a JSON object.
     * When the template has no top-level {@code allowed_mentions} key, the object ends with
     * {@code "allowed_mentions": {"parse": []}}. The template is not changed.
     *
     * @throws IllegalArgumentException when a value is of a type the class documentation does not list;
     *     the message names the value by its key path, such as {@code embeds[0].timestamp}
     */
    public static String render(Map<String, Object> template, UnaryOperator<String> resolve) {
        Map<String, Object> out = resolveMap(template, "", resolve);
        if (!out.containsKey(ALLOWED_MENTIONS)) {
            out.put(ALLOWED_MENTIONS, Map.of("parse", List.of()));
        }
        return GSON.toJson(out);
    }

    private static Object resolveValue(Object value, String path, UnaryOperator<String> resolve) {
        if (value instanceof String string) {
            return resolve.apply(string);
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof ConfigurationSection section) {
            return resolveMap(section.getValues(false), path, resolve);
        }
        if (value instanceof Map<?, ?> map) {
            return resolveMap(map, path, resolve);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                out.add(resolveValue(list.get(index), path + "[" + index + "]", resolve));
            }
            return out;
        }
        throw new IllegalArgumentException("Webhook payload value at " + path + " is a "
            + value.getClass().getName() + "; a value must be a String, Number, Boolean, null, Map, List"
            + " or ConfigurationSection");
    }

    private static Map<String, Object> resolveMap(Map<?, ?> map, String path, UnaryOperator<String> resolve) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, child) -> {
            String name = String.valueOf(key);
            out.put(name, resolveValue(child, path.isEmpty() ? name : path + "." + name, resolve));
        });
        return out;
    }
}
