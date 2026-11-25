package so.alaz.taffy.platform;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;
import so.alaz.taffy.test.SimulatedClasspath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class FoliaDetectionTest {

    private final CapturedLog log = new CapturedLog();
    private final Plugin plugin = mock(Plugin.class);

    FoliaDetectionTest() {
        when(plugin.getServer()).thenReturn(mock(Server.class));
        when(plugin.getLogger()).thenReturn(log.logger);
    }

    @Test
    void readsPaperWhenTheFoliaServerClassIsAbsent() {
        Scheduler scheduler = new Scheduler(plugin, new ErrorReports());

        assertThat(scheduler.mode()).isEqualTo(Scheduler.Mode.PAPER);
        assertThat(log.records).isEmpty();
    }

    @Test
    void readsFoliaOnASimulatedFoliaClasspath() {
        ClassLoader loader = SimulatedClasspath.with(Scheduler.FOLIA_MARKER);

        Scheduler scheduler = new Scheduler(plugin, new ErrorReports(), loader);

        assertThat(scheduler.mode()).isEqualTo(Scheduler.Mode.FOLIA);
        assertThat(log.records).isEmpty();
    }

    @Test
    void readsPaperWithAWarningWhenTheLookupItselfFails() {
        ClassLoader broken = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new IllegalStateException("zip file closed");
            }
        };

        Scheduler scheduler = new Scheduler(plugin, new ErrorReports(), broken);

        assertThat(scheduler.mode()).isEqualTo(Scheduler.Mode.PAPER);
        assertThat(log.lines()).singleElement().asString()
            .startsWith("WARNING Could not look up " + Scheduler.FOLIA_MARKER)
            .endsWith("java.lang.IllegalStateException: zip file closed");
    }
}
