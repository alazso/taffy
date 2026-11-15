package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

final class CommitUnknownExceptionTest {

    @Test
    void theCommitFailureKeepsTheMessageSqlStateVendorCodeAndCause() {
        SQLException cause = new SQLException("connection reset", "08S01", 2013);

        CommitUnknownException failure = new CommitUnknownException(cause);

        assertThat((Throwable) failure)
            .isInstanceOf(SQLException.class)
            .hasMessage("the commit failed, so whether the transaction was written is unknown: connection reset")
            .hasCause(cause);
        assertThat(failure.getSQLState()).isEqualTo("08S01");
        assertThat(failure.getErrorCode()).isEqualTo(2013);
    }
}
