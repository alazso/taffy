package so.alaz.taffy.test;

import java.util.Locale;
import java.util.TimeZone;

/**
 * Runs a block under another JVM default locale or time zone, then puts the previous one back.
 *
 * <p>The defaults are JVM-wide state: every thread sees the change while the block runs. Not safe under parallel
 * test execution.
 */
public final class JvmDefaults {

    /** Turkish as used in Turkey, where the upper case of {@code i} and the lower case of {@code I} are not ASCII. */
    public static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    /** Test code that may throw. */
    public interface Block {

        /**
         * Runs the test code.
         *
         * @throws Exception whatever the test code throws
         */
        void run() throws Exception;
    }

    private JvmDefaults() {
    }

    /**
     * Runs {@code block} with {@code locale} as the default locale and as the default of both locale categories,
     * {@link Locale.Category#DISPLAY} and {@link Locale.Category#FORMAT}. The three previous defaults are back
     * when this returns or throws.
     *
     * @throws Exception whatever {@code block} throws
     */
    public static void withLocale(Locale locale, Block block) throws Exception {
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(locale);
        try {
            block.run();
        } finally {
            // Setting the default without a category also sets both category defaults.
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    /**
     * Runs {@code block} with the zone named {@code zoneId} as the default time zone. The previous default is back
     * when this returns or throws.
     *
     * @param zoneId a zone id as {@link TimeZone#getTimeZone(String)} reads it, where an unknown id gives GMT
     * @throws Exception whatever {@code block} throws
     */
    public static void withTimeZone(String zoneId, Block block) throws Exception {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        try {
            block.run();
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
