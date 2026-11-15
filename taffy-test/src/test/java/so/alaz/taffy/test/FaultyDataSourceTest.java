package so.alaz.taffy.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultyDataSourceTest {

    private static final String INSERT = "INSERT INTO item (id) VALUES (?)";

    @TempDir
    Path folder;

    private String url;
    private FaultyDataSource source;
    private FaultyDataSource.Faults faults;

    @BeforeEach
    void createTable() throws SQLException {
        url = "jdbc:sqlite:" + folder.resolve("data.db").toAbsolutePath();
        source = new FaultyDataSource(url, new Properties());
        faults = source.faults();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item (id INTEGER PRIMARY KEY)");
        }
    }

    @Test
    void aConnectionWithNoFaultArmedReadsAndWritesTheDatabase() throws SQLException {
        insert(1);
        insert(2);

        assertThat(ids()).containsExactly(1, 2);
        assertThat(faults.statements()).isEqualTo(4);
        assertThat(faults.commits()).isZero();
        assertThat(faults.dead()).isFalse();
    }

    @Test
    void connectionsAreOpenedWithACopyOfTheProperties() throws SQLException {
        Properties defaults = new Properties();
        defaults.setProperty("foreign_keys", "true");
        Properties properties = new Properties(defaults);
        FaultyDataSource withKeys = new FaultyDataSource(url, properties);
        defaults.setProperty("foreign_keys", "false");

        try (Connection connection = withKeys.getConnection("ignored", "ignored");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void aNullPropertiesArgumentIsRefusedByName() {
        assertThatThrownBy(() -> new FaultyDataSource(url, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("properties");
    }

    @Test
    void theNthStatementFailsOnceAndIsCounted() throws SQLException {
        faults.failStatement(2);

        insert(1);
        assertThatThrownBy(() -> insert(2)).isInstanceOf(SQLException.class).hasMessage("injected statement failure");
        insert(3);

        assertThat(ids()).containsExactly(1, 3);
        assertThat(faults.statements()).isEqualTo(5);
    }

    @Test
    void aCommitThatIsNotWrittenFailsAndLeavesNothingBehind() throws SQLException {
        faults.failCommit(2, false, false);

        assertThat(commitInsert(1)).isNull();
        Throwable failure = commitInsert(2);

        assertThat(failure).hasMessage("injected commit failure");
        assertThat(ids()).containsExactly(1);
        assertThat(faults.commits()).isEqualTo(2);
    }

    @Test
    void aCommitThatIsWrittenThenReportedFailedIsVisibleFromAFreshConnection() throws SQLException {
        faults.failCommit(1, true, false);

        Throwable failure = commitInsert(1);

        assertThat(failure).hasMessage("injected commit failure");
        assertThat(ids()).containsExactly(1);
        assertThat(faults.commits()).isEqualTo(1);
    }

    @Test
    void aFailedCommitWithReadFailsAlsoFailsTheNextStatement() throws SQLException {
        faults.failCommit(1, true, true);

        assertThat(commitInsert(1)).hasMessage("injected commit failure");

        assertThatThrownBy(this::ids).isInstanceOf(SQLException.class).hasMessage("injected statement failure");
        assertThat(ids()).containsExactly(1);
    }

    @Test
    void statementsMatchingAFragmentFailCountTimesWithoutBeingCounted() throws SQLException {
        faults.failMatching("INSERT INTO item", 2);

        assertThatThrownBy(() -> insert(1)).isInstanceOf(SQLException.class)
            .hasMessage("injected failure of INSERT INTO item");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO item (id) VALUES (2)"))
                .hasMessage("injected failure of INSERT INTO item");
        }
        assertThat(ids()).isEmpty();
        insert(3);

        assertThat(ids()).containsExactly(3);
        assertThat(faults.statements()).isEqualTo(4);
    }

    @Test
    void anEmptyFragmentMatchesNothing() throws SQLException {
        faults.failMatching("", 5);
        faults.afterMatching("", 1, () -> {
            throw new AssertionError("an empty fragment matched");
        });

        insert(1);

        assertThat(ids()).containsExactly(1);
    }

    @Test
    void theActionRunsOnceOnTheCallingThreadAfterTheNthMatchingStatementTookEffect() throws SQLException {
        List<Thread> threads = new ArrayList<>();
        List<List<Integer>> seen = new ArrayList<>();
        faults.afterMatching("INSERT INTO item", 2, () -> {
            threads.add(Thread.currentThread());
            try {
                seen.add(ids());
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });

        insert(1);
        assertThat(threads).isEmpty();
        insert(2);
        insert(3);

        assertThat(threads).containsExactly(Thread.currentThread());
        assertThat(seen).containsExactly(List.of(1, 2));
    }

    @Test
    void aFailedStatementDoesNotCountTowardTheAction() throws SQLException {
        List<String> runs = new ArrayList<>();
        faults.afterMatching("INSERT INTO item", 1, () -> runs.add("ran"));
        faults.failStatement(1);

        assertThatThrownBy(() -> insert(1)).hasMessage("injected statement failure");
        assertThat(runs).isEmpty();
        insert(2);

        assertThat(runs).containsExactly("ran");
    }

    @Test
    void disarmCancelsPendingFailuresButKeepsTheAction() throws SQLException {
        List<String> runs = new ArrayList<>();
        faults.failStatement(1);
        faults.failCommit(1, false, false);
        faults.failMatching("INSERT", 1);
        faults.afterMatching("INSERT", 1, () -> runs.add("ran"));

        faults.disarm();

        assertThat(commitInsert(1)).isNull();
        assertThat(ids()).containsExactly(1);
        assertThat(runs).containsExactly("ran");
    }

    @Test
    void killMakesEveryLaterConnectionStatementAndCommitFail() throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            PreparedStatement prepared = connection.prepareStatement(INSERT);
            prepared.setInt(1, 1);

            faults.kill();

            assertThat(faults.dead()).isTrue();
            assertThatThrownBy(source::getConnection).hasMessage("the process is dead");
            assertThatThrownBy(() -> connection.prepareStatement(INSERT)).hasMessage("the process is dead");
            assertThatThrownBy(connection::createStatement).hasMessage("the process is dead");
            assertThatThrownBy(prepared::executeUpdate).hasMessage("the process is dead");
            assertThatThrownBy(connection::commit).hasMessage("the process is dead");
            prepared.close();
            connection.rollback();
        }

        assertThat(faults.statements()).isEqualTo(1);
        assertThat(faults.commits()).isZero();
        assertThat(ids(new FaultyDataSource(url, new Properties()))).isEmpty();
    }

    @Test
    void aStatementRunAfterDeathThatAPendingMatchMatchesFailsWithTheMatch() throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement prepared = connection.prepareStatement(INSERT)) {
            prepared.setInt(1, 1);
            faults.failMatching("INSERT INTO item", 1);
            faults.kill();

            assertThatThrownBy(prepared::executeUpdate).hasMessage("injected failure of INSERT INTO item");
            assertThatThrownBy(prepared::executeUpdate).hasMessage("the process is dead");
        }

        assertThat(ids(new FaultyDataSource(url, new Properties()))).isEmpty();
    }

    @Test
    void anActionThatThrowsPropagatesFromTheRunCallAfterTheStatementTookEffect() throws SQLException {
        IllegalStateException broken = new IllegalStateException("broken action");
        faults.afterMatching("INSERT INTO item", 1, () -> {
            throw broken;
        });

        assertThatThrownBy(() -> insert(1)).isSameAs(broken);

        assertThat(ids()).containsExactly(1);
    }

    @Test
    void killAfterLetsNStatementsRunThenTheNextOneDies() throws SQLException {
        faults.killAfter(2);

        insert(1);
        insert(2);
        assertThat(faults.dead()).isFalse();
        assertThatThrownBy(() -> insert(3)).hasMessage("the process is dead");

        assertThat(faults.dead()).isTrue();
        assertThat(faults.statements()).isEqualTo(4);
        assertThatThrownBy(source::getConnection).hasMessage("the process is dead");
        assertThat(ids(new FaultyDataSource(url, new Properties()))).containsExactly(1, 2);
    }

    @Test
    void aBatchIsOneStatement() throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setInt(1, 1);
            statement.addBatch();
            statement.setInt(1, 2);
            statement.addBatch();
            statement.executeBatch();
        }

        assertThat(faults.statements()).isEqualTo(2);
        assertThat(ids()).containsExactly(1, 2);
    }

    @Test
    void aLargeBatchIsOneStatementAndCanBeFailed() throws SQLException {
        faults.failStatement(1);
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setInt(1, 1);
            statement.addBatch();
            assertThatThrownBy(statement::executeLargeBatch).hasMessage("injected statement failure");
            statement.clearBatch();
            statement.setInt(1, 2);
            statement.addBatch();
            statement.setInt(1, 3);
            statement.addBatch();
            statement.executeLargeBatch();
        }

        assertThat(faults.statements()).isEqualTo(3);
        assertThat(ids()).containsExactly(2, 3);
    }

    @Test
    void unwrappingTheConnectionGivesTheRealOne() throws SQLException {
        try (Connection connection = source.getConnection()) {
            Connection real = connection.unwrap(Connection.class);

            assertThat(Proxy.isProxyClass(connection.getClass())).isTrue();
            assertThat(Proxy.isProxyClass(real.getClass())).isFalse();
            assertThat(connection.unwrap(real.getClass())).isSameAs(real);
            assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        }
    }

    @Test
    void aWrapperEqualsOnlyItselfAndHashesByIdentity() throws SQLException {
        try (Connection connection = source.getConnection();
             Connection other = source.getConnection();
             Statement statement = connection.createStatement();
             PreparedStatement prepared = connection.prepareStatement(INSERT)) {
            assertThat(connection.equals(connection)).isTrue();
            assertThat(connection.equals(other)).isFalse();
            assertThat(connection.equals(connection.unwrap(Connection.class))).isFalse();
            assertThat(connection.hashCode()).isEqualTo(System.identityHashCode(connection));
            assertThat(statement.equals(statement)).isTrue();
            assertThat(prepared.equals(statement)).isFalse();
            assertThat(prepared.hashCode()).isEqualTo(System.identityHashCode(prepared));
        }
    }

    @Test
    void aStatementHandsBackTheWrapperSoAKillStillApplies() throws SQLException {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             PreparedStatement prepared = connection.prepareStatement(INSERT)) {
            assertThat(statement.getConnection()).isSameAs(connection);
            assertThat(prepared.getConnection()).isSameAs(connection);

            faults.kill();

            assertThatThrownBy(() -> statement.getConnection().createStatement())
                .isInstanceOf(SQLException.class)
                .hasMessage("the process is dead");
        }
    }

    @Test
    void aWrappedCallThatFailsThrowsTheDriversException() throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("SELECT * FROM missing"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("missing");
        }
    }

    @Test
    void theDataSourceItselfWrapsNothingAndKeepsNoLogSettings() {
        source.setLogWriter(null);
        source.setLoginTimeout(30);

        assertThat(source.getLogWriter()).isNull();
        assertThat(source.getLoginTimeout()).isZero();
        assertThat(source.getParentLogger()).isSameAs(Logger.getGlobal());
        assertThat(source.isWrapperFor(Connection.class)).isFalse();
        assertThatThrownBy(() -> source.unwrap(Connection.class))
            .isInstanceOf(SQLException.class)
            .hasMessage("not a wrapper");
    }

    @Test
    void theDataSourceUnwrapsToItselfForATypeItImplements() throws SQLException {
        assertThat(source.isWrapperFor(DataSource.class)).isTrue();
        assertThat(source.isWrapperFor(FaultyDataSource.class)).isTrue();
        assertThat(source.unwrap(DataSource.class)).isSameAs(source);
        assertThat(source.unwrap(FaultyDataSource.class)).isSameAs(source);
    }

    private void insert(int id) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    /** Inserts {@code id} in a transaction and commits it, rolling back on failure; the commit failure, or null. */
    private Throwable commitInsert(int id) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setInt(1, id);
                statement.executeUpdate();
            }
            try {
                connection.commit();
                return null;
            } catch (SQLException failure) {
                connection.rollback();
                return failure;
            }
        }
    }

    private List<Integer> ids() throws SQLException {
        return ids(source);
    }

    private static List<Integer> ids(FaultyDataSource from) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection connection = from.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id FROM item ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ids.add(result.getInt(1));
            }
        }
        return ids;
    }
}
