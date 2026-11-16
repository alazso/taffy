package so.alaz.taffy.gui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RouteTest {

    @Test
    void aRouteKeepsItsMenuStateAndBackRoute() {
        Route home = new Route("home", "first", null);
        Route entries = new Route("entries", 3, home);
        Route detail = new Route("detail", "row", entries);

        assertThat(home.back()).isNull();
        assertThat(detail.menu()).isEqualTo("detail");
        assertThat(detail.state()).isEqualTo("row");
        assertThat(detail.back()).isEqualTo(entries);
        assertThat(detail.back().state()).isEqualTo(3);
        assertThat(detail.back().back()).isEqualTo(new Route("home", "first", null));
    }

    @Test
    void theMenuAndStateAreRequired() {
        assertThatThrownBy(() -> new Route(null, "first", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("menu");
        assertThatThrownBy(() -> new Route("home", null, null)).isInstanceOf(NullPointerException.class)
            .hasMessage("state");
    }
}
