package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import dev.faststats.bukkit.BukkitContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import so.alaz.taffy.test.RaceRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;

/**
 * Reports go through a real FastStats context over {@link MetricsFixture}, whose global tracker is context-unaware
 * so no handler is attached. The context sends its errors to {@link LoopbackSink} inside {@code shutdown()}.
 */
class SourceTrackersTest {

    @TempDir
    Path plugins;

    private LoopbackSink sink;
    private MetricsFixture fixture;
    private BukkitContext context;
    private SourceTrackers trackers;

    @BeforeEach
    void setUp() throws IOException {
        System.clearProperty("faststats.first-run");
        sink = new LoopbackSink();
        sink.point();
        fixture = new MetricsFixture(plugins);
        fixture.writeFastStatsConfig(true, true, true);
        context = new BukkitContext.Factory(fixture.plugin, MetricsFixture.FASTSTATS_TOKEN)
            .errorTrackerService(ErrorTracker.contextUnaware())
            .create();
        context.ready();
        trackers = new SourceTrackers(context.errorTrackerService().orElseThrow(), fixture.log.logger);
    }

    @AfterEach
    void tearDown() {
        context.shutdown();
        sink.close();
        System.clearProperty("faststats.first-run");
    }

    @Test
    void theSameErrorFromOneSourceMergesIntoOneErrorWithItsCount() {
        IllegalStateException error = new IllegalStateException("storage offline");

        for (int i = 0; i < 3; i++) {
            trackers.report("storage", error, true);
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(1);
        assertThat(body).contains("\"count\":3").contains("\"source\":\"storage\"").contains("\"handled\":true");
    }

    @Test
    void twoSourcesGiveTwoErrorsEachWithItsSource() {
        IllegalStateException error = new IllegalStateException("storage offline");

        trackers.report("storage", error, true);
        trackers.report("shutdown", error, true);

        String body = errorBody();
        assertThat(count(body, "\"source\":\"")).isEqualTo(2);
        assertThat(body).contains("\"source\":\"storage\"").contains("\"source\":\"shutdown\"")
            .doesNotContain("\"count\"");
    }

    @Test
    void sourcesPastTheBoundGoToTheGlobalTrackerWithoutASource() {
        IllegalStateException error = new IllegalStateException("storage offline");

        for (int i = 0; i <= SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error, true);
        }

        assertThat(SourceTrackers.MAX_SOURCES).isEqualTo(64);
        assertThat(trackers.sources()).isEqualTo(64);
        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(65);
        assertThat(count(body, "\"source\":\"")).isEqualTo(64);
        assertThat(body).contains("\"source\":\"source_63\"").doesNotContain("\"source\":\"source_64\"");
    }

    @Test
    void aSourcePastTheBoundStillMergesOnTheGlobalTracker() {
        IllegalStateException error = new IllegalStateException("storage offline");
        for (int i = 0; i < SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error, true);
        }

