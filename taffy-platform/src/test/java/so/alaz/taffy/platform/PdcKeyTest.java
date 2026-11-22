package so.alaz.taffy.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdcKeyTest {

    private static final NamespacedKey OWNER = new NamespacedKey("taffy", "owner");
    private static final NamespacedKey USES = new NamespacedKey("taffy", "uses");

    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private final PersistentDataHolder holder = holder(data);

    @Test
    void getIsNullWhenTheKeyIsAbsent() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);
        new PdcKey<>(USES, PersistentDataType.INTEGER).set(holder, 3);

        assertThat(owner.get(holder)).isNull();
    }

    @Test
    void getReadsBackWhatSetWrote() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> uses = new PdcKey<>(USES, PersistentDataType.INTEGER);

        owner.set(holder, "Alex");
        uses.set(holder, 3);

        assertThat(owner.get(holder)).isEqualTo("Alex");
        assertThat(uses.get(holder)).isEqualTo(3);
        assertThat(data).containsOnly(Map.entry(OWNER, "Alex"), Map.entry(USES, 3));
    }

    @Test
    void hasIsTrueAfterSet() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);

        owner.set(holder, "Alex");

        assertThat(owner.has(holder)).isTrue();
    }

    @Test
    void hasIsFalseForAKeyThatWasNotSet() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> uses = new PdcKey<>(USES, PersistentDataType.INTEGER);

        assertThat(owner.has(holder)).isFalse();

        uses.set(holder, 3);

        assertThat(owner.has(holder)).isFalse();
    }

    @Test
    void hasIsFalseWhenTheKeyHoldsAnotherType() {
        PdcKey<String, String> text = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> number = new PdcKey<>(OWNER, PersistentDataType.INTEGER);

        text.set(holder, "Alex");

        assertThat(number.has(holder)).isFalse();
        assertThat(text.has(holder)).isTrue();
    }

    @Test
    void removeClearsTheValueAndLeavesOtherKeys() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> uses = new PdcKey<>(USES, PersistentDataType.INTEGER);
        owner.set(holder, "Alex");
        uses.set(holder, 3);

        owner.remove(holder);

        assertThat(owner.has(holder)).isFalse();
        assertThat(owner.get(holder)).isNull();
        assertThat(data).containsOnly(Map.entry(USES, 3));
    }

    @Test
    void removeLeavesAHolderWithoutTheKeyUnchanged() {
        PdcKey<String, String> owner = new PdcKey<>(OWNER, PersistentDataType.STRING);
        new PdcKey<>(USES, PersistentDataType.INTEGER).set(holder, 3);

        owner.remove(holder);

        assertThat(data).containsOnly(Map.entry(USES, 3));
    }

    @Test
    void removeClearsAValueOfAnotherType() {
        PdcKey<String, String> text = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> number = new PdcKey<>(OWNER, PersistentDataType.INTEGER);
        text.set(holder, "Alex");

        number.remove(holder);

        assertThat(data).isEmpty();
    }

    @Test
    void getOrDefaultIsTheFallbackOnlyWhenTheKeyIsAbsent() {
        PdcKey<Integer, Integer> uses = new PdcKey<>(USES, PersistentDataType.INTEGER);

        assertThat(uses.getOrDefault(holder, 5)).isEqualTo(5);

        uses.set(holder, 3);

        assertThat(uses.getOrDefault(holder, 5)).isEqualTo(3);
    }

    @Test
    void getOrDefaultThrowsWhenTheKeyHoldsAnotherType() {
        PdcKey<String, String> text = new PdcKey<>(OWNER, PersistentDataType.STRING);
        PdcKey<Integer, Integer> number = new PdcKey<>(OWNER, PersistentDataType.INTEGER);
        text.set(holder, "Alex");

        assertThatIllegalArgumentException().isThrownBy(() -> number.getOrDefault(holder, 5));
    }

    @Test
    void keyIsTheNamespacedKeyItWasBuiltWith() {
        assertThat(new PdcKey<>(OWNER, PersistentDataType.STRING).key()).isSameAs(OWNER);
    }

    // A holder whose container keeps its values in the map; has is true only for a value of the asked type.
    @SuppressWarnings("unchecked")
    private static PersistentDataHolder holder(Map<NamespacedKey, Object> data) {
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(container.get(any(NamespacedKey.class), any(PersistentDataType.class))).thenAnswer(call -> {
            Object value = data.get(call.<NamespacedKey>getArgument(0));
            if (value != null && !holds(call.getArgument(1), value)) {
                throw new IllegalArgumentException("the value is not of the asked type");
            }
            return value;
        });
        when(container.has(any(NamespacedKey.class), any(PersistentDataType.class))).thenAnswer(call -> {
            Object value = data.get(call.<NamespacedKey>getArgument(0));
            return value != null && holds(call.getArgument(1), value);
        });
        doAnswer(call -> data.put(call.getArgument(0), call.getArgument(2)))
            .when(container).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        doAnswer(call -> data.remove(call.<NamespacedKey>getArgument(0)))
            .when(container).remove(any(NamespacedKey.class));
        PersistentDataHolder holder = mock(PersistentDataHolder.class);
        when(holder.getPersistentDataContainer()).thenReturn(container);
        return holder;
    }

    private static boolean holds(PersistentDataType<?, ?> type, Object value) {
        return type.getComplexType().isInstance(value);
    }
}
