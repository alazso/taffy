package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(StrictYaml.class))
            .contains(StrictYaml.class, RestartKeys.class, ConfigLoadException.class, ConfigLoader.class,
                ConfigReader.class, ConfigHolder.class, ConfigHolder.Loader.class, YamlFolder.class,
                YamlFolder.Parser.class, YamlFolder.Loaded.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(StrictYaml.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(StrictYaml.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(StrictYaml.class)).isEmpty();
    }
}
