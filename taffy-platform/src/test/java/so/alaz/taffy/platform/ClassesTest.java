package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

class ClassesTest {

    private final CapturedLog log = new CapturedLog();

    @Test
    void findsALoadableClass() {
        assertThat(Classes.present("java.lang.String", Classes.class, log.logger)).isTrue();
        assertThat(log.records).isEmpty();
    }

    @Test
    void reportsAnAbsentClass() {
        assertThat(Classes.present("no.such.plugin.Api", Classes.class, log.logger)).isFalse();
        assertThat(log.records).isEmpty();
    }

    @Test
    void reportsAbsentWhenAnotherPluginsClassloaderThrows() {
        // As the server does when a plugin failed to enable and its jar was closed.
        ClassLoader closed = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) {
                throw new IllegalStateException("The plugin classloader for Other has thrown a zip file error");
            }
        };
        assertThat(Classes.present("com.sk89q.worldguard.WorldGuard", closed, log.logger)).isFalse();
        assertThat(log.records).hasSize(1);
        assertThat(log.records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(log.records.get(0).getMessage()).isEqualTo(
            "Could not look up com.sk89q.worldguard.WorldGuard, so it is treated as absent. "
                + "Another plugin on this server is likely broken: java.lang.IllegalStateException: "
                + "The plugin classloader for Other has thrown a zip file error");
    }

    @Test
    void reportsAbsentWhenTheLookupFailsToLink() {
        ClassLoader broken = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) {
                throw new NoClassDefFoundError(name);
            }
        };
        assertThat(Classes.present("dev.lone.itemsadder.api.CustomStack", broken, log.logger)).isFalse();
        assertThat(log.records).hasSize(1);
        assertThat(log.records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(log.records.get(0).getMessage())
            .startsWith("Could not look up dev.lone.itemsadder.api.CustomStack, ")
            .endsWith(": java.lang.NoClassDefFoundError: dev.lone.itemsadder.api.CustomStack");
    }
}
