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
        trackers = new SourceTrackers(context.errorTrackerService().orElseThrow());
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
            trackers.report("storage", error);
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(1);
        assertThat(body).contains("\"count\":3").contains("\"source\":\"storage\"").contains("\"handled\":true");
    }

    @Test
    void twoSourcesGiveTwoErrorsEachWithItsSource() {
        IllegalStateException error = new IllegalStateException("storage offline");

        trackers.report("storage", error);
        trackers.report("shutdown", error);

        String body = errorBody();
        assertThat(count(body, "\"source\":\"")).isEqualTo(2);
        assertThat(body).contains("\"source\":\"storage\"").contains("\"source\":\"shutdown\"")
            .doesNotContain("\"count\"");
    }

    @Test
    void sourcesPastTheBoundGoToTheGlobalTrackerWithoutASource() {
        IllegalStateException error = new IllegalStateException("storage offline");

        for (int i = 0; i <= SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error);
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
            trackers.report("source_" + i, error);
        }

        trackers.report("late", error);
        trackers.report("late", error);
        trackers.report("source_0", error);

        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(65);
        assertThat(count(body, "\"count\":2")).isEqualTo(2);
        assertThat(body).doesNotContain("\"source\":\"late\"");
    }

    @Test
    void racingSourcesNeverPassTheBound() throws Exception {
        IllegalStateException error = new IllegalStateException("storage offline");

        RaceRunner.race(200, index -> {
            trackers.report("source_" + index, error);
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
                new IllegalArgumentException("player " + UUID.randomUUID())));
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(1);
        assertThat(body).contains("could not save profile [uuid hidden]").contains("\"count\":3");
    }

    @Test
    void reportsThatDifferInAnUnhiddenPartStaySeparate() {
        for (int i = 0; i < 3; i++) {
            trackers.report("storage", new IllegalStateException("could not save item " + i));
        }

        String body = errorBody();
        assertThat(count(body, "\"error\":\"java.lang.IllegalStateException\"")).isEqualTo(3);
        assertThat(body).doesNotContain("\"count\"");
    }

    @Test
    void aSourceSendsAtMostTheBoundOfKindsAndAKnownKindStillCounts() {
        int[] failures = IntStream.concat(IntStream.range(0, SourceTrackers.MAX_KINDS + 5), IntStream.of(0)).toArray();
        for (int i : failures) {
            trackers.report("storage", new IllegalStateException("failure " + i));
        }
        trackers.report("hook.example", new IllegalStateException("failure 99"));

        assertThat(SourceTrackers.MAX_KINDS).isEqualTo(32);
        String body = errorBody();
        assertThat(count(body, "\"source\":\"storage\"")).isEqualTo(32);
        assertThat(body).contains("failure 31").doesNotContain("failure 32").contains("\"count\":2")
            .contains("\"source\":\"hook.example\"");
    }

    @Test
    void theSourcesPastTheBoundShareOneBoundOfKinds() {
        IllegalStateException error = new IllegalStateException("storage offline");
        for (int i = 0; i < SourceTrackers.MAX_SOURCES; i++) {
            trackers.report("source_" + i, error);
        }

        for (int i = 0; i < SourceTrackers.MAX_KINDS + 5; i++) {
            trackers.report("late_" + i, new IllegalStateException("late " + i));
        }

        String body = errorBody();
        assertThat(count(body, "\"handled\":true")).isEqualTo(SourceTrackers.MAX_SOURCES + SourceTrackers.MAX_KINDS);
        assertThat(body).contains("late 31").doesNotContain("late 32");
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
        assertThatNullPointerException().isThrownBy(() -> trackers.report("storage", null)).withMessage("error");
        assertThat(trackers.sources()).isZero();
    }

    @Test
    void theAnonymizeRulesApplyToASourceReport() {
        trackers.report("storage", new IllegalStateException("mail admin@example.org failed"));

        assertThat(errorBody()).contains("mail [email hidden] failed").doesNotContain("admin@example.org");
    }

    @Test
    void oneSourceKeepsOneTracker() {
        trackers.report("storage", new IllegalStateException("first"));
        trackers.report("storage", new IllegalArgumentException("second"));

        assertThat(trackers.sources()).isEqualTo(1);
        String body = errorBody();
        assertThat(count(body, "\"source\":\"storage\"")).isEqualTo(2);
    }

    @Test
    void aRegisteredSourcePostsAnEmptyErrorsArrayAtEverySendAfterItsReportsWereSent() {
        trackers.report("storage", new IllegalStateException("storage offline"));
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

    private static int count(String text, String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
