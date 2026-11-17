package so.alaz.taffy.comficonf;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(ComfiConfStatus.class))
            .contains(ComfiConfState.class, ComfiConfStatus.class, ComfiConfLink.class, ComfiConfRegistration.class,
                ComfiConfConnection.class, YamlBytes.class, YamlFileProvider.class, YamlFileProvider.Builder.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void thePublicTopLevelTypesAreExactlyTheOnesAPluginNames() throws Exception {
        assertThat(ApiRules.classes(ComfiConfStatus.class).stream()
            .filter(type -> type.getEnclosingClass() == null && Modifier.isPublic(type.getModifiers()))
            .map(Class::getSimpleName))
            .containsExactlyInAnyOrder("ComfiConfState", "ComfiConfStatus", "ComfiConfLink", "ComfiConfRegistration",
                "ComfiConfConnection", "YamlFileProvider");
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(ComfiConfStatus.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(ComfiConfStatus.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(ComfiConfStatus.class)).isEmpty();
    }
}
