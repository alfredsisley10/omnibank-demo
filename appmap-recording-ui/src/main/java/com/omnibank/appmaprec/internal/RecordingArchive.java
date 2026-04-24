package com.omnibank.appmaprec.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed archive of saved AppMap recordings. Lives under
 * {@code tmp/appmap/interactive/} so it does not collide with the
 * Gradle plugin's own per-test directory layout.
 */
public class RecordingArchive {

    private static final Logger log = LoggerFactory.getLogger(RecordingArchive.class);

    private final Path root;

    public RecordingArchive(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path resolveFor(String filename) {
        Path direct = root.resolve(filename);
        // Defend against directory traversal attempts.
        if (!direct.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Refusing to resolve outside archive root: " + filename);
        }
        return direct;
    }

    public boolean exists(String filename) {
        return Files.isRegularFile(resolveFor(filename));
    }

    public Optional<byte[]> read(String filename) {
        Path p = resolveFor(filename);
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(p));
        } catch (IOException e) {
            log.warn("Failed to read archived appmap '{}': {}", filename, e.toString());
            return Optional.empty();
        }
    }

    public boolean delete(String filename) {
        Path p = resolveFor(filename);
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete archived appmap '{}': {}", filename, e.toString());
            return false;
        }
    }

    public Path moveInto(Path source, String filename) throws IOException {
        Files.createDirectories(root);
        Path target = resolveFor(filename);
        return Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<ArchivedFile> list() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Cannot create archive root {}: {}", root, e.toString());
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        try {
                            return new ArchivedFile(p.getFileName().toString(),
                                    Files.size(p),
                                    Files.getLastModifiedTime(p).toMillis());
                        } catch (IOException e) {
                            return new ArchivedFile(p.getFileName().toString(), 0L, 0L);
                        }
                    })
                    .sorted(Comparator.comparingLong(ArchivedFile::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to enumerate archive {}: {}", root, e.toString());
            return List.of();
        }
    }

    public record ArchivedFile(String name, long sizeBytes, long lastModified) {}
}
