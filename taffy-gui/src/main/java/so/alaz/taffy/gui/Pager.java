package so.alaz.taffy.gui;

/** Splits a list into pages that each hold up to a given capacity of entries. */
public final class Pager {

    private Pager() {
    }

    /**
     * Gives how many entries a page holds when a menu shows at most {@code pageSize} entries in {@code slots} list
     * slots.
     *
     * @param pageSize the most entries a page shows
     * @param slots the number of list slots in the menu
     * @return the smaller of {@code pageSize} and {@code slots}
     */
    static int capacity(int pageSize, int slots) {
        return Math.min(pageSize, slots);
    }

    /**
     * Gives the page closest to {@code requested}. A list of {@code count} entries has
     * {@code ceil(count / capacity)} pages, and an empty list has one empty page. A requested page below 0 gives the
     * first page, and a requested page past the last gives the last page.
     *
     * @param count the number of entries in the list
     * @param capacity the most entries a page holds
     * @param requested the index of the page asked for, counting from 0
     * @return the page shown
     * @throws IllegalArgumentException if {@code count} is negative or {@code capacity} is below 1
     */
    public static Page page(int count, int capacity, int requested) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a page holds at least one row, not " + capacity);
        }
        if (count < 0) {
            throw new IllegalArgumentException("a list holds at least zero entries, not " + count);
        }
        int pages = Math.max(1, Math.ceilDiv(count, capacity));
        int index = Math.clamp(requested, 0, pages - 1);
        int from = Math.min(count, index * capacity);
        int to = from + Math.min(capacity, count - from);
        return new Page(index, pages, from, to);
    }

    /**
     * One page of a list.
     *
     * @param index the index of this page, counting from 0
     * @param pages the number of pages, at least 1
     * @param from the index of the first entry on this page, inclusive
     * @param to the index after the last entry on this page, exclusive; equal to {@code from} on an empty page
     */
    public record Page(int index, int pages, int from, int to) {

        /**
         * Checks the numbers.
         *
         * @throws IllegalArgumentException if {@code pages} is below 1, {@code index} is outside 0 to
         *     {@code pages - 1}, {@code from} is negative or {@code to} is below {@code from}
         */
        public Page {
            if (pages < 1) {
                throw new IllegalArgumentException("a list has at least one page, not " + pages);
            }
            if (index < 0 || index >= pages) {
                throw new IllegalArgumentException("page " + index + " is outside 0 to " + (pages - 1));
            }
            if (from < 0) {
                throw new IllegalArgumentException("a page starts at entry 0 or later, not " + from);
            }
            if (to < from) {
                throw new IllegalArgumentException("a page ends at or after its start " + from + ", not at " + to);
            }
        }

        /**
         * Tells whether a page comes before this one.
         *
         * @return whether {@code index} is above 0
         */
        public boolean previous() {
            return index > 0;
        }

        /**
         * Tells whether a page comes after this one.
         *
         * @return whether {@code index} is below {@code pages - 1}
         */
        public boolean next() {
            return index < pages - 1;
        }
    }
}
