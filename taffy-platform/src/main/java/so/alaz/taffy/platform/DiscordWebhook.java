package so.alaz.taffy.platform;

import com.google.gson.Gson;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Posts messages to Discord webhooks over HTTP, asynchronously, through one client. Failures are not
 * thrown. Each is passed to the caller's callback as a one-line reason for the server owner's log,
 * with no Throwable, and nothing is reported to {@link ErrorReports}. The callback runs either on the
 * calling thread before the post method returns, as it does for a URL that does not parse and for a
 * post after {@link #close()}, or later on a thread the caller does not choose. A callback for a
 * request that has already finished can still run after {@link #close()} has returned. The callback
 * runs at most once per post. It must not throw: what it throws for a request that was sent is
 * discarded, and for a URL that does not parse it reaches the caller of the post method.
 *
 * <p>Neither post method checks that the URL points at Discord. {@link #isWebhookUrl(String)} does,
 * for a caller to apply when it reads the URL from config.
 */
public final class DiscordWebhook {

    /** Discord caps a webhook message at 2000 characters. */
    private static final int MAX_CONTENT = 2000;

    /** A Discord webhook URL, allowing the canary/ptb hosts, the legacy discordapp.com, and an API version. */
    private static final Pattern WEBHOOK_URL = Pattern.compile(
        "https://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/\\S+");

    private static final Gson GSON = new Gson();

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Whether {@code url} is a recognised Discord webhook URL: {@code https}, the host
     * {@code discord.com} or {@code discordapp.com} with an optional {@code canary.} or {@code ptb.}
     * prefix, an optional API version, a numeric webhook id and a token. {@code null} is not.
     */
    public static boolean isWebhookUrl(@Nullable String url) {
        return url != null && WEBHOOK_URL.matcher(url).matches();
    }

    /**
     * Posts a plain message to {@code url} as the webhook {@code content}, asynchronously. Content
     * over 2000 characters is cut to fit.
     */
    public void post(String url, String content, Consumer<String> onError) {
        postBody(url, GSON.toJson(Map.of("content", truncate(content))), onError);
    }

    /**
     * Posts a ready JSON {@code body}, such as one {@link WebhookPayload#render} built, to {@code url}
     * asynchronously; {@code onError} gets a reason on failure.
     */
    public void postBody(String url, String body, Consumer<String> onError) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        } catch (IllegalArgumentException ex) {
            onError.accept("invalid webhook URL");
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .whenComplete((response, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException c && c.getCause() != null
                        ? c.getCause() : failure;
                    onError.accept(cause.toString());
                } else if (response.statusCode() >= 300) {
                    onError.accept("Discord returned HTTP " + response.statusCode());
                }
            });
    }

    /**
     * Closes the client and its executor. Blocks the calling thread until every in-flight request
     * has finished, so this is meant to be the last step of a shutdown. A request fails when no
     * response headers arrive within 15 seconds, which is not an upper bound on this call: a server
     * that sends its headers and then stalls can keep it blocked for longer.
     *
     * <p>An interrupt, also one already set on the calling thread when this is called, ends the wait:
     * the in-flight requests are then aborted and fail through their callbacks, and the interrupt stays
     * set.
     */
    public void close() {
        client.close();
    }

    private static String truncate(String content) {
        if (content.length() <= MAX_CONTENT) {
            return content;
        }
        // Back off by one if the cut would land between a surrogate pair, leaving a lone surrogate.
        int end = Character.isHighSurrogate(content.charAt(MAX_CONTENT - 1)) ? MAX_CONTENT - 1 : MAX_CONTENT;
        return content.substring(0, end);
    }
}
