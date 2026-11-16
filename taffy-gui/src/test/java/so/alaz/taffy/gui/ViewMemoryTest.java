package so.alaz.taffy.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ViewMemoryTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    private final ViewMemory memory = new ViewMemory();

    @Test
    void aValueIsKeptPerViewerAndMenu() {
        memory.put(ALEX, "entries", "items");
        memory.put(ALEX, "options", 2);

        assertThat(memory.get(ALEX, "entries", String.class)).isEqualTo("items");
        assertThat(memory.get(ALEX, "options", Integer.class)).isEqualTo(2);
        assertThat(memory.get(ALEX, "detail", String.class)).isNull();
        assertThat(memory.get(SAM, "entries", String.class)).isNull();
        memory.put(ALEX, "entries", "blocks");
        assertThat(memory.get(ALEX, "entries", String.class)).isEqualTo("blocks");
    }

    @Test
    void aValueOfAnotherTypeReadsAsNull() {
        memory.put(ALEX, "entries", "items");

        assertThat(memory.get(ALEX, "entries", Integer.class)).isNull();
        assertThat(memory.get(ALEX, "entries", CharSequence.class)).isEqualTo("items");
    }

    @Test
    void aNullValueIsRefused() {
        assertThatThrownBy(() -> memory.put(ALEX, "entries", null)).isInstanceOf(NullPointerException.class);
        assertThat(memory.get(ALEX, "entries", Object.class)).isNull();
    }

    @Test
    void forgetClearsOnlyThatViewer() {
        memory.put(ALEX, "entries", "items");
        memory.put(SAM, "entries", "blocks");

        memory.forget(ALEX);

        assertThat(memory.get(ALEX, "entries", String.class)).isNull();
        assertThat(memory.get(SAM, "entries", String.class)).isEqualTo("blocks");
    }
}
