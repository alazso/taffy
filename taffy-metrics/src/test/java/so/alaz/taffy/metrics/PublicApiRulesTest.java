package so.alaz.taffy.metrics;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(CounterMark.class))
            .contains(CounterMark.class, MetricChart.class, MetricChart.Type.class, BStatsCharts.class,
                FastStatsCharts.class, MetricsBuilder.class, Metrics.class, Anonymizers.class, SourceTrackers.class,
                TaffyUsage.class, TaffyVersion.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void thePublicTopLevelTypesAreExactlyTheOnesAPluginNames() throws Exception {
        assertThat(ApiRules.classes(CounterMark.class).stream()
            .filter(type -> type.getEnclosingClass() == null && Modifier.isPublic(type.getModifiers()))
            .map(Class::getSimpleName))
            .containsExactlyInAnyOrder("MetricChart", "MetricsBuilder", "Metrics");
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(CounterMark.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(CounterMark.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(CounterMark.class)).isEmpty();
    }
}
