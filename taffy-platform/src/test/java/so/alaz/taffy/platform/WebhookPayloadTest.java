package so.alaz.taffy.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aNullAllowedMentionsCountsAsNoKey() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("allowed_mentions", null);
        template.put("content", "%arg%");

        String json = WebhookPayload.render(template, s -> s.replace("%arg%", "@everyone"));

        assertThat(json).isEqualTo("{\"content\":\"@everyone\",\"allowed_mentions\":{\"parse\":[]}}");
    }

    @Test
    void refusesANumberThatIsNotFiniteAndNamesItsKeyPath() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embeds", List.of(Map.of("color", Double.NaN)));

        assertThatThrownBy(() -> WebhookPayload.render(template, s -> s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("embeds[0].color is NaN");
        assertThatThrownBy(() -> WebhookPayload.render(Map.of("x", Float.POSITIVE_INFINITY), s -> s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("x is Infinity");
    }

    @Test
    void anAllowedMentionsKeyBelowTheTopLevelDoesNotCount() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embeds", List.of(Map.of("allowed_mentions", "x")));

        JsonObject root = JsonParser.parseString(WebhookPayload.render(template, s -> s)).getAsJsonObject();

        assertThat(root.getAsJsonObject("allowed_mentions").toString()).isEqualTo("{\"parse\":[]}");
    }

    @Test
    void readsAConfigurationSectionAsTheMapOfItsKeys() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
            payload:
              username: Shop
              thumbnail:
                url: 'https://example.com/%player%.png'
              embeds:
                - title: '%player% won'
                  color: 5814783
                  footer:
                    text: bye
            """);
        Map<String, Object> template = config.getConfigurationSection("payload").getValues(false);
        assertThat(template.get("thumbnail")).isInstanceOf(ConfigurationSection.class);

        String json = WebhookPayload.render(template, s -> s.replace("%player%", "Steve"));

        assertThat(json).isEqualTo("{\"username\":\"Shop\","
            + "\"thumbnail\":{\"url\":\"https://example.com/Steve.png\"},"
            + "\"embeds\":[{\"title\":\"Steve won\",\"color\":5814783,\"footer\":{\"text\":\"bye\"}}],"
            + "\"allowed_mentions\":{\"parse\":[]}}");
    }

    @Test
    void readsAConfigurationSectionInsideAListAndASection() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("outer.inner.text", "%arg%");
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embeds", List.of(config.getConfigurationSection("outer")));
        template.put("allowed_mentions", config.createSection("mentions", Map.of("parse", List.of("users"))));

        String json = WebhookPayload.render(template, s -> s.replace("%arg%", "hi"));

        assertThat(json).isEqualTo("{\"embeds\":[{\"inner\":{\"text\":\"hi\"}}],"
            + "\"allowed_mentions\":{\"parse\":[\"users\"]}}");
    }

    @Test
    void passesNumbersBooleansAndNullThrough() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("tts", false);
        template.put("flags", 4096L);
        template.put("ratio", 0.5);
        template.put("avatar_url", null);
        template.put("list", Arrays.asList(1, null, true));

        String json = WebhookPayload.render(template, s -> {
            throw new AssertionError("no string to resolve");
        });

        assertThat(json).isEqualTo("{\"tts\":false,\"flags\":4096,\"ratio\":0.5,\"list\":[1,null,true],"
            + "\"allowed_mentions\":{\"parse\":[]}}");
    }

    @Test
    void refusesAnyOtherTypeAndNamesItsKeyPath() {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", "hi");
        embed.put("timestamp", Instant.EPOCH);
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embeds", List.of(Map.of("title", "first"), embed));

        assertThatThrownBy(() -> WebhookPayload.render(template, s -> s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Webhook payload value at embeds[1].timestamp is a java.time.Instant; a value must be a"
                + " String, Number, Boolean, null, Map, List or ConfigurationSection");
    }

    @Test
    void namesTheKeyPathThroughAConfigurationSection() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("author.icon", 'x');
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("embed", config);
        template.put("top", new String[] {"a"});

        assertThatThrownBy(() -> WebhookPayload.render(template, s -> s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Webhook payload value at embed.author.icon is a java.lang.Character;");
        template.remove("embed");
        assertThatThrownBy(() -> WebhookPayload.render(template, s -> s))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Webhook payload value at top is a [Ljava.lang.String;;");
    }
}
