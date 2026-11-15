package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ContainerRuntimeTest {

    @Test
    void continuousIntegrationRunsTheMysqlMariadbAndPostgresqlSuites() {
        assumeTrue(System.getenv("CI") != null, "only CI must have Docker");

        assertThat(DockerClientFactory.instance().isDockerAvailable())
            .as("Docker on the CI runner, so the MySQL, MariaDB and PostgreSQL tests do not skip").isTrue();
    }
}
