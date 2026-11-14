package so.alaz.taffy.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeFoldersTest {

    private static final Path FIRST = Path.of("first.yml");
    private static final Path SECOND = Path.of("second.txt");

    @Test
    void theFolderIsADirectoryThatDoesNotExist() throws IOException {
        Path folder = FakeFolders.unlistable(null);

        assertThat(Files.isDirectory(folder)).isTrue();
        assertThat(Files.readAttributes(folder, BasicFileAttributes.class).isDirectory()).isTrue();
        assertThat(Files.exists(folder)).isFalse();
        assertThat(Files.notExists(folder)).isFalse();
    }

    @Test
    void attributesReadByNameGiveMockitosDefaultAnswer() throws IOException {
        Path folder = FakeFolders.unlistable(null);

        assertThat(Files.readAttributes(folder, "*")).isEmpty();
    }

    @Test
    void aNameResolvesToTheFolderItself() {
        Path folder = FakeFolders.unlistable(null);

        assertThat(folder.resolve("lang")).isSameAs(folder);
        assertThat(folder.resolve("lang").resolve("en.yml")).isSameAs(folder);
        assertThat(Files.isDirectory(folder.resolve("lang"))).isTrue();
    }

    @Test
    void aFolderWithNoStreamFailsWithAccessDeniedWhenItIsListed() {
        Path folder = FakeFolders.unlistable(null);

        assertThatThrownBy(() -> Files.newDirectoryStream(folder))
            .isExactlyInstanceOf(IOException.class)
            .hasMessage("access denied");
        assertThatThrownBy(() -> Files.newDirectoryStream(folder, "*.yml"))
            .isExactlyInstanceOf(IOException.class)
            .hasMessage("access denied");
        assertThatThrownBy(() -> Files.list(folder))
            .isExactlyInstanceOf(IOException.class)
            .hasMessage("access denied");
    }

    @Test
    void aListedFolderGivesTheStreamAsItIsWithNoFilterApplied() throws IOException {
        DirectoryStream<Path> stream = streamOf(FIRST, SECOND);
        Path folder = FakeFolders.unlistable(stream);

        assertThat((Object) Files.newDirectoryStream(folder)).isSameAs(stream);
        assertThat((Object) Files.newDirectoryStream(folder, "*.yml")).isSameAs(stream);
        assertThat((Object) Files.newDirectoryStream(folder, entry -> false)).isSameAs(stream);
        try (Stream<Path> listed = Files.list(folder)) {
            assertThat(listed).containsExactly(FIRST, SECOND);
        }
    }

    @Test
    void whatIsNotStubbedOnThePathAnswersNull() {
        Path folder = FakeFolders.unlistable(null);

        assertThat(folder.getFileName()).isNull();
        assertThat(folder.toAbsolutePath()).isNull();
        assertThat(folder.normalize()).isNull();
        assertThat(folder.resolve(FIRST)).isNull();
        assertThat(folder.getParent()).isNull();
    }

    private static DirectoryStream<Path> streamOf(Path... entries) {
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return List.of(entries).iterator();
            }

            @Override
            public void close() {
            }
        };
    }
}
