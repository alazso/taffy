package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one run of an {@link Importer} did, or would do when it was a dry run: how many entries of the source it read,
 * how many it imported and how many it skipped, with a line for each entry the importer wants to name, and the
 * warnings of the run.
 *
 * <p>An importer counts an entry with a line or without one. An importer of a few files names each one, as in
 * {@code imported("gold-voucher")} and {@code skipped("vip: the id is taken")}, and its counts are then the numbers of
 * its lines. An importer of many rows, such as one balance for each player, counts them with {@code imported(1)} and
 * names only what the owner has to look at. {@link #importedCount()} and {@link #skippedCount()} hold both kinds.
 *
 * <p>Every line is plain text: no MiniMessage tag and no colour code in it is meant as one. A plugin shows a line as
 * text, as {@code Component.text(line)} or as the value of an unparsed placeholder of one of its messages, and never
 * parses it. Lines hold text read from another plugin's files, so a plugin that writes them to a public place passes
 * them through {@link Redaction#hide} first. The report keeps the lines as given, in the order they were added, and
 * drops none.
 *
 * <pre>{@code
 * ImportReport.Builder report = ImportReport.builder();
 * for (SourceVoucher voucher : source) {
 *     report.read(1);
 *     if (existing.contains(voucher.id())) {
 *         report.skipped(voucher.id() + ": a voucher of this id exists");
 *     } else {
 *         if (!dryRun) {
 *             write(voucher);
 *         }
 *         report.imported(voucher.id());
 *     }
 * }
 * return report.build();
 * }</pre>
 *
 * <p>A report is immutable and safe to read from any thread. Two reports are equal when their counts and lines are.
 *
 * @since 0.4.15
 */
public final class ImportReport {

    private final int read;
    private final int importedCount;
    private final int skippedCount;
    private final List<String> imported;
    private final List<String> skipped;
    private final List<String> warnings;

    private ImportReport(Builder builder) {
        this.read = builder.read;
        this.imported = List.copyOf(builder.imported);
        this.skipped = List.copyOf(builder.skipped);
        this.warnings = List.copyOf(builder.warnings);
        this.importedCount = Math.addExact(builder.importedUnnamed, imported.size());
        this.skippedCount = Math.addExact(builder.skippedUnnamed, skipped.size());
    }

    /**
     * Starts an empty report: every count is 0 and there is no line.
     *
     * @return the builder
     * @since 0.4.15
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Collects the counts and lines of one run. Not thread-safe: the thread that runs the import builds the report.
     *
     * @since 0.4.15
     */
    public static final class Builder {

        private final List<String> imported = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int read;
        private int importedUnnamed;
        private int skippedUnnamed;

        private Builder() {
        }

        /**
         * Counts {@code count} more entries of the source as read, whatever became of them.
         *
         * @param count how many entries, 0 or more
         * @return this builder
         * @throws IllegalArgumentException if {@code count} is negative
         * @throws ArithmeticException if the count passes {@link Integer#MAX_VALUE}
         * @since 0.4.15
         */
        public Builder read(int count) {
            read = Math.addExact(read, atLeastZero(count));
            return this;
        }

        /**
         * Counts one entry as imported, or as one a real run would import, and names it.
         *
         * @param line what was imported, as plain text
         * @return this builder
         * @throws NullPointerException if {@code line} is {@code null}
         * @since 0.4.15
         */
        public Builder imported(String line) {
            imported.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /**
         * Counts {@code count} more entries as imported, or as ones a real run would import, without a line.
         *
         * @param count how many entries, 0 or more
         * @return this builder
         * @throws IllegalArgumentException if {@code count} is negative
         * @throws ArithmeticException if the count passes {@link Integer#MAX_VALUE}
         * @since 0.4.15
         */
        public Builder imported(int count) {
            importedUnnamed = Math.addExact(importedUnnamed, atLeastZero(count));
            return this;
        }

        /**
         * Counts one entry as not imported and names it with the reason.
         *
         * @param line what was skipped and why, as plain text
         * @return this builder
         * @throws NullPointerException if {@code line} is {@code null}
         * @since 0.4.15
         */
        public Builder skipped(String line) {
            skipped.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /**
         * Counts {@code count} more entries as not imported, without a line.
         *
         * @param count how many entries, 0 or more
         * @return this builder
         * @throws IllegalArgumentException if {@code count} is negative
         * @throws ArithmeticException if the count passes {@link Integer#MAX_VALUE}
         * @since 0.4.15
         */
        public Builder skipped(int count) {
            skippedUnnamed = Math.addExact(skippedUnnamed, atLeastZero(count));
            return this;
        }

        /**
         * Adds a warning: something the owner should know that is not one skipped entry, such as a setting of the
         * source that has no equivalent, or an entry that was imported without one of its parts. A warning counts
         * nothing.
         *
         * @param line the warning, as plain text
         * @return this builder
         * @throws NullPointerException if {@code line} is {@code null}
         * @since 0.4.15
         */
        public Builder warning(String line) {
            warnings.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /**
         * The report of what was added so far. The builder can be added to and built again, which leaves the reports
         * built before unchanged.
         *
         * @return the report
         * @throws ArithmeticException if the imported or the skipped entries, with and without a line, pass
         *     {@link Integer#MAX_VALUE}
         * @since 0.4.15
         */
        public ImportReport build() {
            return new ImportReport(this);
        }

        private static int atLeastZero(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count is negative: " + count);
            }
            return count;
        }
    }

    /**
     * How many entries of the source the run read.
     *
     * @return the count, 0 or more
     * @since 0.4.15
     */
    public int read() {
        return read;
    }

    /**
     * How many entries the run imported, or would import when it was a dry run: those with a line and those without.
     *
     * @return the count, never less than the size of {@link #imported()}
     * @since 0.4.15
     */
    public int importedCount() {
        return importedCount;
    }

    /**
     * How many entries the run did not import: those with a line and those without.
     *
     * @return the count, never less than the size of {@link #skipped()}
     * @since 0.4.15
     */
    public int skippedCount() {
        return skippedCount;
    }

    /**
     * The lines of the imported entries that were named, in the order they were added.
     *
     * @return the lines, unmodifiable
     * @since 0.4.15
     */
    public List<String> imported() {
        return imported;
    }

    /**
     * The lines of the skipped entries that were named, each with its reason, in the order they were added.
     *
     * @return the lines, unmodifiable
     * @since 0.4.15
     */
    public List<String> skipped() {
        return skipped;
    }

    /**
     * The warnings of the run, in the order they were added.
     *
     * @return the lines, unmodifiable
     * @since 0.4.15
     */
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ImportReport report
            && read == report.read
            && importedCount == report.importedCount
            && skippedCount == report.skippedCount
            && imported.equals(report.imported)
            && skipped.equals(report.skipped)
            && warnings.equals(report.warnings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(read, importedCount, skippedCount, imported, skipped, warnings);
    }

    /**
     * The counts of the report, for a log line: the lines themselves are left out.
     *
     * @return such as {@code ImportReport[read=3, imported=2, skipped=1, warnings=0]}
     */
    @Override
    public String toString() {
        return "ImportReport[read=" + read + ", imported=" + importedCount + ", skipped=" + skippedCount
            + ", warnings=" + warnings.size() + "]";
    }
}
