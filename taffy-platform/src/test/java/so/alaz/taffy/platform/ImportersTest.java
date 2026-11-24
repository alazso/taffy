package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.CapturedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ImportersTest {

    private final CapturedLog log = new CapturedLog();
    private final List<String> reported = new CopyOnWriteArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final Importers importers;

    ImportersTest() {
        errors.install((source, error) -> reported.add(source + " " + error.getMessage()));
        importers = Importers.create(log.logger, errors);
    }

    /** An importer with a fixed id whose source is present when {@code check} says so. */
    private record Source(String id, BooleanSupplier check) implements Importer {

        Source(String id, boolean present) {
            this(id, () -> present);
        }

        @Override
        public String displayName() {
            return id + " plugin";
        }

        @Override
        public boolean present() {
            return check.getAsBoolean();
        }

        @Override
        public CompletableFuture<ImportReport> run(boolean dryRun) {
            return CompletableFuture.completedFuture(ImportReport.builder().build());
        }
    }

    @Test
    void anEmptyRegistryFindsNothing() {
        assertThat(importers.allIds()).isEmpty();
        assertThat(importers.presentIds()).isEmpty();
        assertThat(importers.byId("crazyvouchers")).isEmpty();
    }

    @Test
    void anImporterIsFoundByItsIdInAnyLetterCase() {
        Source vouchers = new Source("crazyvouchers", false);
        Source crates = new Source("CrazyCrates", true);
        importers.register(vouchers).register(crates);

        assertThat(importers.byId("crazyvouchers")).containsSame(vouchers);
        assertThat(importers.byId("CRAZYVOUCHERS")).containsSame(vouchers);
        assertThat(importers.byId("crazycrates")).containsSame(crates);
        assertThat(importers.byId("crazy")).isEmpty();
        assertThat(importers.byId("")).isEmpty();
        assertThatThrownBy(() -> importers.byId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void idsAreGivenInRegistrationOrderAsTheImporterWroteThem() {
        importers.register(new Source("zeta", true))
            .register(new Source("Alpha", false))
            .register(new Source("mid.source_2-b", true));

        assertThat(importers.allIds()).containsExactly("zeta", "Alpha", "mid.source_2-b");
        assertThat(importers.presentIds()).containsExactly("zeta", "mid.source_2-b");
        assertThatThrownBy(() -> importers.allIds().add("more")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> importers.presentIds().add("more"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void presentIdsAsksEveryImporterAgainInEachCall() {
        AtomicBoolean installed = new AtomicBoolean();
        AtomicInteger asked = new AtomicInteger();
        importers.register(new Source("crazyvouchers", () -> {
            asked.incrementAndGet();
            return installed.get();
        }));

        assertThat(importers.presentIds()).isEmpty();
        installed.set(true);
        assertThat(importers.presentIds()).containsExactly("crazyvouchers");
        assertThat(importers.allIds()).containsExactly("crazyvouchers");
        assertThat(importers.byId("crazyvouchers")).isPresent();
        assertThat(asked).hasValue(2);
    }

    @Test
    void theIdIsReadOnceWhenTheImporterIsRegistered() {
        AtomicInteger asked = new AtomicInteger();
        Importer renamed = new Importer() {
            @Override
            public String id() {
                return asked.getAndIncrement() == 0 ? "first" : "later";
            }

            @Override
            public String displayName() {
                return "Renamed";
            }

            @Override
            public boolean present() {
                return true;
            }

            @Override
            public CompletableFuture<ImportReport> run(boolean dryRun) {
                return CompletableFuture.completedFuture(ImportReport.builder().build());
            }
        };
        importers.register(renamed);

        assertThat(importers.allIds()).containsExactly("first");
        assertThat(importers.presentIds()).containsExactly("first");
        assertThat(importers.byId("first")).containsSame(renamed);
        assertThat(importers.byId("later")).isEmpty();
        assertThat(asked).hasValue(1);
    }

    @Test
    void aSecondImporterUnderAnIdIsRefusedInAnyLetterCaseAndTheFirstStays() {
        Source first = new Source("CrazyVouchers", true);
        importers.register(first);

        assertThatThrownBy(() -> importers.register(new Source("crazyvouchers", true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("An importer is already registered under the id 'CrazyVouchers'");
        assertThatThrownBy(() -> importers.register(first)).isInstanceOf(IllegalArgumentException.class);
        assertThat(importers.allIds()).containsExactly("CrazyVouchers");
        assertThat(importers.byId("crazyvouchers")).containsSame(first);
    }

    @Test
    void anIdACommandCannotTakeIsRefused() {
        for (String id : List.of("", " ", "crazy vouchers", "crazy:vouchers", "vouchérs", "a".repeat(65))) {
            assertThatThrownBy(() -> importers.register(new Source(id, true)))
                .as(id)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The importer id '" + id
                    + "' is not 1 to 64 ASCII letters, digits, dots, underscores and hyphens");
        }
        importers.register(new Source("a".repeat(64), true));

        assertThat(importers.allIds()).containsExactly("a".repeat(64));
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThatThrownBy(() -> Importers.create(null, errors)).isInstanceOf(NullPointerException.class)
            .hasMessage("logger");
        assertThatThrownBy(() -> Importers.create(log.logger, null)).isInstanceOf(NullPointerException.class)
            .hasMessage("errors");
        assertThatThrownBy(() -> importers.register(null)).isInstanceOf(NullPointerException.class)
            .hasMessage("importer");
        assertThatThrownBy(() -> importers.register(new Source(null, true)))
            .isInstanceOf(NullPointerException.class).hasMessage("the importer's id");
        assertThat(importers.allIds()).isEmpty();
    }

    @Test
    void anImporterWhosePresenceCheckThrowsCountsAsAbsentAndIsLoggedOnceAndReported() {
        IllegalStateException failure = new IllegalStateException("no folder");
        importers.register(new Source("Broken", () -> {
            throw failure;
        })).register(new Source("unlinked", () -> {
            throw new NoClassDefFoundError("gone/Api");
        })).register(new Source("working", true));

        assertThat(importers.presentIds()).containsExactly("working");
        assertThat(importers.presentIds()).containsExactly("working");

        assertThat(importers.allIds()).containsExactly("Broken", "unlinked", "working");
        assertThat(log.lines()).containsExactly(
            "WARNING The importer Broken could not tell whether its source is present, so it counts as absent"
                + " (java.lang.IllegalStateException: no folder).",
            "WARNING The importer unlinked could not tell whether its source is present, so it counts as absent"
                + " (java.lang.NoClassDefFoundError: gone/Api).");
        assertThat(log.records.get(0).getThrown()).isSameAs(failure);
        assertThat(reported).containsExactly("importer.broken no folder", "importer.unlinked gone/Api",
            "importer.broken no folder", "importer.unlinked gone/Api");
    }

    @Test
    void anErrorOfThePresenceCheckThatIsNotALinkageErrorPropagates() {
        importers.register(new Source("deep", () -> {
            throw new StackOverflowError("too deep");
        }));

        assertThatThrownBy(importers::presentIds).isInstanceOf(StackOverflowError.class);
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void importersRegisteredFromSeveralThreadsAreAllKeptAndAnIdIsTakenOnce() throws Exception {
        int threads = 8;
        int each = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> running = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int number = thread;
                running.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        importers.register(new Source("own-" + number + "-" + i, true));
                        try {
                            importers.register(new Source((number % 2 == 0 ? "shared-" : "SHARED-") + i, true));
                        } catch (IllegalArgumentException taken) {
                            refused.incrementAndGet();
                        }
                        assertThat(importers.presentIds()).contains("own-" + number + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(importers.allIds()).hasSize(threads * each + each).doesNotHaveDuplicates();
        assertThat(refused).hasValue((threads - 1) * each);
        assertThat(importers.byId("shared-7")).isPresent();
    }

    /** The report of ProVouchers' {@code Migrator.migrate()}: lines only, counted by their number. */
    private record VoucherReport(List<String> imported, List<String> skipped, List<String> warnings) {
    }

    /** ProVouchers' importer in this shape: it has no dry run of its own and reads its files where it is called. */
    private static final class VoucherImporter implements Importer {

        private final VoucherReport source;
        private final List<String> written = new ArrayList<>();

        VoucherImporter(VoucherReport source) {
            this.source = source;
        }

        @Override
        public String id() {
            return "crazyvouchers";
        }

        @Override
        public String displayName() {
            return "CrazyVouchers";
        }

        @Override
        public boolean present() {
            return true;
        }

        @Override
        public CompletableFuture<ImportReport> run(boolean dryRun) {
            ImportReport.Builder report = ImportReport.builder()
                .read(source.imported().size() + source.skipped().size());
            for (String voucher : source.imported()) {
                if (!dryRun) {
                    written.add(voucher);
                }
                report.imported(voucher);
            }
            source.skipped().forEach(report::skipped);
            source.warnings().forEach(report::warning);
            return CompletableFuture.completedFuture(report.build());
        }
    }

    @Test
    void theProVouchersReportFitsWithEveryLineItsCommandShows() {
        VoucherReport source = new VoucherReport(List.of("gold", "code SUMMER"),
            List.of("vip: a voucher of this id exists"), List.of("gold: options.glow has no equivalent"));
        VoucherImporter importer = new VoucherImporter(source);
        importers.register(importer);

        ImportReport dry = importers.byId("CrazyVouchers").orElseThrow().run(true).join();
        assertThat(importer.written).isEmpty();
        ImportReport real = importer.run(false).join();

        assertThat(importer.written).containsExactly("gold", "code SUMMER");
        assertThat(real).isEqualTo(dry);
        assertThat(new VoucherReport(real.imported(), real.skipped(), real.warnings())).isEqualTo(source);
        assertThat(real.importedCount()).isEqualTo(source.imported().size());
        assertThat(real.skippedCount()).isEqualTo(source.skipped().size());
        assertThat(real.read()).isEqualTo(3);
        assertThat(importer.displayName()).isEqualTo("CrazyVouchers");
    }

    /** The report of Ledger's {@code LedgerMigrator.importInto}: three counts and the lines of what went wrong. */
    private record BalanceReport(int read, int imported, int skipped, List<String> errors) {
    }

    /** Ledger's balance import in this shape: one row for each player, counted and not named. */
    private static final class BalanceImporter implements Importer {

        private final List<Double> balances;
        private final List<Double> written = new ArrayList<>();

        BalanceImporter(List<Double> balances) {
            this.balances = balances;
        }

        @Override
        public String id() {
            return "vault";
        }

        @Override
        public String displayName() {
            return "Vault";
        }

        @Override
        public boolean present() {
            return !balances.isEmpty();
        }

        @Override
        public CompletableFuture<ImportReport> run(boolean dryRun) {
            return CompletableFuture.supplyAsync(() -> {
                ImportReport.Builder report = ImportReport.builder();
                for (int row = 0; row < balances.size(); row++) {
                    double balance = balances.get(row);
                    report.read(1);
                    if (Double.isNaN(balance)) {
                        report.skipped(1).warning("Invalid numeric balance for row " + row + ": " + balance);
                    } else if (balance == 0) {
                        report.skipped(1);
                    } else {
                        if (!dryRun) {
                            written.add(balance);
                        }
                        report.imported(1);
                    }
                }
                return report.build();
            });
        }
    }

    @Test
    void theLedgerCountsAndDryRunFitWithoutALineForEachPlayer() {
        BalanceImporter importer = new BalanceImporter(List.of(10.0, 0.0, Double.NaN, 2.5, 7.0));
        importers.register(importer);
        BalanceReport expected = new BalanceReport(5, 3, 2, List.of("Invalid numeric balance for row 2: NaN"));

        ImportReport dry = importer.run(true).join();
        assertThat(importer.written).isEmpty();
        ImportReport real = importer.run(false).join();

        assertThat(importer.written).containsExactly(10.0, 2.5, 7.0);
        assertThat(real).isEqualTo(dry);
        assertThat(new BalanceReport(real.read(), real.importedCount(), real.skippedCount(), real.warnings()))
            .isEqualTo(expected);
        assertThat(real.imported()).isEmpty();
        assertThat(real.skipped()).isEmpty();
        assertThat(importers.presentIds()).containsExactly("vault");
    }
}
