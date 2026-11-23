package so.alaz.taffy.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.UpdateCheck.Limits;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class UpdateCheckTest {

    private static final Executor DIRECT = Runnable::run;

    private record Received(String method, String target, Map<String, String> headers) {
    }

    private final CapturedLog log = new CapturedLog();
    private final Plugin plugin = mock(Plugin.class);
    private final PluginMeta meta = mock(PluginMeta.class);
    private final Server bukkit = mock(Server.class);
    private final ErrorReports errors = new ErrorReports();
    private final List<String> reported = new CopyOnWriteArrayList<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "[]";
    private volatile String location;
    private volatile boolean held;

    UpdateCheckTest() {
        when(plugin.getName()).thenReturn("Shop");
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.getServer()).thenReturn(bukkit);
        when(plugin.getLogger()).thenReturn(log.logger);
        when(meta.getVersion()).thenReturn("1.4.0");
        errors.install((source, error) -> reported.add(source + ": " + error));
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers));
        if (held) {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private UpdateSource github() {
        return UpdateSource.github("alaz", "shop").withBase(origin());
    }

    private UpdateSource modrinth() {
        return UpdateSource.modrinth("shop").withBase(origin());
    }

    private UpdateCheck check(UpdateSource source) {
        return UpdateCheck.start(plugin, errors, true, source, DIRECT);
    }

    private static String githubRelease(String tag, boolean preRelease, boolean draft) {
        return "{\"url\":\"https://api.github.com/x\",\"id\":7,\"author\":{\"login\":\"a\",\"site_admin\":true},"
            + "\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + preRelease
            + ",\"assets\":[{\"name\":\"shop.jar\",\"size\":12}],\"body\":\"notes\"}";
    }

    private static String modrinthVersion(String number, String type, String versionStatus) {
        return "{\"game_versions\":[\"26.3\"],\"loaders\":[\"paper\"],\"id\":\"abc\",\"version_number\":\""
            + number + "\",\"version_type\":\"" + type + "\",\"status\":\"" + versionStatus
            + "\",\"files\":[{\"hashes\":{\"sha1\":\"00\"},\"primary\":true}],\"downloads\":3}";
    }

    private static String list(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private List<String> lines(Level level) {
        return log.records.stream().filter(record -> record.getLevel() == level).map(LogRecord::getMessage).toList();
    }

    @Test
    void aSwitchedOffCheckSendsAndLogsNothing() {
        List<Runnable> submitted = new ArrayList<>();

        UpdateCheck check = UpdateCheck.start(plugin, errors, false, github(), submitted::add);

        assertThat(check.latest()).isNull();
        assertThat(submitted).isEmpty();
        assertThat(received).isEmpty();
        assertThat(log.records).isEmpty();
    }

    @Test
    void startOnlySubmitsTheRequestToTheExecutor() {
        body = list(githubRelease("v1.5.0", false, false));
        List<Runnable> submitted = new ArrayList<>();

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), submitted::add);

        assertThat(submitted).hasSize(1);
        assertThat(received).isEmpty();
        assertThat(check.latest()).isNull();
        submitted.get(0).run();
        assertThat(check.latest()).isEqualTo("1.5.0");
    }

    @Test
    void aStableBuildIsOfferedTheNewestReleaseOfAGithubRepository() {
        body = list(
            githubRelease("v2.0.0", false, true),
            githubRelease("v1.6.0-rc.1", true, false),
            githubRelease("nightly", false, false),
            githubRelease("v1.5.0", false, false),
            githubRelease("v1.4.0", false, false),
            githubRelease("v1.4.9", false, false));

        UpdateCheck check = check(github());

        assertThat(check.latest()).isEqualTo("1.5.0");
        assertThat(log.records).hasSize(1);
        assertThat(lines(Level.INFO)).containsExactly(
            "A newer release is available: 1.5.0 (running 1.4.0). https://github.com/alaz/shop/releases");
        assertThat(reported).isEmpty();
    }

    @Test
    void theRequestNamesThePluginAndNothingElse() {
        when(plugin.getName()).thenReturn("My Shop");

        check(github());

        assertThat(received).hasSize(1);
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.target()).isEqualTo("/repos/alaz/shop/releases?per_page=30");
        assertThat(request.headers()).containsEntry("User-Agent", "My_Shop/1.4.0")
            .containsEntry("Accept", "application/vnd.github+json")
            .containsEntry("X-GitHub-Api-Version", "2022-11-28");
        assertThat(request.headers().keySet()).allSatisfy(name -> assertThat(name)
            .isIn("Accept", "Cache-control", "Connection", "Host", "Pragma", "User-agent", "X-github-api-version"));
        assertThat(request.headers()).containsEntry("Cache-Control", "no-cache").containsEntry("Pragma", "no-cache");
    }

    @Test
    void aPreReleaseBuildIsOfferedPreReleasesToo() {
        when(meta.getVersion()).thenReturn("1.6.0-SNAPSHOT");
        body = list(githubRelease("v1.6.0-rc.1", true, false), githubRelease("v1.5.0", false, false));

        assertThat(check(github()).latest()).isEqualTo("1.6.0-rc.1");
        assertThat(lines(Level.INFO)).containsExactly("A newer release is available: 1.6.0-rc.1"
            + " (running 1.6.0-SNAPSHOT). https://github.com/alaz/shop/releases");
    }

    @Test
    void aPreReleaseBuildIsOfferedTheReleaseOfItsOwnVersion() {
        when(meta.getVersion()).thenReturn("1.5.0-rc.2");
        body = list(githubRelease("v1.5.0", false, false), githubRelease("v1.5.0-rc.2", true, false));

        assertThat(check(github()).latest()).isEqualTo("1.5.0");
    }

    @Test
    void nothingIsLoggedWhenNoNewerReleaseIsPublished() {
        body = list(githubRelease("v1.4.0", false, false), githubRelease("v1.3.0", false, false),
            githubRelease("v1.5.0-beta.1", true, false));

        assertThat(check(github()).latest()).isNull();
        body = "[]";
        assertThat(check(github()).latest()).isNull();
        assertThat(received).hasSize(2);
        assertThat(log.records).isEmpty();
    }

    @Test
    void modrinthVersionsAreReadByTheirTypeAndStatus() {
        body = list(
            modrinthVersion("3.0.0", "release", "draft"),
            modrinthVersion("2.0.0", "beta", "listed"),
            modrinthVersion("1.5.0-paper", "release", "listed"),
            modrinthVersion("1.4.2+build.9", "release", "listed"));

        UpdateCheck check = check(modrinth());

        assertThat(check.latest()).isEqualTo("1.5.0-paper");
        assertThat(lines(Level.INFO)).containsExactly("A newer release is available: 1.5.0-paper (running 1.4.0)."
            + " https://modrinth.com/project/shop/versions");
        assertThat(received.get(0).target()).isEqualTo("/v2/project/shop/version?include_changelog=false");
        assertThat(received.get(0).headers()).containsEntry("Accept", "application/json")
            .containsEntry("User-Agent", "Shop/1.4.0");
    }

    @Test
    void aVersionThatIsNotSemanticStopsTheCheckBeforeAnyRequest() {
        when(meta.getVersion()).thenReturn("build-17");
        List<Runnable> submitted = new ArrayList<>();

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), submitted::add);

        assertThat(check.latest()).isNull();
        assertThat(submitted).isEmpty();
        assertThat(log.records).hasSize(1);
        assertThat(lines(Level.WARNING)).containsExactly(
            "Update check skipped: the plugin's version build-17 is not a semantic version such as 1.4.0");
    }

    @Test
    void anAnswerOtherThan200IsOneWarning() {
        status = 404;
        body = "{\"message\":\"Not Found\"}";

        assertThat(check(github()).latest()).isNull();
        assertThat(log.records).hasSize(1);
        assertThat(lines(Level.WARNING)).containsExactly("Update check failed: 127.0.0.1 answered with status 404");
        assertThat(reported).isEmpty();
    }

    @Test
    void aRedirectIsNotFollowed() {
        status = 302;
        body = "";
        location = origin() + "/elsewhere";

        assertThat(check(github()).latest()).isNull();
        assertThat(received).extracting(Received::target).containsExactly("/repos/alaz/shop/releases?per_page=30");
        assertThat(lines(Level.WARNING)).containsExactly("Update check failed: 127.0.0.1 answered with status 302");
    }

    @Test
    void anAnswerThatIsNoListOfReleasesIsOneWarning() {
        for (String answer : List.of("{\"tag_name\":\"v9.0.0\"}", "[\"v9.0.0\"]", "<html>", "[{\"tag_name\":")) {
            log.records.clear();
            body = answer;

            assertThat(check(github()).latest()).as(answer).isNull();
            assertThat(log.records).hasSize(1);
            assertThat(lines(Level.WARNING)).as(answer).containsExactly(
                "Update check failed: the answer of 127.0.0.1 is not a list of releases");
        }
        assertThat(reported).isEmpty();
    }

    @Test
    void anAnswerLongerThanTheLimitIsReadUpToItsLastCompleteEntry() {
        String first = githubRelease("v1.5.0", false, false);
        body = list(first, githubRelease("v9.0.0", false, false));
        Limits limits = new Limits(10_000, 10_000, 30_000, first.length() + 20, 100);

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), DIRECT, limits);

        assertThat(check.latest()).isEqualTo("1.5.0");
        assertThat(lines(Level.WARNING)).isEmpty();
    }

    @Test
    void anAnswerWithNoCompleteEntryWithinTheLimitIsOneWarning() {
        body = list(githubRelease("v9.0.0", false, false));
        Limits limits = new Limits(10_000, 10_000, 30_000, 40, 100);

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), DIRECT, limits);

        assertThat(check.latest()).isNull();
        assertThat(lines(Level.WARNING)).containsExactly(
            "Update check failed: the answer of 127.0.0.1 is not a list of releases");
    }

    @Test
    void entriesPastTheEntryLimitAreNotRead() {
        body = list(githubRelease("v1.5.0", false, true), githubRelease("v1.6.0", false, false),
            githubRelease("v9.0.0", false, false));
        Limits limits = new Limits(10_000, 10_000, 30_000, 262_144, 2);

        assertThat(UpdateCheck.start(plugin, errors, true, github(), DIRECT, limits).latest()).isEqualTo("1.6.0");
    }

    @Test
    void theDefaultLimitsBoundTheTimeAndTheSize() {
        assertThat(Limits.DEFAULT).isEqualTo(new Limits(10_000, 10_000, 30_000, 262_144, 100));
    }

    @Test
    void anAnswerThatDoesNotComeWithinTheReadTimeoutIsOneWarning() {
        held = true;
        body = list(githubRelease("v9.0.0", false, false));
        Limits limits = new Limits(10_000, 200, 30_000, 262_144, 100);

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), DIRECT, limits);

        assertThat(check.latest()).isNull();
        assertThat(log.records).hasSize(1);
        assertThat(lines(Level.WARNING)).containsExactly(
            "Update check failed: could not read from 127.0.0.1 (java.net.SocketTimeoutException)");
        assertThat(reported).isEmpty();
    }

    @Test
    void anAnswerPastTheTotalTimeIsOneWarning() {
        body = list(githubRelease("v9.0.0", false, false));
        Limits limits = new Limits(10_000, 10_000, 0, 262_144, 100);

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), DIRECT, limits);

        assertThat(check.latest()).isNull();
        assertThat(lines(Level.WARNING)).containsExactly(
            "Update check failed: could not read from 127.0.0.1 (java.net.SocketTimeoutException)");
    }

    @Test
    void headersThatDripPastTheTotalTimeEndTheRequest() throws IOException {
        byte[] head = ("HTTP/1.1 200 OK\r\nX-Pad: " + "x".repeat(200) + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        try (ServerSocket dripping = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread drip = new Thread(() -> {
                try (Socket socket = dripping.accept()) {
                    for (byte one : head) {
                        socket.getOutputStream().write(one);
                        socket.getOutputStream().flush();
                        Thread.sleep(50);
                    }
                } catch (IOException | InterruptedException ended) {
                    Thread.currentThread().interrupt();
                }
            });
            drip.setDaemon(true);
            drip.start();
            UpdateSource source = UpdateSource.github("alaz", "shop")
                .withBase("http://127.0.0.1:" + dripping.getLocalPort());
            Limits limits = new Limits(10_000, 10_000, 400, 262_144, 100);
            long began = System.nanoTime();

            UpdateCheck check = UpdateCheck.start(plugin, errors, true, source, DIRECT, limits);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).isLessThan(5_000);
            assertThat(check.latest()).isNull();
            assertThat(log.records).hasSize(1);
            assertThat(lines(Level.WARNING)).containsExactly(
                "Update check failed: could not read from 127.0.0.1 (java.net.SocketTimeoutException)");
            assertThat(reported).isEmpty();
        }
    }

    @Test
    void aServerThatCannotBeReachedIsOneWarning() {
        UpdateSource source = github();
        server.stop(0);

        assertThat(check(source).latest()).isNull();
        assertThat(log.records).hasSize(1);
        assertThat(lines(Level.WARNING)).containsExactly(
            "Update check failed: could not read from 127.0.0.1 (java.net.ConnectException)");
        assertThat(reported).isEmpty();
    }

    @Test
    void anExecutorThatRefusesTheTaskIsOneWarningAndNothingThrown() {
        Executor refusing = task -> {
            throw new RejectedExecutionException("closed");
        };

        UpdateCheck check = UpdateCheck.start(plugin, errors, true, github(), refusing);

        assertThat(check.latest()).isNull();
        assertThat(received).isEmpty();
        assertThat(lines(Level.WARNING)).containsExactly("Update check skipped: the executor refused it"
            + " (java.util.concurrent.RejectedExecutionException)");
    }

    @Test
    void nothingIsSentFromAServerThread() {
        when(bukkit.isPrimaryThread()).thenReturn(true);
        body = list(githubRelease("v9.0.0", false, false));

        assertThat(check(github()).latest()).isNull();
        assertThat(received).isEmpty();
        assertThat(lines(Level.WARNING)).containsExactly("Update check skipped: the executor ran it on a server"
            + " thread, and the request waits on the network");
    }

    @Test
    void anUnexpectedExceptionIsReportedAndNotThrown() {
        when(bukkit.isPrimaryThread()).thenThrow(new IllegalStateException("boom"));

        assertThat(check(github()).latest()).isNull();
        assertThat(lines(Level.WARNING)).containsExactly("Update check failed: java.lang.IllegalStateException");
        assertThat(reported).containsExactly("update-check: java.lang.IllegalStateException: boom");
    }

    @Test
    void startRefusesNull() {
        UpdateSource source = github();

        assertThatThrownBy(() -> UpdateCheck.start(null, errors, true, source, DIRECT))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> UpdateCheck.start(plugin, null, true, source, DIRECT))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> UpdateCheck.start(plugin, errors, false, null, DIRECT))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("source");
        assertThatThrownBy(() -> UpdateCheck.start(plugin, errors, false, source, null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("executor");
    }
}
