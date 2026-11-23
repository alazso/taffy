package so.alaz.taffy.platform;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.DiagnosticReport.Line;
import so.alaz.taffy.platform.DiagnosticReport.Section;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class DiagnosticReportTest {

    private final CapturedLog log = new CapturedLog();
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);

    DiagnosticReportTest() {
        PluginMeta meta = mock(PluginMeta.class);
        when(plugin.getName()).thenReturn("Shop");
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn("1.4.0");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(log.logger);
        when(server.getName()).thenReturn("Paper");
        when(server.getMinecraftVersion()).thenReturn("26.3");
    }

    private DiagnosticReport.Builder builder() {
        return DiagnosticReport.builder(plugin, Scheduler.Mode.PAPER);
    }

    @Test
    void aReportStartsWithTheServerSection() {
        DiagnosticReport report = builder().build();

        assertThat(report.sections()).containsExactly(new Section("Server", List.of(
            new Line("Plugin", "Shop 1.4.0"),
            new Line("Server", "Paper 26.3"),
            new Line("Java", Runtime.version().toString()),
            new Line("Scheduler", "Paper"))));
        assertThat(DiagnosticReport.builder(plugin, Scheduler.Mode.FOLIA).build().sections().get(0).lines())
            .contains(new Line("Scheduler", "Folia"));
        assertThat(log.records).isEmpty();
    }

    @Test
    void sectionsFollowInTheOrderTheyWereAddedAndRenderAsPlainText() {
        DiagnosticReport report = builder()
            .section("Storage", () -> List.of(new Line("Database", "SQLite"), new Line("State", "open")))
            .section("Integrations", List.of(Line.of("Vault on")))
            .section("Empty", List.of())
            .build();

        assertThat(report.sections()).extracting(Section::title)
            .containsExactly("Server", "Storage", "Integrations", "Empty");
        assertThat(report.plainText()).containsExactly(
            "Server",
            "  Plugin: Shop 1.4.0",
            "  Server: Paper 26.3",
            "  Java: " + Runtime.version(),
            "  Scheduler: Paper",
            "Storage",
            "  Database: SQLite",
            "  State: open",
            "Integrations",
            "  Vault on",
            "Empty");
    }

    @Test
    void theComponentsHoldTheTextOfThePlainLinesAndNeverReadAValueAsMarkup() {
        DiagnosticReport report = builder()
            .section("Notes", List.of(new Line("Motd", "<red>hi</red> &cthere"), Line.of("Vault on")))
            .build();

        List<Component> components = report.components();

        assertThat(components).extracting(PlainTextComponentSerializer.plainText()::serialize)
            .containsExactlyElementsOf(report.plainText());
        assertThat(components.get(0)).isEqualTo(Component.text("Server", NamedTextColor.GOLD));
        assertThat(components.get(6)).isEqualTo(Component.text("  Motd: ", NamedTextColor.GRAY)
            .append(Component.text("<red>hi</red> &cthere", NamedTextColor.WHITE)));
        assertThat(components.get(7)).isEqualTo(Component.text("  ")
            .append(Component.text("Vault on", NamedTextColor.WHITE)));
    }

    @Test
    void aSectionThatThrowsShowsAsOneLineAndTheOthersAreKept() {
        IllegalStateException failure = new IllegalStateException("jdbc:mysql://root:hunter2@db.example.net/shop");
        DiagnosticReport report = builder()
            .section("Storage", () -> {
                throw failure;
            })
            .section("Missing", () -> {
                throw new NoClassDefFoundError("com/example/Gone");
            })
            .section("Null", () -> null)
            .section("Null line", () -> Collections.singletonList(null))
            .section("Metrics", () -> List.of(new Line("bStats", "on")))
            .build();

        assertThat(report.plainText()).containsSubsequence(
            "Storage",
            "  could not be read: java.lang.IllegalStateException",
            "Missing",
            "  could not be read: java.lang.NoClassDefFoundError",
            "Null",
            "  could not be read: java.lang.NullPointerException",
            "Null line",
            "  could not be read: java.lang.NullPointerException",
            "Metrics",
            "  bStats: on");
        assertThat(String.join("\n", report.plainText())).doesNotContain("hunter2").doesNotContain("example.net");
        assertThat(log.lines()).hasSize(4).startsWith(
            "WARNING The diagnostics section 'Storage' could not be read");
        assertThat(log.records.get(0).getThrown()).isSameAs(failure);
    }

    @Test
    void aFailingServerSectionAndAFailingLoggerDoNotLoseTheReport() {
        when(plugin.getServer()).thenThrow(new IllegalStateException("no server"));
        when(plugin.getLogger()).thenThrow(new IllegalStateException("no logger"));

        DiagnosticReport report = builder().section("Metrics", List.of(new Line("bStats", "on"))).build();

        assertThat(report.plainText()).containsExactly(
            "Server",
            "  could not be read: java.lang.IllegalStateException",
            "Metrics",
            "  bStats: on");
    }

    @Test
    void everySupplierRunsOnceInEachBuildOnTheCallingThread() {
        AtomicInteger runs = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        DiagnosticReport.Builder builder = builder().section("Counter", () -> {
            threads.add(Thread.currentThread());
            return List.of(new Line("Runs", Integer.toString(runs.incrementAndGet())));
        });

        assertThat(runs).hasValue(0);
        assertThat(builder.build().plainText()).contains("  Runs: 1");
        assertThat(builder.build().plainText()).contains("  Runs: 2");
        assertThat(threads).containsExactly(Thread.currentThread(), Thread.currentThread());
    }

    @Test
    void aBuiltReportDoesNotChange() {
        List<Line> lines = new ArrayList<>(List.of(new Line("A", "1")));
        DiagnosticReport report = builder().section("Mine", lines).build();
        lines.add(new Line("B", "2"));

        assertThat(report.sections().get(1).lines()).containsExactly(new Line("A", "1"));
        assertThatThrownBy(() -> report.sections().add(new Section("X", List.of())))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.sections().get(1).lines().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.plainText().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.components().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLineNeverBreaksAndIsCutToItsLimits() {
        Line broken = new Line(" Mo\ntd\t", "one\r\ntwo three four\u0000 ");

        assertThat(broken).isEqualTo(new Line("Mo td", "one  two three four"));
        assertThat(broken.plain()).isEqualTo("Mo td: one  two three four");

        Line cut = new Line("l".repeat(65), "v".repeat(513));
        assertThat(cut.label()).isEqualTo("l".repeat(61) + "...").hasSize(DiagnosticReport.MAX_LABEL);
        assertThat(cut.value()).isEqualTo("v".repeat(509) + "...").hasSize(DiagnosticReport.MAX_VALUE);
        Line whole = new Line("l".repeat(64), "v".repeat(512));
        assertThat(whole.label()).hasSize(64).doesNotContain(".");
        assertThat(whole.value()).hasSize(512).doesNotContain(".");
        assertThat(new Line("", "v".repeat(508) + "😀" + "vvvv").value())
            .isEqualTo("v".repeat(508) + "...");
        assertThat(Line.of("Vault on")).isEqualTo(new Line("", "Vault on"));
        assertThat(Line.of("Vault on").plain()).isEqualTo("Vault on");
    }

    @Test
    void aRedactedLineHidesWhatRedactionHides() {
        Line line = Line.redacted("State", "failed: Access denied for user 'mc_prod'@'10.0.0.5' (using password: YES)"
            + " at jdbc:mysql://db.example.net:3306/shop?password=hunter2");

        assertThat(line.label()).isEqualTo("State");
        assertThat(line.value()).isEqualTo("failed: Access denied for user [user hidden]@[host hidden]"
            + " (using password: YES) at jdbc:[redacted]");
    }

    @Test
    void thePendingRestartLineNamesPathsOnly() {
        assertThat(Line.pendingRestart(List.of())).isEqualTo(new Line("Waiting for a restart", "none"));
        assertThat(Line.pendingRestart(List.of("storage.password", "locale.default")))
            .isEqualTo(new Line("Waiting for a restart", "storage.password, locale.default"));
        assertThat(Line.pendingRestart(List.of("a\nb")).value()).isEqualTo("a b");
    }

    @Test
    void thePendingRestartLineCountsThePathsItLeavesOut() {
        List<String> many = IntStream.rangeClosed(1, 40).mapToObj(i -> "key" + i).toList();
        List<String> shown = many.subList(0, DiagnosticReport.MAX_PATHS);

        assertThat(Line.pendingRestart(many).value()).isEqualTo(String.join(", ", shown) + " and 8 more");

        List<String> wide = IntStream.rangeClosed(1, 10).mapToObj(i -> i + "p".repeat(199)).toList();
        String value = Line.pendingRestart(wide).value();
        String path = "p".repeat(124) + "...";
        assertThat(value).isEqualTo("1" + path + ", 2" + path + ", 3" + path + " and 7 more");
        assertThat(value.length()).isLessThanOrEqualTo(DiagnosticReport.MAX_VALUE);
    }

    @Test
    void aSectionKeepsItsFirstLinesAndCountsTheRest() {
        List<Line> lines = IntStream.rangeClosed(1, 70).mapToObj(i -> Line.of("line " + i)).toList();

        Section section = new Section("Many", lines);

        assertThat(section.lines()).hasSize(DiagnosticReport.MAX_LINES + 1)
            .startsWith(Line.of("line 1")).endsWith(Line.of("line 64"), Line.of("6 more lines not shown"));
        assertThat(new Section("Full", lines.subList(0, 64)).lines()).hasSize(64).endsWith(Line.of("line 64"));
        assertThat(builder().section("Many", () -> lines).build().sections().get(1)).isEqualTo(section);
    }

    @Test
    void sectionsPastTheLimitAreCountedAndNeverRun() {
        AtomicInteger runs = new AtomicInteger();
        DiagnosticReport.Builder builder = builder();
        for (int i = 1; i <= DiagnosticReport.MAX_SECTIONS + 3; i++) {
            builder.section("Section " + i, () -> {
                runs.incrementAndGet();
                return List.of();
            });
        }

        DiagnosticReport report = builder.build();

        assertThat(runs).hasValue(DiagnosticReport.MAX_SECTIONS);
        assertThat(report.sections()).hasSize(DiagnosticReport.MAX_SECTIONS + 2);
        assertThat(report.sections().get(DiagnosticReport.MAX_SECTIONS).title()).isEqualTo("Section 32");
        assertThat(report.sections().get(DiagnosticReport.MAX_SECTIONS + 1))
            .isEqualTo(new Section("Not shown", List.of(Line.of("3 more sections"))));
    }

    @Test
    void nullsAndABlankTitleAreRefused() {
        assertThatThrownBy(() -> DiagnosticReport.builder(null, Scheduler.Mode.PAPER))
            .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> DiagnosticReport.builder(plugin, null))
            .isInstanceOf(NullPointerException.class).hasMessage("mode");
        assertThatThrownBy(() -> new Line(null, "v")).isInstanceOf(NullPointerException.class).hasMessage("label");
        assertThatThrownBy(() -> new Line("l", null)).isInstanceOf(NullPointerException.class).hasMessage("value");
        assertThatThrownBy(() -> Line.redacted(null, "v")).isInstanceOf(NullPointerException.class)
            .hasMessage("label");
        assertThatThrownBy(() -> Line.redacted("l", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("text");
        assertThatThrownBy(() -> Line.pendingRestart(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("paths");
        assertThatThrownBy(() -> Line.pendingRestart(Arrays.asList("a", null)))
            .isInstanceOf(NullPointerException.class).hasMessage("path");
        assertThatThrownBy(() -> new Section(null, List.of())).isInstanceOf(NullPointerException.class)
            .hasMessage("title");
        assertThatThrownBy(() -> new Section("t", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("lines");
        assertThatThrownBy(() -> new Section(" \n", List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("title is blank");
        assertThatThrownBy(() -> builder().section(" ", List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("title is blank");
        assertThatThrownBy(() -> builder().section("t", (List<Line>) null))
            .isInstanceOf(NullPointerException.class).hasMessage("lines");
        assertThatThrownBy(() -> builder().section("t", (java.util.function.Supplier<List<Line>>) null))
            .isInstanceOf(NullPointerException.class).hasMessage("lines");
    }
}
