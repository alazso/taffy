package so.alaz.taffy.comficonf;

import com.comficonf.api.CallbackThread;
import com.comficonf.api.ComfiConfApi;
import com.comficonf.api.ConfigProvider;
import com.comficonf.api.ConfigResource;
import com.comficonf.api.ResourceRegistration;
import com.comficonf.api.io.SafeFileWriter;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.concurrent.locks.Lock;

/**
 * Registers one settings resource with the running ComfiConf bridge.
 *
 * <p>This class names {@code com.comficonf.api} types, so loading it on a server without the bridge fails with a
 * {@link NoClassDefFoundError}. The plugin calls {@link #register} only from its own ComfiConf integration class,
 * which it reaches only through the supplier it passes to {@link ComfiConfConnection#connect}, after the settings
 * file loaded in {@code onEnable}.
 */
public final class ComfiConfRegistration implements ComfiConfLink {

    private final String id;
    private final ResourceRegistration registration;

    private ComfiConfRegistration(String id, ResourceRegistration registration) {
        this.id = id;
        this.registration = registration;
    }

    /**
     * Registers the resource {@code id} with the running bridge. Its provider callbacks run on a bridge worker
     * thread. Call from {@code onEnable}, never from {@code onLoad}, a constructor or a static initializer.
     *
     * @param owner the plugin that owns the resource
     * @param id the resource id, {@code namespace:name} with each part matching {@code [a-z0-9][a-z0-9._-]{0,63}};
     *     the editor and the change history refer to it, so it never changes once released
     * @param displayName the name shown in the editor, at most 80 characters
     * @param description the description shown in the editor, at most 500 characters
     * @param provider the callbacks of the resource
     * @return the link the plugin keeps until {@code onDisable}
     * @throws IllegalArgumentException if the id or a text is invalid, or the id is already registered
     * @throws IllegalStateException if the bridge or {@code owner} is not enabled, or the bridge's API service is not
     *     the one this plugin sees, which happens when the plugin bundles its own copy of {@code com.comficonf.api}
     */
    public static ComfiConfLink register(Plugin owner, String id, String displayName, String description,
                                         ConfigProvider provider) {
        ConfigResource resource = ConfigResource.builder(id)
            .displayName(displayName)
            .description(description)
            .provider(provider)
            .callbackThread(CallbackThread.WORKER)
            .build();
        ComfiConfApi api = owner.getServer().getServicesManager().load(ComfiConfApi.class);
        if (api == null) {
            throw new IllegalStateException("No ComfiConf API service matches this plugin's com.comficonf.api classes:"
                + " the bridge is not enabled, or this plugin bundles its own copy of com.comficonf.api");
        }
        return new ComfiConfRegistration(id, api.register(owner, resource));
    }

    @Override
    public String resourceId() {
        return id;
    }

    @Override
    public boolean isRegistered() {
        return registration.isActive();
    }

    @Override
    public void notifyChanged() {
        registration.notifyChanged();
    }

    @Override
    public void close() {
        registration.unregister();
    }

    /** The lock of {@link SafeFileWriter#lockFor(Path)}, which every {@link SafeFileWriter} write of the path takes. */
    @Override
    public Lock lockFor(Path file) {
        return SafeFileWriter.lockFor(file);
    }
}
