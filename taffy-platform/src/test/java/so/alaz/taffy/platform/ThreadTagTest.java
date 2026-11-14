package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class ThreadTagTest {

    @Test
    void tagsPrintTheirNames() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(ThreadTag.GLOBAL).hasToString("GLOBAL");
        assertThat(ThreadTag.ASYNC).hasToString("ASYNC");
        assertThat(ThreadTag.entity(id)).hasToString("ENTITY(" + id + ")");
    }

    @Test
    void entityTagsCompareByTheirId() {
        UUID id = UUID.randomUUID();

        assertThat(ThreadTag.entity(id)).isEqualTo(ThreadTag.entity(id));
        assertThat(ThreadTag.entity(id)).isNotEqualTo(ThreadTag.entity(UUID.randomUUID()));
    }

    @Test
    void anUnavailableEntityNamesItsId() {
        UUID id = UUID.randomUUID();

        EntityUnavailableException failure = new EntityUnavailableException(id);

        assertThat(failure.id()).isEqualTo(id);
        assertThat(failure).hasMessageContaining(id.toString());
    }
}
