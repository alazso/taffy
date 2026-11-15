package so.alaz.taffy.storage;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
final class MySqlStorageTest extends CheckpointedStorageContract {

    @Container
    private static final MySQLContainer<?> DATABASE = new MySQLContainer<>(DatabaseImages.MYSQL);

    @Override
    Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    String name() {
        return "mysql-probe";
    }

    @Override
    StorageSettings.Server server(String prefix) {
        return new StorageSettings.Server(DATABASE.getHost(), DATABASE.getFirstMappedPort(),
            DATABASE.getDatabaseName(), DATABASE.getUsername(), DATABASE.getPassword(),
            StorageSettings.SslMode.DISABLE, prefix);
    }
}
