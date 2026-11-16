package so.alaz.taffy.gui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

final class PaperCancelLinkTest {

    @Test
    @SuppressWarnings("unchecked")
    void theLinkIsAOneUseCallbackThatLivesAsLongAsAnAskAndRunsTheAction() {
        ClickEvent made = ClickEvent.runCommand("/cancel-link-test");
        AtomicInteger runs = new AtomicInteger();
        ArgumentCaptor<ClickCallback<Audience>> callback = ArgumentCaptor.forClass(ClickCallback.class);
        ArgumentCaptor<ClickCallback.Options> options = ArgumentCaptor.forClass(ClickCallback.Options.class);

        try (MockedStatic<ClickEvent> events = mockStatic(ClickEvent.class)) {
            events.when(() -> ClickEvent.callback(any(ClickCallback.class), any(ClickCallback.Options.class)))
                .thenReturn(made);

            ClickEvent link = new PaperCancelLink().link(runs::incrementAndGet);

            assertThat(link).isSameAs(made);
            events.verify(() -> ClickEvent.callback(callback.capture(), options.capture()));
        }

        assertThat(options.getValue().uses()).isEqualTo(1);
        assertThat(options.getValue().lifetime()).isEqualTo(Prompts.TIMEOUT);
        assertThat(runs).hasValue(0);
        callback.getValue().accept(mock(Audience.class));
        assertThat(runs).hasValue(1);
    }

    @Test
    void eachLinkRunsItsOwnAction() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        try (MockedStatic<ClickEvent> events = mockStatic(ClickEvent.class)) {
            PaperCancelLink links = new PaperCancelLink();
            links.link(first::incrementAndGet);
            links.link(second::incrementAndGet);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<ClickCallback<Audience>> callback = ArgumentCaptor.forClass(ClickCallback.class);
            events.verify(() -> ClickEvent.callback(callback.capture(), any(ClickCallback.Options.class)),
                times(2));
            callback.getAllValues().get(1).accept(mock(Audience.class));
        }

        assertThat(first).hasValue(0);
        assertThat(second).hasValue(1);
    }
}
