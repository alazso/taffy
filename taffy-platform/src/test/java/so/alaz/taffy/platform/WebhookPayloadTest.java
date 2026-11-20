package so.alaz.taffy.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadTest {

    @Test
    void resolvesStringsAndPreservesStructureAndTypes() {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "Rare!");
        embed.put("description", "%player% won");
        embed.put("color", 16766720);
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("username", "Server Bot");
        template.put("content", "hi %player%");
        template.put("embeds", List.of(embed));

        String json = WebhookPayload.render(template, s -> s.replace("%player%", "Steve"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThat(root.get("username").getAsString()).isEqualTo("Server Bot");
        assertThat(root.get("content").getAsString()).isEqualTo("hi Steve");
        JsonObject parsedEmbed = root.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertThat(parsedEmbed.get("description").getAsString()).isEqualTo("Steve won");
        assertThat(parsedEmbed.get("color").getAsInt()).isEqualTo(16766720);
    }

    @Test
    void escapesResolvedValuesSoTheyCannotInjectJson() {
        String injected = "evil\",\"x\":\"y";
        Map<String, Object> template = Map.of("content", "%arg%");

        String json = WebhookPayload.render(template, s -> s.replace("%arg%", injected));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThat(root.get("content").getAsString()).isEqualTo(injected);
        assertThat(root.has("x")).isFalse();
        assertThat(root.keySet()).containsExactly("content", "allowed_mentions");
    }

    @Test
    void addsAllowedMentionsWithAnEmptyParseListWhenTheTemplateHasNone() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("content", "%arg%");

        String json = WebhookPayload.render(template, s -> s.replace("%arg%", "@everyone"));

        assertThat(json).isEqualTo("{\"content\":\"@everyone\",\"allowed_mentions\":{\"parse\":[]}}");
        assertThat(template).containsOnlyKeys("content");
    }

    @Test
    void keepsTheAllowedMentionsOfTheTemplate() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("content", "hi");
        template.put("allowed_mentions", Map.of("parse", List.of("users", "roles")));

        JsonObject root = JsonParser.parseString(WebhookPayload.render(template, s -> s)).getAsJsonObject();

        assertThat(root.keySet()).containsExactly("content", "allowed_mentions");
        assertThat(root.getAsJsonObject("allowed_mentions").toString())
            .isEqualTo("{\"parse\":[\"users\",\"roles\"]}");
    }

    @Test
    void keepsAnAllowedMentionsValueThatAllowsEverything() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("allowed_mentions", Map.of());
        template.put("content", "hi");

        assertThat(WebhookPayload.render(template, s -> s))
            .isEqualTo("{\"allowed_mentions\":{},\"content\":\"hi\"}");
    }

    @Test
    void anAllowedMentionsKeyBelowTheTopLevelDoesNotCount() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embeds", List.of(Map.of("allowed_mentions", "x")));

        JsonObject root = JsonParser.parseString(WebhookPayload.render(template, s -> s)).getAsJsonObject();

        assertThat(root.getAsJsonObject("allowed_mentions").toString()).isEqualTo("{\"parse\":[]}");
    }
}
