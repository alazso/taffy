package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class UpdateSourceTest {

    @Test
    void githubReadsTheReleasesOfTheRepositoryOverHttps() {
        UpdateSource source = UpdateSource.github("alaz-so", "Shop.Plugin_2");

        assertThat(source.endpoint())
            .isEqualTo(URI.create("https://api.github.com/repos/alaz-so/Shop.Plugin_2/releases?per_page=30"));
        assertThat(source.page()).isEqualTo("https://github.com/alaz-so/Shop.Plugin_2/releases");
        assertThat(source).hasToString("GitHub releases of alaz-so/Shop.Plugin_2");
    }

    @Test
    void modrinthReadsTheVersionsOfTheProjectOverHttps() {
        UpdateSource source = UpdateSource.modrinth("AANobbMI");

        assertThat(source.endpoint())
            .isEqualTo(URI.create("https://api.modrinth.com/v2/project/AANobbMI/version?include_changelog=false"));
        assertThat(source.page()).isEqualTo("https://modrinth.com/project/AANobbMI/versions");
        assertThat(source).hasToString("Modrinth versions of AANobbMI");
    }

    @Test
    void githubRefusesANameThatCouldChangeTheAddress() {
        for (String owner : List.of("", "-alaz", "alaz-", "a/b", "a b", "a?b", "a".repeat(40), "aläz")) {
            assertThatThrownBy(() -> UpdateSource.github(owner, "shop")).as(owner)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("owner is not a GitHub user or organization name");
        }
        for (String repository : List.of("", ".", "..", "a/b", "a b", "a#b", "a%2Fb", "a".repeat(101))) {
            assertThatThrownBy(() -> UpdateSource.github("alaz", repository)).as(repository)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("repository is not a GitHub repository name");
        }
        assertThatThrownBy(() -> UpdateSource.github(null, "shop"))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> UpdateSource.github("alaz", null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("repository");
    }

    @Test
    void modrinthRefusesAProjectThatCouldChangeTheAddress() {
        for (String project : List.of("", "ab", "..", "a/b", "a b c", "a?b=c", "a".repeat(65))) {
            assertThatThrownBy(() -> UpdateSource.modrinth(project)).as(project)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("project is not a Modrinth project id or slug");
        }
        assertThatThrownBy(() -> UpdateSource.modrinth(null))
            .isExactlyInstanceOf(NullPointerException.class).hasMessage("project");
    }
}
