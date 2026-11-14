package so.alaz.taffy.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JvmDefaultsTest {

    private static final String TOKYO = "Asia/Tokyo";
    private static final String ST_JOHNS = "America/St_Johns";

    @Test
    void theBlockRunsUnderTheLocaleAndThePreviousOneIsBackAfterwards() throws Exception {
        Locale before = Locale.getDefault();
        List<Locale> seen = new ArrayList<>();

        JvmDefaults.withLocale(Locale.JAPAN, () -> {
            JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> seen.add(Locale.getDefault()));
            seen.add(Locale.getDefault());
        });

        assertThat(seen).containsExactly(JvmDefaults.TURKISH, Locale.JAPAN);
        assertThat(Locale.getDefault()).isEqualTo(before);
    }

    @Test
    void thePreviousLocaleIsBackWhenTheBlockThrows() throws Exception {
        Locale before = Locale.getDefault();
        IOException failure = new IOException("boom");

        JvmDefaults.withLocale(Locale.JAPAN, () -> {
            assertThatThrownBy(() -> JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
                assertThat(Locale.getDefault()).isEqualTo(JvmDefaults.TURKISH);
                throw failure;
            })).isSameAs(failure);
            assertThat(Locale.getDefault()).isEqualTo(Locale.JAPAN);
        });

        assertThat(Locale.getDefault()).isEqualTo(before);
    }

    @Test
    void theDisplayAndFormatLocalesFollowTheLocaleAndThePreviousOnesAreBackAfterwards() throws Exception {
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.FRANCE);
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            Locale before = Locale.getDefault();
            List<Locale> seen = new ArrayList<>();
            IOException failure = new IOException("boom");

            JvmDefaults.withLocale(Locale.JAPAN, () -> {
                seen.add(Locale.getDefault(Locale.Category.DISPLAY));
                seen.add(Locale.getDefault(Locale.Category.FORMAT));
                assertThat(String.format("%,.2f", 1234.5)).isEqualTo("1,234.50");
            });

            assertThat(seen).containsExactly(Locale.JAPAN, Locale.JAPAN);
            assertThat(Locale.getDefault()).isEqualTo(before);
            assertThat(Locale.getDefault(Locale.Category.DISPLAY)).isEqualTo(Locale.FRANCE);
            assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(Locale.GERMANY);
            assertThat(String.format("%,.2f", 1234.5)).isEqualTo("1.234,50");

            assertThatThrownBy(() -> JvmDefaults.withLocale(Locale.JAPAN, () -> {
                throw failure;
            })).isSameAs(failure);

            assertThat(Locale.getDefault()).isEqualTo(before);
            assertThat(Locale.getDefault(Locale.Category.DISPLAY)).isEqualTo(Locale.FRANCE);
            assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(Locale.GERMANY);
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void turkishChangesTheCaseOfTheLetterI() throws Exception {
        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat("TITLE".toLowerCase()).isEqualTo("tıtle");
            assertThat("title".toUpperCase()).isEqualTo("TİTLE");
        });
    }

    @Test
    void theBlockRunsUnderTheZoneAndThePreviousOneIsBackAfterwards() throws Exception {
        TimeZone before = TimeZone.getDefault();
        List<String> seen = new ArrayList<>();

        JvmDefaults.withTimeZone(TOKYO, () -> {
            JvmDefaults.withTimeZone(ST_JOHNS, () -> seen.add(TimeZone.getDefault().getID()));
            seen.add(TimeZone.getDefault().getID());
        });

        assertThat(seen).containsExactly(ST_JOHNS, TOKYO);
        assertThat(TimeZone.getDefault()).isEqualTo(before);
    }

    @Test
    void thePreviousZoneIsBackWhenTheBlockThrows() throws Exception {
        TimeZone before = TimeZone.getDefault();
        IOException failure = new IOException("boom");

        JvmDefaults.withTimeZone(TOKYO, () -> {
            assertThatThrownBy(() -> JvmDefaults.withTimeZone(ST_JOHNS, () -> {
                assertThat(TimeZone.getDefault().getID()).isEqualTo(ST_JOHNS);
                throw failure;
            })).isSameAs(failure);
            assertThat(TimeZone.getDefault().getID()).isEqualTo(TOKYO);
        });

        assertThat(TimeZone.getDefault()).isEqualTo(before);
    }

    @Test
    void anUnknownZoneIdGivesGmt() throws Exception {
        JvmDefaults.withTimeZone(TOKYO, () -> {
            List<String> seen = new ArrayList<>();

            JvmDefaults.withTimeZone("Nowhere/Nothing", () -> seen.add(TimeZone.getDefault().getID()));

            assertThat(seen).containsExactly("GMT");
            assertThat(TimeZone.getDefault().getID()).isEqualTo(TOKYO);
        });
    }
}
