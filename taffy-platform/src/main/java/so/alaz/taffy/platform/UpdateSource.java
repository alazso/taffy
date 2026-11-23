package so.alaz.taffy.platform;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where {@link UpdateCheck} reads the published releases of a plugin: the releases of a GitHub repository or the
 * versions of a Modrinth project.
 *
 * <p>Both are read over HTTPS from a fixed host, without an account or token:
 * <ul>
 *   <li>{@link #github}: {@code https://api.github.com/repos/<owner>/<repository>/releases?per_page=30}, the 30
 *       newest releases. The version of a release is its {@code tag_name}, a release with {@code prerelease} set
 *       is a pre-release, and a draft is left out;</li>
 *   <li>{@link #modrinth}: {@code https://api.modrinth.com/v2/project/<project>/version?include_changelog=false},
 *       newest first. The version is the {@code version_number}, a version whose {@code version_type} is not
 *       {@code release} is a pre-release, and a version whose {@code status} is not {@code listed} is left
 *       out.</li>
 * </ul>
 * Whether a published version is a pre-release is what the site says about it, not the form of its version text.
 *
 * <p>Instances are immutable.
 */
public final class UpdateSource {

    /** One published version: its text as the site gives it and whether the site marks it as a pre-release. */
    record Release(String version, boolean preRelease) {
    }

    private enum Site { GITHUB, MODRINTH }

    private static final String GITHUB_API = "https://api.github.com";
    private static final String MODRINTH_API = "https://api.modrinth.com";

    private static final Pattern GITHUB_OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern MODRINTH_PROJECT = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    private final Site site;
    private final String base;
    private final String owner;
    private final String name;

    private UpdateSource(Site site, String base, String owner, String name) {
        this.site = site;
        this.base = base;
        this.owner = owner;
        this.name = name;
    }

    /**
     * The releases of the GitHub repository {@code owner/repository}.
     *
     * @param owner the user or organization: 1 to 39 letters, digits and hyphens, with no hyphen first or last
     * @param repository the repository name: 1 to 100 letters, digits, {@code .}, {@code _} and {@code -}, and
     *     not {@code .} or {@code ..}
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if an argument has another form
     */
    public static UpdateSource github(String owner, String repository) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(repository, "repository");
        if (!GITHUB_OWNER.matcher(owner).matches()) {
            throw new IllegalArgumentException("owner is not a GitHub user or organization name");
        }
        if (!NAME.matcher(repository).matches() || repository.equals(".") || repository.equals("..")) {
            throw new IllegalArgumentException("repository is not a GitHub repository name");
        }
        return new UpdateSource(Site.GITHUB, GITHUB_API, owner, repository);
    }

    /**
     * The versions of a Modrinth project.
     *
     * @param project the project's id or slug: 3 to 64 letters, digits, {@code .}, {@code _} and {@code -}
     * @throws NullPointerException if the project is null
     * @throws IllegalArgumentException if the project has another form
     */
    public static UpdateSource modrinth(String project) {
        Objects.requireNonNull(project, "project");
        if (!MODRINTH_PROJECT.matcher(project).matches()) {
            throw new IllegalArgumentException("project is not a Modrinth project id or slug");
        }
        return new UpdateSource(Site.MODRINTH, MODRINTH_API, "", project);
    }

    /** This source read from another origin, such as {@code http://127.0.0.1:8080}, for a test. */
    UpdateSource withBase(String origin) {
        return new UpdateSource(site, origin, owner, name);
    }

    /** The address of the one request. */
    URI endpoint() {
        return URI.create(switch (site) {
            case GITHUB -> base + "/repos/" + owner + "/" + name + "/releases?per_page=30";
            case MODRINTH -> base + "/v2/project/" + name + "/version?include_changelog=false";
        });
    }

    /** The request headers beside {@code User-Agent}. */
    Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        switch (site) {
            case GITHUB -> {
                headers.put("Accept", "application/vnd.github+json");
                headers.put("X-GitHub-Api-Version", "2022-11-28");
            }
            case MODRINTH -> headers.put("Accept", "application/json");
        }
        return headers;
    }

    /** The page an owner opens to get a release. It is built from the arguments, never from an answer. */
    String page() {
        return switch (site) {
            case GITHUB -> "https://github.com/" + owner + "/" + name + "/releases";
            case MODRINTH -> "https://modrinth.com/project/" + name + "/versions";
        };
    }

    /**
     * Reads the next object of the answer's array.
     *
     * @return the release, or null for an entry that is left out or that names no version
     * @throws IOException if the reader fails
     * @throws IllegalStateException if the next value is not an object
     */
    @Nullable Release release(JsonReader reader) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            JsonToken token = reader.peek();
            if (token == JsonToken.STRING) {
                fields.put(field, reader.nextString());
            } else if (token == JsonToken.BOOLEAN) {
                fields.put(field, Boolean.toString(reader.nextBoolean()));
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return switch (site) {
            case GITHUB -> {
                String tag = fields.get("tag_name");
                yield tag == null || "true".equals(fields.get("draft"))
                    ? null : new Release(tag, "true".equals(fields.get("prerelease")));
            }
            case MODRINTH -> {
                String number = fields.get("version_number");
                yield number == null || !"listed".equals(fields.getOrDefault("status", "listed"))
                    ? null : new Release(number, !"release".equals(fields.get("version_type")));
            }
        };
    }

    /** {@code GitHub releases of <owner>/<repository>} or {@code Modrinth versions of <project>}. */
    @Override
    public String toString() {
        return switch (site) {
            case GITHUB -> "GitHub releases of " + owner + "/" + name;
            case MODRINTH -> "Modrinth versions of " + name;
        };
    }
}
