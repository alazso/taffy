package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(TaskGate.class))
            .contains(TaskGate.class, Scheduler.class, Messages.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(TaskGate.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(TaskGate.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(TaskGate.class)).isEmpty();
    }
}
