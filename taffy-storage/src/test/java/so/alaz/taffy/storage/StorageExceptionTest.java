package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

final class StorageExceptionTest {

    @Test
    void aMessageAloneHasNoCause() {
        StorageException failure = new StorageException("the sqlite driver is not available");

        assertThat(failure).hasMessage("the sqlite driver is not available").hasNoCause();
    }

    @Test
    void aCauseIsKeptBesideTheMessage() {
        SQLException cause = new SQLException("connection refused", "08001");

        StorageException failure = new StorageException("cannot open the database", cause);

        assertThat(failure).hasMessage("cannot open the database").hasCause(cause);
    }
}
