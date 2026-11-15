package so.alaz.taffy.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.RaceRunner;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class StorageContract {

    static final Instant NOW = Instant.parse("2026-11-02T10:15:30.123Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final List<String> COLUMNS = List.of("id", "state", "amount", "at", "doc", "data");

    abstract Storage storage();

    abstract String name();

    abstract String createProbe(String table);

    String probe() {
        return storage().table("probe");
    }

    @BeforeEach
    void freshProbeTable() {
        storage().async(tx -> {
            tx.execute("DROP TABLE IF EXISTS " + probe());
            tx.execute(createProbe(probe()));
            return null;
        }).join();
    }

    @Test
    void everyBoundTypeRoundTripsExactly() {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("922337203685477.5807");
        Instant at = Instant.ofEpochMilli(1_798_000_000_123L);
        byte[] data = {0, 1, 2, (byte) 0xFF};

        insert(id, "A", amount, at, "{\"a\":1}", data);
        Object[] row = storage().read(tx -> tx.queryOne("SELECT * FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id),
            found -> new Object[] {found.uuid("id"), found.text("state"), found.money("amount"), found.time("at"),
                found.json("doc"), found.bytes("data")}).orElseThrow()).join();

        assertThat(row[0]).isEqualTo(id);
        assertThat(row[1]).isEqualTo("A");
        assertThat((BigDecimal) row[2]).isEqualByComparingTo(amount);
        assertThat(row[3]).isEqualTo(at);
        assertThat(row[4].toString().replace(" ", "")).isEqualTo("{\"a\":1}");
        assertThat((byte[]) row[5]).containsExactly(data);
    }

    @Test
    void nullsRoundTripAsNull() {
        UUID id = UUID.randomUUID();
        insert(id, "A", null, null, null, null);

        List<Object> values = storage().read(tx -> tx.queryOne("SELECT * FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id),
            found -> java.util.Arrays.<Object>asList(found.money("amount"), found.time("at"), found.json("doc"),
                found.bytes("data"), found.integer("amount"))).orElseThrow()).join();

        assertThat(values).containsOnlyNulls();
    }

    @Test
    void moneySortsInNumericOrder() {
        for (String amount : List.of("10", "9.5", "100", "0", "0.0001")) {
            insert(UUID.randomUUID(), "A", new BigDecimal(amount), NOW, null, null);
        }

        List<BigDecimal> sorted = storage().read(tx -> tx.query(
            "SELECT amount FROM " + probe() + " ORDER BY amount", Tx.Binder.NONE, row -> row.money("amount"))).join();

        assertThat(sorted).extracting(BigDecimal::toPlainString)
            .containsExactly("0.0000", "0.0001", "9.5000", "10.0000", "100.0000");
    }

    @Test
    void aNegativeAmountIsRefusedAndANegativeBalanceIsKept() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", new BigDecimal("-1"), NOW, null, null))
            .hasRootCauseInstanceOf(MoneyOutOfRangeException.class);
        storage().async(tx -> tx.update("INSERT INTO " + probe() + " (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(new BigDecimal("-5.5")))).join();

        assertThat(storage().read(tx -> tx.queryOne("SELECT amount FROM " + probe(), Tx.Binder.NONE,
            row -> row.money("amount")).orElseThrow()).join()).isEqualByComparingTo("-5.5");
    }

    @Test
    void anOverCapBalanceStoresNullAndAnOverCapAmountIsRefused() {
        UUID id = UUID.randomUUID();
        BigDecimal over = new BigDecimal("1e20");

        storage().async(tx -> tx.update(
            "INSERT INTO " + probe() + " (id, state, amount) VALUES (?, ?, ?)",
            params -> params.uuid(id).text("A").moneyOrNull(over))).join();
        List<BigDecimal> stored = storage().read(tx -> tx.query("SELECT amount FROM " + probe(), Tx.Binder.NONE,
            row -> row.money("amount"))).join();

        assertThat(stored).containsExactly((BigDecimal) null);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "A", over, NOW, null, null))
            .hasRootCauseInstanceOf(MoneyOutOfRangeException.class);
    }

    @Test
    void insertOrIgnoreCountsANewRowOnceAndNeverThrows() {
        UUID id = UUID.randomUUID();
        String sql = storage().dialect().insertOrIgnore(probe(), List.of("id", "state"), List.of("id"));

        int first = storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("A"))).join();
        int second = storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("B"))).join();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(state(id)).isEqualTo("A");
    }

    @Test
    void aConditionalUpdateHasExactlyOneWinner() {
        UUID id = UUID.randomUUID();
        insert(id, "ACTIVE", BigDecimal.ONE, NOW, null, null);
        String claim = "UPDATE " + probe() + " SET state = ? WHERE id = ? AND state = ?";

        int winner = storage().async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE")))
            .join();
        int loser = storage().async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE")))
            .join();

        assertThat(winner).isEqualTo(1);
        assertThat(loser).isZero();
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test
    void aRacedConditionalUpdateHasExactlyOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        insert(id, "ACTIVE", BigDecimal.ONE, NOW, null, null);
        String claim = "UPDATE " + probe() + " SET state = ? WHERE id = ? AND state = ?";

        List<Integer> changed = RaceRunner.race(16, contender -> storage()
            .async(tx -> tx.update(claim, params -> params.text("PENDING").uuid(id).text("ACTIVE"))).join());

        assertThat(changed).filteredOn(count -> count == 1).hasSize(1);
        assertThat(changed).filteredOn(count -> count == 0).hasSize(15);
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test
    void upsertInsertsThenUpdates() {
        UUID id = UUID.randomUUID();
        String sql = storage().dialect().upsert(probe(), List.of("id", "state"), List.of("id"), List.of("state"));

        storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("A"))).join();
        storage().async(tx -> tx.update(sql, params -> params.uuid(id).text("B"))).join();

        assertThat(state(id)).isEqualTo("B");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aFailedTransactionLeavesNothingBehind() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> storage().async(tx -> {
            tx.update("INSERT INTO " + probe() + " (id, state) VALUES (?, ?)", params -> params.uuid(id).text("A"));
            throw new SQLException("boom");
        }).join()).isInstanceOf(CompletionException.class).hasRootCauseMessage("boom");

        assertThat(count()).isZero();
    }

    @Test
    void workRunsOnADatabaseThreadWithTheApplicationClock() {
        String thread = storage().async(tx -> Thread.currentThread().getName()).join();
        Instant now = storage().async(Tx::now).join();

        assertThat(thread).startsWith(name() + "-db-");
        assertThat(now).isEqualTo(NOW);
    }

    @Test
    void aTransactionCannotBeUsedFromAnotherThreadOrAfterItEnds() {
        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Tx leaked = storage().async(tx -> {
            Thread other = new Thread(() -> {
                try {
                    tx.execute("SELECT 1");
                } catch (Throwable failure) {
                    crossThread.set(failure);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return tx;
        }).join();

        assertThat(crossThread.get()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only on the database thread");
        assertThatThrownBy(() -> leaked.execute("SELECT 1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("this transaction has ended");
    }

    @Test
    void queryOneRefusesSeveralRows() {
        insert(UUID.randomUUID(), "A", null, null, null, null);
        insert(UUID.randomUUID(), "A", null, null, null, null);

        assertThatThrownBy(() -> storage().read(tx -> tx.queryOne("SELECT id FROM " + probe(), Tx.Binder.NONE,
            row -> row.uuid("id"))).join()).hasRootCauseMessage(
            "expected at most one row, found 2: SELECT id FROM " + probe());
    }

    void insert(UUID id, String state, BigDecimal amount, Instant at, String doc, byte[] data) {
        String sql = "INSERT INTO " + probe() + " (" + String.join(", ", COLUMNS) + ") VALUES (?, ?, ?, ?, ?, ?)";
        storage().async(tx -> tx.update(sql,
            params -> params.uuid(id).text(state).money(amount).time(at).json(doc).bytes(data))).join();
    }

    String state(UUID id) {
        return storage().read(tx -> tx.queryOne("SELECT state FROM " + probe() + " WHERE id = ?",
            params -> params.uuid(id), row -> row.text("state")).orElseThrow()).join();
    }

    long count() {
        return storage().read(tx -> tx.queryOne("SELECT COUNT(*) AS n FROM " + probe(), Tx.Binder.NONE,
            row -> row.integer("n")).orElseThrow()).join();
    }
}
