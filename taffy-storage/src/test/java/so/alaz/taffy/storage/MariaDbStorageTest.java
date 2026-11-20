package so.alaz.taffy.storage;

import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
final class MariaDbStorageTest extends CheckpointedStorageContract {

    @Container
    private static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>(DatabaseImages.MARIADB);

    @Override
    Dialect dialect() {
        return Dialect.MARIADB;
    }

    @Override
    String name() {
        return "mariadb-probe";
    }

    @Override
    StorageSettings.Server server(String prefix) {
        return StorageSettings.Server.of(DATABASE.getHost(), DATABASE.getFirstMappedPort(),
            DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
            StorageSettings.SslMode.DISABLE).tablePrefix(prefix);
    }
}
