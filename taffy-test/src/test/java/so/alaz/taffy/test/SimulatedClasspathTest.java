package so.alaz.taffy.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedClasspathTest {

    private static final String MARKER = "simulated.library.Marker";
    private static final String OTHER = "simulated.library.Other";

    @Test
    void aNamedClassLoadsAndHasNoMembers() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        Class<?> marker = Class.forName(MARKER, false, loader);

        assertThat(marker.getName()).isEqualTo(MARKER);
        assertThat(marker.getClassLoader()).isSameAs(loader);
        assertThat(marker.getSuperclass()).isEqualTo(Object.class);
        assertThat(marker.getDeclaredFields()).isEmpty();
        assertThat(marker.getDeclaredMethods()).isEmpty();
        assertThat(marker.getDeclaredConstructors()).isEmpty();
    }

    @Test
    void everyNamedClassLoadsAndTheSameClassIsGivenEachTime() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER, OTHER, MARKER);

        Class<?> marker = Class.forName(MARKER, false, loader);

        assertThat(Class.forName(MARKER, false, loader)).isSameAs(marker);
        assertThat(Class.forName(OTHER, false, loader).getName()).isEqualTo(OTHER);
    }

    @Test
    void aClassThatWasNotNamedIsNotFound() {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        assertThatThrownBy(() -> Class.forName(OTHER, false, loader))
            .isInstanceOf(ClassNotFoundException.class)
            .hasMessage(OTHER);
        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.with()))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void aNamedClassIsAbsentFromTheParentAndFromAnotherSimulatedLoader() throws Exception {
        Class.forName(MARKER, false, SimulatedClasspath.with(MARKER));

        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.class.getClassLoader()))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.with(OTHER)))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void classesOfTheParentStillLoad() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        assertThat(loader.getParent()).isSameAs(SimulatedClasspath.class.getClassLoader());
        assertThat(Class.forName(CapturedLog.class.getName(), false, loader)).isSameAs(CapturedLog.class);
        assertThat(Class.forName("java.util.List", false, loader)).isSameAs(java.util.List.class);
    }

    @Test
    void aNamedClassTheParentKnowsLoadsTheRealClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(CapturedLog.class.getName());

        assertThat(Class.forName(CapturedLog.class.getName(), false, loader)).isSameAs(CapturedLog.class);
    }
}
