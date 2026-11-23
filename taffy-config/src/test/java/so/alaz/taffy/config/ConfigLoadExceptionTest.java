package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void theProblemsAreCopiedAndAFailureBuiltWithoutThemHasNone() {
        ConfigProblem problem = new ConfigProblem(
            "kit.yml", "title", ConfigProblem.Severity.ERROR, "title is missing; add one.", "add one");
        List<ConfigProblem> given = new ArrayList<>(List.of(problem));

        ConfigLoadException failure = ConfigLoadException.withProblems("title is missing; add one.", given);
        given.clear();

        assertThat(failure).hasMessage("title is missing; add one.").hasNoCause();
        assertThat(failure.problems()).containsExactly(problem);
        assertThatThrownBy(() -> failure.problems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ConfigLoadException("gone").problems()).isEmpty();
        assertThat(new ConfigLoadException("gone", new IOException("closed")).problems()).isEmpty();
    }

    @Test
    void aNullLiteralAsTheCauseNamesTheConstructorWithACause() {
        ConfigLoadException failure = new ConfigLoadException("gone", null);

        assertThat(failure).hasMessage("gone").hasNoCause();
        assertThat(failure.problems()).isEmpty();
    }

    @Test
    void itIsCheckedSoACallerMustHandleIt() {
        assertThat(Exception.class).isAssignableFrom(ConfigLoadException.class);
        assertThat(RuntimeException.class.isAssignableFrom(ConfigLoadException.class)).isFalse();
    }
}
