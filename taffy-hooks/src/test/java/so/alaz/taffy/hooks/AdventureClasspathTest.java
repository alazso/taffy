package so.alaz.taffy.hooks;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class AdventureClasspathTest {

    private static final String MINI_MESSAGE = "adventure-text-minimessage-";

    private static String jarOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
    }

    @Test
    void miniMessageLoadsFromTheAdventureJarAndNotFromTheHologramLibJar() throws URISyntaxException {
        assertThat(jarOf(MiniMessage.class)).startsWith(MINI_MESSAGE);
    }

    @Test
    void theApiAndKeyClassesLoadFromAdventureJarsOfTheMiniMessageVersion() throws URISyntaxException {
        String version = jarOf(MiniMessage.class).substring(MINI_MESSAGE.length());

        assertThat(jarOf(Component.class)).isEqualTo("adventure-api-" + version);
        assertThat(jarOf(Key.class)).isEqualTo("adventure-key-" + version);
    }
}
