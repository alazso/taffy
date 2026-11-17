package so.alaz.taffy.comficonf;

import com.comficonf.api.ApplyContext;
import com.comficonf.api.ApplyResult;
import com.comficonf.api.CallbackThread;
import com.comficonf.api.ComfiConfApi;
import com.comficonf.api.ConfigProvider;
import com.comficonf.api.ConfigResource;
import com.comficonf.api.ConfigSnapshot;
import com.comficonf.api.ConfigValues;
import com.comficonf.api.ResourceRegistration;
import com.comficonf.api.ValidationResult;
import com.comficonf.api.io.SafeFileWriter;
import com.comficonf.api.schema.ConfigField;
import com.comficonf.api.schema.ConfigSchema;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComfiConfRegistrationTest {

    private final Plugin owner = mock(Plugin.class);
    private final ServicesManager services = mock(ServicesManager.class);
    private final ComfiConfApi api = mock(ComfiConfApi.class);
    private final ResourceRegistration registration = mock(ResourceRegistration.class);
    private final ConfigProvider provider = new NothingProvider();

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        when(services.load(ComfiConfApi.class)).thenReturn(api);
        when(api.register(any(), any())).thenReturn(registration);
    }

    private ComfiConfLink register() {
        return ComfiConfRegistration.register(owner, "taffy:test", "Test settings", "Settings of the test plugin.",
            provider);
    }

    @Test
    void registersTheResourceWithTheOwnerOnTheWorkerThread() {
        register();

        ArgumentCaptor<ConfigResource> resource = ArgumentCaptor.forClass(ConfigResource.class);
        verify(api).register(same(owner), resource.capture());
        assertThat(resource.getValue().id().toString()).isEqualTo("taffy:test");
        assertThat(resource.getValue().displayName()).isEqualTo("Test settings");
        assertThat(resource.getValue().description()).isEqualTo("Settings of the test plugin.");
        assertThat(resource.getValue().provider()).isSameAs(provider);
        assertThat(resource.getValue().callbackThread()).isEqualTo(CallbackThread.WORKER);
    }

    @Test
    void theLinkGivesTheIdAndDelegatesToTheRegistration() {
        ComfiConfLink link = register();

        assertThat(link.resourceId()).isEqualTo("taffy:test");
        when(registration.isActive()).thenReturn(true);
        assertThat(link.isRegistered()).isTrue();
        when(registration.isActive()).thenReturn(false);
        assertThat(link.isRegistered()).isFalse();
        link.notifyChanged();
        verify(registration).notifyChanged();
        link.close();
        verify(registration).unregister();
    }

    @Test
    void aSecondCloseIsHarmless() {
        ComfiConfLink link = register();

        link.close();
        link.close();

        verify(registration, times(2)).unregister();
    }

    @Test
    void aMissingBridgeServiceThrowsAndRegistersNothing() {
        when(services.load(ComfiConfApi.class)).thenReturn(null);

        assertThatThrownBy(this::register).isInstanceOf(IllegalStateException.class)
            .hasMessage("No ComfiConf API service matches this plugin's com.comficonf.api classes: the bridge is not"
                + " enabled, or this plugin bundles its own copy of com.comficonf.api");
        verifyNoInteractions(api);
    }

    @Test
    void theLockForAFileIsTheOneEverySafeFileWriterWriteOfItTakes() {
        ComfiConfLink link = register();
        Path file = Path.of("plugins", "Test", "config.yml");

        assertThat(link.lockFor(file)).isSameAs(SafeFileWriter.lockFor(file))
            .isSameAs(link.lockFor(file.toAbsolutePath()));
    }

    @Test
    void anInvalidIdThrowsFromTheBuilderBeforeTheBridgeIsAsked() {
        assertThatThrownBy(() -> ComfiConfRegistration.register(owner, "Taffy Test", "Test settings",
            "Settings of the test plugin.", provider)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(services, api);
    }

    @Test
    void aDisplayNameOverEightyCharactersThrowsFromTheBuilder() {
        assertThatThrownBy(() -> ComfiConfRegistration.register(owner, "taffy:test", "x".repeat(81),
            "Settings of the test plugin.", provider)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);
    }

    @Test
    void aRefusedRegistrationPropagates() {
        IllegalArgumentException taken = new IllegalArgumentException("taffy:test is already registered");
        when(api.register(any(), any())).thenThrow(taken);

        assertThatThrownBy(this::register).isSameAs(taken);
    }

    /** A provider whose callbacks are never called here. */
    private static final class NothingProvider implements ConfigProvider {

        @Override
        public ConfigSchema describe() {
            return ConfigSchema.of(ConfigField.string("greeting"));
        }

        @Override
        public ConfigSnapshot read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ValidationResult validate(ConfigValues proposed) {
            return ValidationResult.ok();
        }

        @Override
        public ApplyResult apply(ApplyContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