        trackers.report("late", error, true);
        trackers.report("late", error, true);
        trackers.report("source_0", error, true);

        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(65);
        assertThat(count(body, "\"count\":2")).isEqualTo(2);
        assertThat(body).doesNotContain("\"source\":\"late\"");
    }

    @Test
    void racingSourcesNeverPassTheBound() throws Exception {
        IllegalStateException error = new IllegalStateException("storage offline");

        RaceRunner.race(200, index -> {
            trackers.report("source_" + index, error, true);
            return index;
        });

        assertThat(trackers.sources()).isEqualTo(SourceTrackers.MAX_SOURCES);
        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(65);
        assertThat(count(body, "\"source\":\"")).isEqualTo(64);
        assertThat(body).contains("\"count\":136");
    }

    @Test
    void reportsWhoseMessagesDifferOnlyInWhatTheRulesHideMergeIntoOneError() {
        for (int i = 0; i < 3; i++) {
            trackers.report("storage", new IllegalStateException("could not save profile " + UUID.randomUUID(),
                new IllegalArgumentException("player " + UUID.randomUUID())), true);
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(1);
        assertThat(body).contains("could not save profile [uuid hidden]").contains("\"count\":3");
    }

    @Test
    void reportsThatDifferInAnUnhiddenPartStaySeparate() {
        for (int i = 0; i < 3; i++) {
            trackers.report("storage", new IllegalStateException("could not save item " + word(i)), true);
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(3);
        assertThat(body).doesNotContain("\"count\"");
    }

    @Test
    void aSourceSendsAtMostTheBoundOfKindsAndAKnownKindStillCounts() {
        int[] failures = IntStream.concat(IntStream.range(0, SourceTrackers.MAX_KINDS + 5), IntStream.of(0)).toArray();
        for (int i : failures) {
            trackers.report("storage", new IllegalStateException("failure " + word(i)), true);
        }
        trackers.report("hook.example", new IllegalStateException("failure " + word(40)), true);

        assertThat(SourceTrackers.MAX_KINDS).isEqualTo(32);
        String body = errorBody();
        assertThat(count(body, "\"source\":\"storage\"")).isEqualTo(32);
        assertThat(body).contains("failure " + word(31)).doesNotContain("failure " + word(32))
            .contains("\"count\":2").contains("\"source\":\"hook.example\"");
    }

    @Test
    void theKindThatFillsASourceLogsOneWarningThatNamesIt() {
        for (int i = 0; i < SourceTrackers.MAX_KINDS - 1; i++) {
            trackers.report("storage.save", new IllegalStateException("failure " + word(i)), true);
        }
        trackers.report("hook.example", new IllegalStateException("failure"), true);
        assertThat(fixture.log.lines()).isEmpty();

        for (int i = SourceTrackers.MAX_KINDS - 1; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("storage.save", new IllegalStateException("failure " + word(i)), true);
        }
        trackers.report("storage.save", new IllegalStateException("failure " + word(0)), true);

        assertThat(fixture.log.lines()).containsExactly("WARNING Error reports from the source 'storage.save'"
            + " reached 32 kinds. A report of any further kind from there is not sent to FastStats until metrics"
            + " start again.");
    }

    @Test
    void theKindThatFillsTheSourcesPastTheBoundLogsOneWarning() {
        IllegalStateException error = new IllegalStateException("storage offline");
        for (int i = 0; i < SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error, true);
        }

        for (int i = 0; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("late_" + i, new IllegalStateException("late " + word(i)), true);
        }

        assertThat(fixture.log.lines()).containsExactly("WARNING Error reports from the sources past the first 64"
            + " reached 32 kinds. A report of any further kind from there is not sent to FastStats until metrics"
            + " start again.");
    }

    @Test
    void reportsWhoseMessagesDifferOnlyInDigitsOrAddressesMergeAndCarryTheFirstMessage() {
        String[] waits = {"timeout after 5003ms", "timeout after 71ms", "timeout after 12ms"};
        String[] hosts = {"10.0.0.5:3306", "192.168.1.20:3307", "172.16.4.9:25565"};
        String[] routes = {"2001:db8::8a2e:370:7334", "fe80::1", "::1"};
        for (int i = 0; i < 3; i++) {
            trackers.report("storage.save", new IllegalStateException(waits[i],
                new IllegalArgumentException("Connection refused: " + hosts[i] + " through " + routes[i])), true);
        }
        for (int i = 0; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("storage.load", new IllegalStateException("could not load row " + i), true);
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(2);
        assertThat(body).contains("timeout after 5003ms").doesNotContain("timeout after 71ms")
            .contains("could not load row 0").contains("\"count\":3").contains("\"count\":37");
        assertThat(fixture.log.lines()).isEmpty();
    }

    @Test
    void aMessageKeyReplacesAddressesAndDigitRunsAfterTheAnonymizeRules() {
        assertThat(SourceTrackers.messageKey("Connection refused: 10.0.0.5:3306"))
            .isEqualTo("Connection refused: [address]:[number]");
        assertThat(SourceTrackers.messageKey("no route to fe80::1a2b, ::1 or 2001:0db8:85a3:0:0:8a2e:370:7334"))
            .isEqualTo("no route to [address], [address] or [address]");
        assertThat(SourceTrackers.messageKey("timeout after 5003ms for shop 12"))
            .isEqualTo("timeout after [number]ms for shop [number]");
        assertThat(SourceTrackers.messageKey("player 123e4567-e89b-12d3-a456-426614174000 has 3 items"))
            .isEqualTo("player [uuid hidden] has [number] items");
        assertThat(SourceTrackers.messageKey("Reward::give failed: no item named ruby"))
            .isEqualTo("Reward::give failed: no item named ruby");
        assertThat(SourceTrackers.messageKey(null)).isNull();
    }

    @Test
    void theSourcesPastTheBoundShareOneBoundOfKinds() {
        IllegalStateException error = new IllegalStateException("storage offline");
        for (int i = 0; i < SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error, true);
        }

        for (int i = 0; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("late_" + i, new IllegalStateException("late " + word(i)), true);
        }

        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(SourceTrackers.MAX_SOURCES + SourceTrackers.MAX_KINDS);
        assertThat(body).contains("late " + word(31)).doesNotContain("late " + word(32));
    }

    @Test
    void anUnhandledReportIsSentAsUnhandledOnceForItsKind() {
        IllegalStateException error = new IllegalStateException("task broken");

        for (int i = 0; i < 3; i++) {
            trackers.report("scheduler.async", error, false);
        }
        trackers.report("scheduler.async", new IllegalArgumentException("other task broken"), false);

        String body = errorBody();
        assertThat(count(body, "\"handled\":false")).isEqualTo(2);
        assertThat(count(body, "\"source\":\"scheduler.async\"")).isEqualTo(2);
        assertThat(body).contains("task broken").contains("other task broken").doesNotContain("\"count\"")
            .doesNotContain("\"handled\":true");
    }

    @Test
    void aKindReportedBothWaysGivesOneUnhandledErrorAndOneHandledErrorWithItsCount() {
        IllegalStateException error = new IllegalStateException("storage offline");

        trackers.report("storage", error, false);
        trackers.report("storage", error, true);
        trackers.report("storage", error, false);
        trackers.report("storage", error, true);

        String body = errorBody();
        assertThat(count(body, "\"handled\":false")).isEqualTo(1);
        assertThat(count(body, "\"handled\":true")).isEqualTo(1);
        assertThat(count(body, "\"count\":2")).isEqualTo(1);
    }

    @Test
    void unhandledKindsCountTowardsTheBoundOfTheirSource() {
        for (int i = 0; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("scheduler.async", new IllegalStateException("failure " + word(i)), false);
        }

        String body = errorBody();
        assertThat(count(body, "\"handled\":false")).isEqualTo(32);
        assertThat(body).contains("failure " + word(31)).doesNotContain("failure " + word(32));
        assertThat(fixture.log.lines()).singleElement().asString().contains("'scheduler.async'");
    }

    @Test
    void aKindCoversTheWholeCauseChainAndSurvivesACycle() {
        IllegalStateException first = new IllegalStateException("outer");
        IllegalArgumentException cause = new IllegalArgumentException("inner " + UUID.randomUUID());
        first.initCause(cause);
        cause.initCause(first);

        assertThat(SourceTrackers.kindOf(first)).hasSize(6).contains("inner [uuid hidden]");
        assertThat(SourceTrackers.kindOf(new IllegalStateException((String) null))).hasSize(3).containsNull();
    }

    @Test
    void aNullErrorIsRefused() {
        assertThatNullPointerException().isThrownBy(() -> trackers.report("storage", null, true)).withMessage("error");
        assertThat(trackers.sources()).isZero();
    }

    @Test
    void theAnonymizeRulesApplyToASourceReport() {
        trackers.report("storage", new IllegalStateException("mail admin@example.org failed"), true);

        assertThat(errorBody()).contains("mail [email hidden] failed").doesNotContain("admin@example.org");
    }

    @Test
    void oneSourceKeepsOneTracker() {
        trackers.report("storage", new IllegalStateException("first"), true);
        trackers.report("storage", new IllegalArgumentException("second"), true);

        assertThat(trackers.sources()).isEqualTo(1);
        String body = errorBody();
        assertThat(count(body, "\"source\":\"storage\"")).isEqualTo(2);
    }

    @Test
    void aRegisteredSourcePostsAnEmptyErrorsArrayAtEverySendAfterItsReportsWereSent() {
        trackers.report("storage", new IllegalStateException("storage offline"), true);
        ArgumentCaptor<Consumer<ScheduledTask>> scheduled = ArgumentCaptor.captor();
        verify(fixture.asyncScheduler)
            .runAtFixedRate(any(Plugin.class), scheduled.capture(), anyLong(), anyLong(), any(TimeUnit.class));

        scheduled.getValue().accept(fixture.task);
        scheduled.getValue().accept(fixture.task);
        context.shutdown();

        List<String> bodies = errorBodies();
        assertThat(bodies).hasSize(3);
        assertThat(bodies.getFirst()).contains("\"source\":\"storage\"");
        assertThat(bodies.subList(1, 3)).allSatisfy(body -> assertThat(body).contains("\"errors\":[]"));
    }

    @Test
    void noReportSendsNoErrors() {
        context.shutdown();

        assertThat(errorBodies()).isEmpty();
    }

    /** Shuts the context down, which sends its errors, and returns the one error body the sink received. */
    private String errorBody() {
        context.shutdown();
        List<String> bodies = errorBodies();
        assertThat(bodies).hasSize(1);
        return bodies.getFirst();
    }

    private List<String> errorBodies() {
        return sink.requests.stream()
            .filter(request -> request.path().equals("/v1/error"))
            .map(LoopbackSink.Request::body)
            .toList();
    }

    /** A word of letters that differs for each {@code index} below 52. */
    private static String word(int index) {
        return "kind" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(index);
    }

    private static int count(String text, String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
