package so.alaz.taffy.platform;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Renders a Discord webhook payload template (a nested map of strings, numbers, booleans, lists, and
 * maps, as read from config) into a JSON body. Every string value is passed through {@code resolve}
 * (the caller's value substitution) first; the structure is then serialized with Gson, so a resolved
 * value cannot change the JSON structure. The body is in the form {@link DiscordWebhook#postBody} sends.
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
     */
    public static String render(Map<String, Object> template, UnaryOperator<String> resolve) {
        Map<String, Object> out = new LinkedHashMap<>();
        template.forEach((key, child) -> out.put(String.valueOf(key), resolveValue(child, resolve)));
        if (!out.containsKey(ALLOWED_MENTIONS)) {
            out.put(ALLOWED_MENTIONS, Map.of("parse", List.of()));
        }
        return GSON.toJson(out);
    }

    private static Object resolveValue(Object value, UnaryOperator<String> resolve) {
        if (value instanceof String string) {
            return resolve.apply(string);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, child) -> out.put(String.valueOf(key), resolveValue(child, resolve)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolveValue(item, resolve));
            }
            return out;
        }
        return value;
    }
}
