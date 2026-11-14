package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

final class ConfigLoadExceptionTest {

    @Test
    void aMessageAloneHasNoCause() {
        ConfigLoadException failure = new ConfigLoadException("the plugin jar has no config.yml");

        assertThat(failure).hasMessage("the plugin jar has no config.yml").hasNoCause();
    }

    @Test
    void aCauseIsKeptBesideTheMessage() {
        IOException cause = new IOException("closed");

        ConfigLoadException failure = new ConfigLoadException("cannot read config.yml", cause);

        assertThat(failure).hasMessage("cannot read config.yml").hasCause(cause);
    }

    @Test
    void itIsCheckedSoACallerMustHandleIt() {
        assertThat(Exception.class).isAssignableFrom(ConfigLoadException.class);
        assertThat(RuntimeException.class.isAssignableFrom(ConfigLoadException.class)).isFalse();
    }
}
