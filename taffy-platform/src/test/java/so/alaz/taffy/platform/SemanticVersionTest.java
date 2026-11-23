package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class SemanticVersionTest {

    private static SemanticVersion version(String text) {
        SemanticVersion version = SemanticVersion.parse(text);
        assertThat(version).as(text).isNotNull();
        return version;
    }

    @Test
    void parseReadsTheNumbersAndThePreReleaseIdentifiers() {
        assertThat(version("1.4.0")).isEqualTo(new SemanticVersion(1, 4, 0, List.of()));
        assertThat(version(" v1.4.2 ")).isEqualTo(new SemanticVersion(1, 4, 2, List.of()));
        assertThat(version("V2.0")).isEqualTo(new SemanticVersion(2, 0, 0, List.of()));
        assertThat(version("1.5.0-rc.1")).isEqualTo(new SemanticVersion(1, 5, 0, List.of("rc", "1")));
        assertThat(version("1.5.0-SNAPSHOT")).isEqualTo(new SemanticVersion(1, 5, 0, List.of("SNAPSHOT")));
        assertThat(version("1.4.0+build.7")).isEqualTo(new SemanticVersion(1, 4, 0, List.of()));
        assertThat(version("1.4.0-beta+exp.sha.5114f85")).isEqualTo(new SemanticVersion(1, 4, 0, List.of("beta")));
        assertThat(version("999999999.0.0").major()).isEqualTo(999_999_999);
    }

    @Test
    void parseGivesNullForAnotherForm() {
        for (String text : List.of("", "1", "build-17", "1.4.0.1", "1.4.x", "1.4.0-", "1.4.0-rc..1", "1.4.0+",
            "1.4.0 beta", "1.4.0-<red>", "1.4.0-rc_1", "1,4,0", "-1.4.0", "1000000000.0.0",
            "1.4.0-" + "a".repeat(64))) {
            assertThat(SemanticVersion.parse(text)).as(text).isNull();
        }
    }

    @Test
    void preReleaseIsSaidOfAVersionWithIdentifiers() {
        assertThat(version("1.5.0").isPreRelease()).isFalse();
        assertThat(version("1.5.0+build").isPreRelease()).isFalse();
        assertThat(version("1.5.0-SNAPSHOT").isPreRelease()).isTrue();
        assertThat(version("1.5.0-rc.1").isPreRelease()).isTrue();
    }

    @Test
    void theOrderIsThePrecedenceOfSemanticVersioning() {
        List<String> ascending = List.of("0.9.9", "1.0.0-0", "1.0.0-2", "1.0.0-10", "1.0.0-99999999999999999999",
            "1.0.0-SNAPSHOT", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1-SNAPSHOT", "1.0.1", "1.1.0", "1.10.0", "2.0.0");
        List<SemanticVersion> versions = new ArrayList<>(ascending.stream().map(SemanticVersionTest::version).toList());

        Collections.reverse(versions);
        Collections.sort(versions);

        assertThat(versions).extracting(SemanticVersion::toString).containsExactlyElementsOf(ascending);
        assertThat(version("1.0.0+a")).isEqualByComparingTo(version("1.0.0+b"));
        assertThat(version("1.2")).isEqualByComparingTo(version("v1.2.0"));
    }

    @Test
    void toStringIsTheVersionWithoutThePrefixAndTheBuildMetadata() {
        assertThat(version("v1.4")).hasToString("1.4.0");
        assertThat(version("1.5.0-rc.1+build.7")).hasToString("1.5.0-rc.1");
    }
}
