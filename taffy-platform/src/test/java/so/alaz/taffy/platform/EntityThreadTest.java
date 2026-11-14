package so.alaz.taffy.platform;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EntityThreadTest {

    private final List<ThreadTag> dispatched = new ArrayList<>();
    private final Dispatcher dispatcher = this::run;
    private final Server server = mock(Server.class);
    private final UUID id = UUID.randomUUID();

    @Test
    void theTaskRunsThroughTheEntityTagWithTheOnlinePlayer() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        when(server.getPlayer(id)).thenReturn(player);

        String name = EntityThread.on(dispatcher, server).call(id, Player::getName).join();

        assertThat(name).isEqualTo("Alex");
        assertThat(dispatched).containsExactly(ThreadTag.entity(id));
    }

    @Test
    void aPlayerWhoLeftBeforeTheTaskRanIsUnavailable() {
        assertThatThrownBy(() -> EntityThread.on(dispatcher, server).call(id, Player::getName).join())
            .hasCauseInstanceOf(EntityUnavailableException.class);
        assertThat(dispatched).containsExactly(ThreadTag.entity(id));
    }

    private <T> CompletableFuture<T> run(ThreadTag tag, Supplier<T> task) {
        dispatched.add(tag);
        try {
            return CompletableFuture.completedFuture(task.get());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
