package so.alaz.taffy.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DiscordWebhookTest {

    private static final long WAIT_SECONDS = 10;

    private record Received(String method, String contentType, String body) {
    }

    private final DiscordWebhook webhook = new DiscordWebhook();
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        webhook.close();
        server.stop(0);
    }

    /** Answers with the status that ends the request path; under {@code /held/}, only once {@link #release} opens. */
    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(),
            exchange.getRequestHeaders().getFirst("Content-Type"), body));
        if (path.startsWith("/held/")) {
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.sendResponseHeaders(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)), -1);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private Received nextRequest() throws InterruptedException {
        Received request = received.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(request).as("a request within %d seconds", WAIT_SECONDS).isNotNull();
        return request;
    }

    private static String content(Received request) {
        return JsonParser.parseString(request.body()).getAsJsonObject().get("content").getAsString();
    }

    private static JsonObject json(Received request) {
        return JsonParser.parseString(request.body()).getAsJsonObject();
    }

    private static List<String> allowedParse(Received request) {
        JsonObject allowed = json(request).getAsJsonObject("allowed_mentions");
        assertThat(allowed.keySet()).containsExactly("parse");
        return allowed.getAsJsonArray("parse").asList().stream().map(JsonElement::getAsString).toList();
    }

    private String errorFrom(String url) throws Exception {
        CompletableFuture<String> error = new CompletableFuture<>();
        webhook.post(url, "hi", error::complete);
        return error.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void postSendsTheContentAsAJsonObjectInAPostRequest() throws Exception {
        String content = "say \"hi\", caf\u00E9 \uD83D\uDE00";

        webhook.post(url("/204"), content, errors::add);

        Received request = nextRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(json(request).keySet()).containsExactly("content", "allowed_mentions");
        assertThat(content(request)).isEqualTo(content);
    }

    @Test
    void postSendsAllowedMentionsWithAnEmptyParseList() throws Exception {
        webhook.post(url("/204"), "@everyone <@&1> <@2> free nitro", errors::add);

        Received request = nextRequest();
        assertThat(request.body()).contains("\"allowed_mentions\":{\"parse\":[]}");
        assertThat(allowedParse(request)).isEmpty();
        assertThat(content(request)).isEqualTo("@everyone <@&1> <@2> free nitro");
    }

    @Test
    void postWithNoMentionsOptionsSendsAnEmptyParseList() throws Exception {
        webhook.post(url("/204"), "@here", DiscordWebhook.PostOptions.noMentions(), errors::add);

        assertThat(allowedParse(nextRequest())).isEmpty();
    }

    @Test
    void postWithOptionsAllowsTheNamedMentionTypes() throws Exception {
        webhook.post(url("/204"), "<@&1> restock",
            DiscordWebhook.PostOptions.allowing(DiscordWebhook.Mention.ROLES), errors::add);
        assertThat(allowedParse(nextRequest())).containsExactly("roles");

        webhook.post(url("/204"), "<@2> won", new DiscordWebhook.PostOptions(
            Set.of(DiscordWebhook.Mention.USERS, DiscordWebhook.Mention.EVERYONE)), errors::add);
        Received request = nextRequest();
        assertThat(allowedParse(request)).containsExactly("everyone", "users");
        assertThat(content(request)).isEqualTo("<@2> won");
    }

    @Test
    void postWithEveryMentionTypeListsThemInDiscordOrder() throws Exception {
        webhook.post(url("/204"), "hi",
            DiscordWebhook.PostOptions.allowing(DiscordWebhook.Mention.values()), errors::add);

        assertThat(allowedParse(nextRequest())).containsExactly("everyone", "roles", "users");
    }

    @Test
    void postOptionsCopyTheirMentionSet() {
        Set<DiscordWebhook.Mention> mentions = new HashSet<>(Set.of(DiscordWebhook.Mention.ROLES));
        DiscordWebhook.PostOptions options = new DiscordWebhook.PostOptions(mentions);

        mentions.add(DiscordWebhook.Mention.EVERYONE);

        assertThat(options.mentions()).containsExactly(DiscordWebhook.Mention.ROLES);
        assertThat(DiscordWebhook.PostOptions.noMentions().mentions()).isEmpty();
    }

    @Test
    void aRenderedTemplateWithoutAllowedMentionsIsPostedWithAnEmptyParseList() throws Exception {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("content", "{reason}");

        webhook.postBody(url("/204"), WebhookPayload.render(template, s -> s.replace("{reason}", "@everyone")),
            errors::add);

        Received request = nextRequest();
        assertThat(json(request).keySet()).containsExactly("content", "allowed_mentions");
        assertThat(allowedParse(request)).isEmpty();
        assertThat(content(request)).isEqualTo("@everyone");
    }

    @Test
    void aRenderedTemplateKeepsItsOwnAllowedMentions() throws Exception {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("allowed_mentions", Map.of("parse", List.of("roles")));
        template.put("content", "<@&1> {reason}");

        webhook.postBody(url("/204"), WebhookPayload.render(template, s -> s.replace("{reason}", "restock")),
            errors::add);

        Received request = nextRequest();
        assertThat(json(request).keySet()).containsExactly("allowed_mentions", "content");
        assertThat(allowedParse(request)).containsExactly("roles");
    }

    @Test
    void postBodyAddsNoAllowedMentions() throws Exception {
        webhook.postBody(url("/204"), "{\"content\":\"@everyone\"}", errors::add);

        Received request = nextRequest();
        assertThat(request.body()).isEqualTo("{\"content\":\"@everyone\"}");
        assertThat(json(request).has("allowed_mentions")).isFalse();
    }

    @Test
    void postBodySendsTheBodyUnchangedAsUtf8() throws Exception {
        String body = "{\"username\":\"caf\u00E9\",\"embeds\":[]}";

        webhook.postBody(url("/204"), body, errors::add);

        Received request = nextRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo(body);
    }

    @Test
    void anAnswerBelow300ReportsNothing() throws Exception {
        webhook.post(url("/204"), "first", errors::add);
        nextRequest();
        webhook.post(url("/299"), "second", errors::add);
        nextRequest();

        CompletableFuture<String> last = new CompletableFuture<>();
        webhook.post(url("/500"), "third", error -> {
            errors.add(error);
            last.complete(error);
        });
        last.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(errors).containsExactly("Discord returned HTTP 500");
    }

    @Test
    void anAnswerOf300OrAboveReportsTheStatus() throws Exception {
        assertThat(errorFrom(url("/300"))).isEqualTo("Discord returned HTTP 300");
        assertThat(errorFrom(url("/404"))).isEqualTo("Discord returned HTTP 404");
        assertThat(errorFrom(url("/503"))).isEqualTo("Discord returned HTTP 503");
    }

    @Test
    void aUrlThatDoesNotParseIsReportedBeforeThePostReturns() {
        webhook.post("not a url", "hi", errors::add);
        webhook.post("127.0.0.1/204", "hi", errors::add);
        webhook.postBody("ftp://127.0.0.1/204", "{}", errors::add);

        assertThat(errors).containsExactly("invalid webhook URL", "invalid webhook URL", "invalid webhook URL");
        assertThat(received).isEmpty();
    }

    @Test
    void aRefusedConnectionIsReported() throws Exception {
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        closed.start();
        int port = closed.getAddress().getPort();
        closed.stop(0);

        String error = errorFrom("http://127.0.0.1:" + port + "/204");

        assertThat(error).startsWith("java.net.ConnectException");
        assertThat(received).isEmpty();
    }

    @Test
    void aCallbackThatThrowsForAStatusIsCalledOnce() throws Exception {
        List<String> reasons = new CopyOnWriteArrayList<>();
        CountDownLatch called = new CountDownLatch(1);

        webhook.post(url("/500"), "hi", reason -> {
            reasons.add(reason);
            called.countDown();
            throw new RuntimeException("callback failed");
        });
        assertThat(called.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        nextRequest();
        webhook.post(url("/204"), "barrier", errors::add);
        nextRequest();

        assertThat(reasons).containsExactly("Discord returned HTTP 500");
    }

    @Test
    void contentOfExactly2000CharactersIsSentWhole() throws Exception {
        String content = "a".repeat(2000);

        webhook.post(url("/204"), content, errors::add);

        assertThat(content(nextRequest())).isEqualTo(content);
    }

    @Test
    void contentOver2000CharactersIsCutTo2000() throws Exception {
        webhook.post(url("/204"), "a".repeat(1999) + "bc", errors::add);

        assertThat(content(nextRequest())).isEqualTo("a".repeat(1999) + "b");
    }

    @Test
    void aCutThatWouldSplitASurrogatePairStopsOneCharacterEarlier() throws Exception {
        webhook.post(url("/204"), "a".repeat(1999) + "\uD83D\uDE00" + "b", errors::add);

        assertThat(content(nextRequest())).isEqualTo("a".repeat(1999));
    }

    @Test
    void aSurrogatePairThatEndsAtTheLimitIsKept() throws Exception {
        webhook.post(url("/204"), "a".repeat(1998) + "\uD83D\uDE00" + "b", errors::add);

        assertThat(content(nextRequest())).isEqualTo("a".repeat(1998) + "\uD83D\uDE00");
    }

    @Test
    void closeBlocksUntilAnInFlightRequestIsAnswered() throws Exception {
        webhook.post(url("/held/204"), "hi", errors::add);
        nextRequest();

        CompletableFuture<Void> closed = CompletableFuture.runAsync(webhook::close);

        assertThatThrownBy(() -> closed.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(errors).isEmpty();
    }

    @Test
    void aPostAfterCloseIsReportedBeforeThePostReturns() {
        webhook.close();

        webhook.post(url("/204"), "hi", errors::add);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).startsWith("java.io.IOException").contains("closed");
        assertThat(received).isEmpty();
    }

    @Test
    void recognisesAWebhookUrl() {
        assertThat(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/123/abc-DEF_4")).isTrue();
    }

    @Test
    void acceptsCanaryPtbAndVersionedAndLegacyHosts() {
        assertThat(DiscordWebhook.isWebhookUrl("https://canary.discord.com/api/v10/webhooks/1/tok")).isTrue();
        assertThat(DiscordWebhook.isWebhookUrl("https://ptb.discord.com/api/webhooks/1/tok")).isTrue();
        assertThat(DiscordWebhook.isWebhookUrl("https://discordapp.com/api/webhooks/1/tok")).isTrue();
        assertThat(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/1/tok?wait=true")).isTrue();
    }

    @Test
    void rejectsNonDiscordUrl() {
        assertThat(DiscordWebhook.isWebhookUrl("https://evil.example.com/api/webhooks/1/t")).isFalse();
        assertThat(DiscordWebhook.isWebhookUrl("http://discord.com/api/webhooks/1/tok")).isFalse();
        assertThat(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/1/tok hi")).isFalse();
        assertThat(DiscordWebhook.isWebhookUrl(null)).isFalse();
    }
}
