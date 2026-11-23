package so.alaz.taffy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class AtomicWriterTest {

    private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-23T12:00:00.250Z"), ZoneOffset.UTC);

    @TempDir
    private Path folder;

    @Test
    void createWritesANewFileInAFolderItCreatesAndLeavesNoTemporaryFile() throws Exception {
        Path file = folder.resolve("vouchers").resolve("gold.yml");

        AtomicWriter.create(file, "name: Gold é\n");

        assertThat(Files.readAllBytes(file)).isEqualTo("name: Gold é\n".getBytes(StandardCharsets.UTF_8));
        assertThat(names(file.getParent())).containsExactly("gold.yml");
    }

    @Test
    void createRefusesAFileThatExistsAndLeavesItAsItIs() throws Exception {
        Path file = Files.writeString(folder.resolve("gold.yml"), "name: Mine\n");

        assertThatThrownBy(() -> AtomicWriter.create(file, "name: Theirs\n"))
            .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(file)).isEqualTo("name: Mine\n");
        assertThat(names(folder)).containsExactly("gold.yml");
    }

    @Test
    void createRefusesASymbolicLinkWhateverItLeadsTo() throws Exception {
        Path link = folder.resolve("gold.yml");
        link(link, folder.resolve("nowhere.yml"));

        assertThatThrownBy(() -> AtomicWriter.create(link, "name: Theirs\n"))
            .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(folder.resolve("nowhere.yml")).doesNotExist();
    }

    @Test
    void createRefusesAPathWhoseParentIsAFile() throws Exception {
        Path notAFolder = Files.writeString(folder.resolve("vouchers"), "a file");

        assertThatThrownBy(() -> AtomicWriter.create(notAFolder.resolve("gold.yml"), "name: Gold\n"))
            .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(notAFolder)).isEqualTo("a file");
        assertThat(names(folder)).containsExactly("vouchers");
    }

    @Test
    void writeAndReplaceRefuseASymbolicLinkThatLeadsToNoFile() throws Exception {
        Path link = folder.resolve("crate.yml");
        link(link, folder.resolve("nowhere.yml"));

        assertThatThrownBy(() -> AtomicWriter.write(link, "new: 2\n")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> AtomicWriter.replace(link, "new: 2\n", AtomicWriter.ABSENT,
            folder.resolve("backups"), 5)).isInstanceOf(NoSuchFileException.class);

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(names(folder)).containsExactly("crate.yml");
    }

    @Test
    void writeReplacesAFileAndRemovesALeftoverTemporaryFile() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");
        Files.writeString(folder.resolve("crate.yml.tmp"), "cut off");

        AtomicWriter.write(file, "new: 2\n");

        assertThat(Files.readString(file)).isEqualTo("new: 2\n");
        assertThat(names(folder)).containsExactly("crate.yml");
    }

    @Test
    void writeCreatesAMissingFileAndItsFolder() throws Exception {
        Path file = folder.resolve("crates").resolve("crate.yml");

        AtomicWriter.write(file, "new: 2\n");

        assertThat(Files.readString(file)).isEqualTo("new: 2\n");
    }

    @Test
    void writeReplacesTheFileASymbolicLinkLeadsToAndKeepsTheLink() throws Exception {
        Path real = Files.writeString(Files.createDirectory(folder.resolve("real")).resolve("crate.yml"), "old: 1\n");
        Path link = folder.resolve("crate.yml");
        link(link, real);

        AtomicWriter.write(link, "new: 2\n");

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(real)).isEqualTo("new: 2\n");
        assertThat(names(real.getParent())).containsExactly("crate.yml");
    }

    @Test
    void writeAndReplaceKeepThePosixPermissionsOfTheFile() throws Exception {
        try (FileSystem zip = FileSystems.newFileSystem(
            folder.resolve("posix.zip"), Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            Path data = Files.createDirectories(zip.getPath("/data"));
            Path file = Files.writeString(data.resolve("crate.yml"), "old: 1\n");
            Set<PosixFilePermission> chosen = PosixFilePermissions.fromString("rw-rw-r--");
            Files.setPosixFilePermissions(file, chosen);

            AtomicWriter.write(file, "new: 2\n");
            AtomicWriter.Result result = AtomicWriter.replace(file, "new: 3\n",
                AtomicWriter.revision("new: 2\n".getBytes(StandardCharsets.UTF_8)), data.resolve("backups"), 3);

            assertThat(Files.readString(file)).isEqualTo("new: 3\n");
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(chosen);
            assertThat(Files.getPosixFilePermissions(((AtomicWriter.Written) result).backup())).isEqualTo(chosen);
        }
    }

    @Test
    void theRevisionIsTheSha256OfTheBytesInLowerCaseHex() {
        assertThat(AtomicWriter.revision("abc".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void replaceWritesAFileOfTheBaseRevisionAndKeepsACopyOfWhatItReplaced() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\r\n");
        Path backups = folder.resolve("backups");

        AtomicWriter.Result result = AtomicWriter.replace(file, "new: 2\n", revisionOf(file), backups, 5, NOON,
            (temporary, target) -> Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING));

        assertThat(result).isInstanceOf(AtomicWriter.Written.class);
        AtomicWriter.Written written = (AtomicWriter.Written) result;
        assertThat(Files.readString(file)).isEqualTo("new: 2\n");
        assertThat(written.revision()).isEqualTo(revisionOf(file));
        assertThat(written.backup()).hasParent(backups);
        assertThat(written.backup().getFileName().toString())
            .matches("crate\\.yml\\.[0-9a-f]{12}\\.20261123-120000-250\\.bak");
        assertThat(Files.readString(written.backup())).isEqualTo("old: 1\r\n");
        assertThat(names(folder)).containsExactly("backups", "crate.yml");
        assertThat(names(backups)).hasSize(1);
    }

    @Test
    void replaceOfAFileThatChangedWritesNothingAndGivesItsRevision() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");
        String base = revisionOf(file);
        Files.writeString(file, "edited: by hand\n");
        Path backups = folder.resolve("backups");

        AtomicWriter.Result result = AtomicWriter.replace(file, "new: 2\n", base, backups, 5);

        assertThat(result).isEqualTo(new AtomicWriter.Conflict(revisionOf(file)));
        assertThat(Files.readString(file)).isEqualTo("edited: by hand\n");
        assertThat(backups).doesNotExist();
        assertThat(names(folder)).containsExactly("crate.yml");
    }

    @Test
    void replaceTreatsAMissingFileAsTheAbsentRevision() throws Exception {
        Path file = folder.resolve("crate.yml");
        Path backups = folder.resolve("backups");

        assertThat(AtomicWriter.replace(file, "new: 2\n", AtomicWriter.revision(new byte[0]), backups, 5))
            .isEqualTo(new AtomicWriter.Conflict(AtomicWriter.ABSENT));
        assertThat(file).doesNotExist();

        AtomicWriter.Result result = AtomicWriter.replace(file, "new: 2\n", AtomicWriter.ABSENT, backups, 5);

        assertThat(result).isEqualTo(new AtomicWriter.Written(revisionOf(file), null));
        assertThat(Files.readString(file)).isEqualTo("new: 2\n");
        assertThat(backups).doesNotExist();
        assertThat(AtomicWriter.replace(file, "new: 3\n", AtomicWriter.ABSENT, backups, 5))
            .isEqualTo(new AtomicWriter.Conflict(revisionOf(file)));
    }

    @Test
    void replaceKeepsOnlyTheNewestCopiesOfThatFile() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "step: 0\n");
        Path other = Files.writeString(Files.createDirectory(folder.resolve("other")).resolve("crate.yml"), "o: 0\n");
        Path backups = folder.resolve("backups");
        AtomicWriter.replace(other, "o: 1\n", revisionOf(other), backups, 2, NOON, AtomicWriterTest::move);

        for (int step = 1; step <= 4; step++) {
            AtomicWriter.replace(file, "step: " + step + "\n", revisionOf(file), backups, 2, NOON,
                AtomicWriterTest::move);
        }

        String prefix = "crate.yml." + pathId(file) + ".20261123-120000-250";
        assertThat(names(backups)).contains(prefix + "-2.bak", prefix + "-3.bak").hasSize(3);
        assertThat(Files.readString(backups.resolve(prefix + "-2.bak"))).isEqualTo("step: 2\n");
        assertThat(Files.readString(backups.resolve(prefix + "-3.bak"))).isEqualTo("step: 3\n");
        assertThat(Files.readString(backups.resolve("crate.yml." + pathId(other) + ".20261123-120000-250.bak")))
            .isEqualTo("o: 0\n");
    }

    @Test
    void replaceTakesNoSecondCopyOfContentTheNewestCopyHolds() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "a: 1\n");
        Path backups = folder.resolve("backups");
        AtomicWriter.Written first =
            (AtomicWriter.Written) AtomicWriter.replace(file, "b: 2\n", revisionOf(file), backups, 5);
        Files.writeString(file, "a: 1\n");

        AtomicWriter.Written second =
            (AtomicWriter.Written) AtomicWriter.replace(file, "c: 3\n", revisionOf(file), backups, 5);

        assertThat(second.backup()).isEqualTo(first.backup());
        assertThat(names(backups)).hasSize(1);
    }

    @Test
    void replaceCountsTheCopiesOfOneFileUnderEverySpellingOfItsPath() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "step: 0\n");
        Path backups = folder.resolve("backups");
        List<Path> spellings = List.of(file,
            Files.createDirectory(folder.resolve("sub")).resolve("..").resolve("crate.yml"),
            folder.resolve(".").resolve("crate.yml"));

        for (int step = 1; step <= 6; step++) {
            AtomicWriter.replace(spellings.get(step % spellings.size()), "step: " + step + "\n", revisionOf(file),
                backups, 1);
        }

        assertThat(names(backups)).singleElement().asString().startsWith("crate.yml." + pathId(file) + ".");
        assertThat(Files.readString(backups.resolve(names(backups).getFirst()))).isEqualTo("step: 5\n");
    }

    @Test
    void replaceKeepsTheCopyItTookWhenAnotherCopyCarriesALaterTime() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");
        Path backups = Files.createDirectory(folder.resolve("backups"));
        String later = "crate.yml." + pathId(file) + ".20270101-000000-000";
        Files.writeString(backups.resolve(later + ".bak"), "older: 0\n");

        AtomicWriter.Written written = (AtomicWriter.Written) AtomicWriter.replace(file, "new: 2\n",
            revisionOf(file), backups, 1, NOON, AtomicWriterTest::move);

        assertThat(written.backup()).isEqualTo(backups.resolve(later + "-1.bak"));
        assertThat(Files.readString(written.backup())).isEqualTo("old: 1\n");
        assertThat(names(backups)).containsExactly(later + "-1.bak");
    }

    @Test
    void aWriteThatFailsLeavesTheFileAndRemovesTheTemporaryFileAndTheCopyItTook() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");
        Path backups = folder.resolve("backups");
        IOException full = new IOException("No space left on device");

        assertThatThrownBy(() -> AtomicWriter.replace(file, "new: 2\n", revisionOf(file), backups, 5, NOON,
            (temporary, target) -> {
                throw full;
            })).isSameAs(full);

        assertThat(Files.readString(file)).isEqualTo("old: 1\n");
        assertThat(names(folder)).containsExactly("backups", "crate.yml");
        assertThat(names(backups)).isEmpty();
    }

    @Test
    void aFileWindowsMarksReadOnlyIsNotReplacedAndNothingIsLeftBeside() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        assumeTrue(dos != null && System.getProperty("os.name").startsWith("Windows"),
            "only Windows refuses to move a file over a read-only one");
        dos.setReadOnly(true);
        try {
            assertThatThrownBy(() -> AtomicWriter.write(file, "new: 2\n")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> AtomicWriter.replace(file, "new: 2\n", revisionOf(file),
                folder.resolve("backups"), 5)).isInstanceOf(IOException.class);

            assertThat(Files.readString(file)).isEqualTo("old: 1\n");
            assertThat(dos.readAttributes().isReadOnly()).isTrue();
            assertThat(names(folder)).containsExactly("backups", "crate.yml");
            assertThat(names(folder.resolve("backups"))).isEmpty();
        } finally {
            dos.setReadOnly(false);
        }
    }

    @Test
    void keepOutsideOneToAThousandIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path file = Files.writeString(folder.resolve("crate.yml"), "old: 1\n");

        assertThatThrownBy(() -> AtomicWriter.replace(file, "new: 2\n", revisionOf(file), folder.resolve("b"), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("keep is 0; expected 1 to 1000");
        assertThatThrownBy(() -> AtomicWriter.replace(file, "new: 2\n", revisionOf(file), folder.resolve("b"), 1001))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readString(file)).isEqualTo("old: 1\n");
    }

    private static void move(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String revisionOf(Path file) throws IOException {
        return AtomicWriter.revision(Files.readAllBytes(file));
    }

    private static String pathId(Path file) {
        return AtomicWriter.revision(file.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8))
            .substring(0, 12);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "this file system cannot create symbolic links");
        }
    }
}
