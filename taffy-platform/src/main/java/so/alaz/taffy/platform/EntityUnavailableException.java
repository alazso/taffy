package so.alaz.taffy.platform;

import java.util.UUID;

/**
 * The entity a task was meant to run on is offline, removed, or was retired before the task ran, or the task
 * could not be scheduled because the owning plugin is disabling.
 */
public final class EntityUnavailableException extends RuntimeException {

    private final UUID id;

    public EntityUnavailableException(UUID id) {
        super("Entity " + id + " is not available");
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
