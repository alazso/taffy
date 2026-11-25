package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class ClassLookupTest {

    private static final String DHAPI = "eu.decentsoftware.holograms.api.DHAPI";

    @Test
    void aLoadableClassIsPresentAndAnUnknownOneIsAbsent() {
        ClassLoader loader = ClassLookupTest.class.getClassLoader();

        assertThat(ClassLookup.of("java.lang.String", loader)).isEqualTo(ClassLookup.PRESENT);
        assertThat(ClassLookup.of("java.lang.String", null)).isEqualTo(ClassLookup.PRESENT);
        assertThat(ClassLookup.of("so.alaz.taffy.hooks.NoSuchApi", loader)).isEqualTo(ClassLookup.ABSENT);
        assertThat(ClassLookup.ABSENT.failure()).isNull();
    }

    @Test
    void aLookupThatReachesTheClosedLoaderOfABrokenPluginFailsWithWhatItThrew() {
        IllegalStateException closed = new IllegalStateException(
            "The plugin classloader for HologramLib has thrown a zip file error.");

        ClassLookup lookup = ClassLookup.of(DHAPI, ClosedLoader.of(closed));

        assertThat(lookup.present()).isFalse();
        assertThat(lookup.failure()).isSameAs(closed);
        assertThat(lookup).isNotEqualTo(ClassLookup.ABSENT);
    }

    @Test
    void aLookupThatFailsToLinkFailsWithTheError() {
        NoClassDefFoundError broken = new NoClassDefFoundError("eu/decentsoftware/holograms/api/Base");
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) {
                throw broken;
            }
        };

        assertThat(ClassLookup.of(DHAPI, loader).failure()).isSameAs(broken);
    }

    @Test
    void aPresentClassHasNoFailureAndAFailureIsNeverNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClassLookup(true, new IllegalStateException()));
        assertThatNullPointerException().isThrownBy(() -> ClassLookup.failed(null)).withMessage("failure");
    }
}
