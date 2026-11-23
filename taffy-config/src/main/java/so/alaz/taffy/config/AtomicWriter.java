package so.alaz.taffy.config;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a text file so that a reader, a crash or a power loss never meets half of it. The text is saved as UTF-8
 * exactly as given.
 *
 * <p>Every write goes to a temporary file beside the target, named as the target followed by {@code .tmp}, which
 * is flushed to the device and then put in place in one step. A reader sees the previous file or the new one.
 * A leftover temporary file from a write that was cut off is removed by the next write of that name. A missing
 * parent folder is created.
 *
 * <ul>
 *   <li>{@link #create} puts a new file in place and never replaces one that exists.</li>
 *   <li>{@link #write} replaces the file whatever it holds.</li>
 *   <li>{@link #replace} replaces the file only while it still holds the content an edit started from, and
 *       keeps copies of what it replaced.</li>
 * </ul>
 *
 * <p>{@link #write} and {@link #replace} follow a symbolic link to the file it names, so the link stays and the
 * file it leads to is replaced, and the temporary file is written beside that file. The new file gets the POSIX
 * permissions of the file it replaces where the file system has them. A file written where none existed gets
 * the permissions the file system gives any new file. A hard link of the target is not updated: the other names
 * keep the previous content.
 *
 * <h2>What a failure leaves behind</h2>
 *
 * <p>Every method throws the {@link IOException} of the step that failed. When the device is full, or the
 * temporary file cannot be written for another reason, the target holds what it held before the call, the
 * temporary file is removed, and the copy {@link #replace} took in that call is removed. A folder that refuses
 * new files fails the same way, before anything is written. A file marked read-only is replaced where the file
 * system allows a file of its folder to be renamed, as POSIX file systems do, and the new file has the same
 * permissions; on Windows the move is refused, and the file and its attribute stay. When the temporary file
 * cannot be removed after a failure, it stays until the next write of that name.
 *
 * <h2>Threads</h2>
 *
 * <p>Every call blocks the calling thread for its file I/O and uses no scheduler, so a plugin calls it from an
 * async task, on Paper and Folia alike. Calls for one file made through the same copy of this class run one
 * after another. A change made by another process, by another plugin or by the owner's editor is not held off:
 * only the revision check of {@link #replace} detects it.
 */
public final class AtomicWriter {

    /** The revision {@link #replace} takes for a file that does not exist. */
    public static final String ABSENT = "absent";

    private static final int MOST_KEPT = 1000;
    private static final int PATH_ID_LENGTH = 12;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern BACKUP_SUFFIX = Pattern.compile("(\\d{8}-\\d{6}-\\d{3})(?:-(\\d{1,9}))?\\.bak");
    private static final Lock[] LOCKS = new Lock[64];

    static {
        Arrays.setAll(LOCKS, index -> new ReentrantLock());
    }

    private AtomicWriter() {
    }

    /** What {@link #replace} did. */
    public sealed interface Result permits Written, Conflict {
    }

    /**
     * The file was replaced.
     *
     * @param revision the revision of the content now in the file, which the next edit starts from
     * @param backup the copy of the content that was replaced, or {@code null} when the file did not exist
     */
    public record Written(String revision, @Nullable Path backup) implements Result {
    }

    /**
     * The file no longer held the content the edit started from, and nothing was written.
     *
     * @param currentRevision the revision of the content the file holds now, or {@link #ABSENT} when it is gone
     */
    public record Conflict(String currentRevision) implements Result {
    }

    /**
     * Writes a new file and never replaces one. The text is written to the temporary file and flushed, and that
     * file is then given the name {@code file} with a hard link, which the file system refuses when the name is
     * taken, so two writers of one new name cannot both succeed. On a file system without hard links the
     * temporary file is moved to the name without replacing, which Windows does in one step and other systems
     * do as a check followed by a move.
     *
     * @throws FileAlreadyExistsException when {@code file} exists, a symbolic link included, whatever it leads
     *     to. That file is not changed.
     * @throws IOException when the file cannot be written. No file named {@code file} was created.
     */
    public static void create(Path file, String text) throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Path target = file.toAbsolutePath();
        Lock lock = lockFor(target);
        lock.lock();
        try {
            if (Files.exists(target) || Files.isSymbolicLink(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            Files.createDirectories(target.getParent());
            Path temporary = temporary(target);
            Files.deleteIfExists(temporary);
            try {
                writeForced(temporary, content);
                try {
                    Files.createLink(target, temporary);
                } catch (FileAlreadyExistsException taken) {
                    throw taken;
                } catch (IOException | UnsupportedOperationException noHardLinks) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces {@code file} with {@code text}, or writes it when it does not exist.
     *
     * @throws IOException when the file cannot be written, as the class description states
     */
    public static void write(Path file, String text) throws IOException {
        Path target = target(file);
        Files.createDirectories(target.getParent());
        put(target, text.getBytes(StandardCharsets.UTF_8), target);
    }

    /**
     * The revision of {@code content}: the SHA-256 of the bytes as 64 lower-case hex digits. It changes whenever
     * the bytes change, whoever changed them. A plugin takes it from the bytes it read the file as, when an edit
     * starts, and passes it to {@link #replace}.
     */
    public static String revision(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /**
     * Replaces {@code file} with {@code text} when the file still has the revision the edit started from. The
     * check, the copy and the write happen with no other call of this class for that file in between.
     *
     * <p>Before the write, the content about to be replaced is copied to {@code backups}, which is created when
     * missing, as {@code <file name>.<path id>.<yyyyMMdd-HHmmss-SSS>.bak}: the time is UTC, {@code -<n>} follows
     * it for a further copy of the same millisecond, and the path id is the first 12 hex digits of the SHA-256
     * of the absolute path of the file (for a symbolic link, of the file it leads to), so files of one name in
     * different folders can share one folder of copies. The copy holds the
     * file byte for byte and gets its POSIX permissions where the file system has them, and no copy is taken
     * when the newest copy of that file already holds the same bytes.
     * After the write, the copies of that file beyond the newest {@code keep} are deleted; a delete that fails
     * leaves that copy and does not fail the call. A file that did not exist gives no copy.
     *
     * @param baseRevision the {@link #revision} of the bytes the edit started from, or {@link #ABSENT} to write
     *     the file only when it does not exist
     * @param backups the folder that receives the copies
     * @param keep the number of copies kept for this file, 1 to 1000
     * @return a {@link Written} with the new revision, or a {@link Conflict} with the current one when the file
     *     has another revision than {@code baseRevision}; nothing is written or copied then
     * @throws IllegalArgumentException when {@code keep} is below 1 or above 1000
     * @throws IOException when the file cannot be read, copied or written, as the class description states
     */
    public static Result replace(Path file, String text, String baseRevision, Path backups, int keep)
        throws IOException {
        return replace(file, text, baseRevision, backups, keep, Clock.systemUTC(), AtomicWriter::moveOver);
    }

    static Result replace(Path file, String text, String baseRevision, Path backups, int keep, Clock clock,
                          Mover mover) throws IOException {
        Objects.requireNonNull(baseRevision, "baseRevision");
        Objects.requireNonNull(backups, "backups");
        if (keep < 1 || keep > MOST_KEPT) {
            throw new IllegalArgumentException("keep is " + keep + "; expected 1 to " + MOST_KEPT);
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Path target = target(file);
        Lock lock = lockFor(target);
        lock.lock();
        try {
            byte[] current = contentOf(target);
            String currentRevision = current == null ? ABSENT : revision(current);
            if (!baseRevision.equals(currentRevision)) {
                return new Conflict(currentRevision);
            }
            Path backup = null;
            boolean copied = false;
            if (current != null) {
                List<Copy> copies = copiesOf(target, backups);
                if (!copies.isEmpty() && Arrays.equals(Files.readAllBytes(copies.getFirst().path()), current)) {
                    backup = copies.getFirst().path();
                } else {
                    backup = newCopy(target, backups, copies, clock);
                    copied = true;
                }
            }
            try {
                if (copied) {
                    Files.createDirectories(backups);
                    put(backup, current, target, AtomicWriter::moveOver);
                }
                Files.createDirectories(target.getParent());
                put(target, content, target, mover);
            } catch (IOException | RuntimeException failure) {
                if (copied) {
                    try {
                        Files.deleteIfExists(backup);
                    } catch (IOException kept) {
                        failure.addSuppressed(kept);
                    }
                }
                throw failure;
            }
            try {
                List<Copy> copies = copiesOf(target, backups);
                for (Copy old : copies.subList(Math.min(keep, copies.size()), copies.size())) {
                    Files.deleteIfExists(old.path());
                }
            } catch (IOException copyKept) {
                // The new content is in place, and a copy that cannot be deleted is tried again by the next call.
            }
            return new Written(revision(content), backup);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces {@code file} with {@code content} when it still holds exactly {@code expected}, after writing
     * {@code expected} to {@code backup}, which gets the permissions of the file.
     *
     * @return whether the file held {@code expected}; nothing is written when it did not
     */
    static boolean replaceKeeping(Path file, byte[] expected, Path backup, byte[] content) throws IOException {
        Path target = target(file);
        Lock lock = lockFor(target);
        lock.lock();
        try {
            if (!Arrays.equals(contentOf(target), expected)) {
                return false;
            }
            put(backup, expected, target, AtomicWriter::moveOver);
            put(target, content, target, AtomicWriter::moveOver);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes {@code content} to the temporary file of {@code target}, flushes it to the device and moves it over
     * {@code target}. The file gets the POSIX permissions of {@code permissionsOf} when that file exists.
     */
    static void put(Path target, byte[] content, Path permissionsOf) throws IOException {
        Lock lock = lockFor(target);
        lock.lock();
        try {
            put(target, content, permissionsOf, AtomicWriter::moveOver);
        } finally {
            lock.unlock();
        }
    }

    /** The file a write of {@code file} replaces: the file a symbolic link leads to, or {@code file} itself. */
    static Path target(Path file) throws IOException {
        return Files.isSymbolicLink(file) ? file.toRealPath() : file.toAbsolutePath();
    }

    static Path temporary(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    /** Puts the written temporary file in the place of the target. */
    @FunctionalInterface
    interface Mover {

        void move(Path temporary, Path target) throws IOException;
    }

    private static void put(Path target, byte[] content, Path permissionsOf, Mover mover) throws IOException {
        Path temporary = temporary(target);
        Files.deleteIfExists(temporary);
        try {
            writeForced(temporary, content);
            if (Files.exists(permissionsOf)
                && Files.getFileStore(permissionsOf).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(permissionsOf));
            }
            mover.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeForced(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel =
                 FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            // The bytes reach the device before the rename, so a power loss after it cannot leave an empty file.
            channel.force(true);
        }
    }

    private static void moveOver(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte @Nullable [] contentOf(Path target) throws IOException {
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    // Two files can share a lock, which only makes their writes wait for each other.
    private static Lock lockFor(Path target) {
        return LOCKS[Math.floorMod(target.normalize().hashCode(), LOCKS.length)];
    }

    private static String copyPrefix(Path target) {
        String pathId = revision(target.toString().getBytes(StandardCharsets.UTF_8)).substring(0, PATH_ID_LENGTH);
        return target.getFileName() + "." + pathId + ".";
    }

    /** A copy of a file: {@code attempt} orders the copies that share one time. */
    private record Copy(Path path, String stamp, int attempt) {
    }

    private static Path newCopy(Path target, Path backups, List<Copy> copies, Clock clock) {
        String stamp = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).format(BACKUP_TIME);
        int attempt = 0;
        for (Copy copy : copies) {
            if (copy.stamp().equals(stamp)) {
                attempt = Math.max(attempt, copy.attempt() + 1);
            }
        }
        Path copy = backups.resolve(copyPrefix(target) + stamp + (attempt == 0 ? "" : "-" + attempt) + ".bak");
        while (Files.exists(copy)) {
            copy = backups.resolve(copyPrefix(target) + stamp + "-" + ++attempt + ".bak");
        }
        return copy;
    }

    /** The copies of {@code target} in {@code backups}, newest first. */
    private static List<Copy> copiesOf(Path target, Path backups) throws IOException {
        List<Copy> found = new ArrayList<>();
        if (!Files.isDirectory(backups)) {
            return found;
        }
        String prefix = copyPrefix(target);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (Path entry : entries) {
                String name = String.valueOf(entry.getFileName());
                if (!name.startsWith(prefix)) {
                    continue;
                }
                Matcher suffix = BACKUP_SUFFIX.matcher(name.substring(prefix.length()));
                if (suffix.matches() && Files.isRegularFile(entry)) {
                    int attempt = suffix.group(2) == null ? 0 : Integer.parseInt(suffix.group(2));
                    found.add(new Copy(entry, suffix.group(1), attempt));
                }
            }
        }
        found.sort(Comparator.comparing(Copy::stamp).thenComparingInt(Copy::attempt).reversed());
        return found;
    }
}
