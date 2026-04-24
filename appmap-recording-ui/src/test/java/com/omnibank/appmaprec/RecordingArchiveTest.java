package com.omnibank.appmaprec;

import com.omnibank.appmaprec.internal.RecordingArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingArchiveTest {

    private Path tmpRoot;
    private RecordingArchive archive;

    @BeforeEach
    void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("archive-test-");
        archive = new RecordingArchive(tmpRoot);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            try (Stream<Path> walk = Files.walk(tmpRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void blocks_directory_traversal() {
        assertThatThrownBy(() -> archive.resolveFor("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void list_returns_sorted_by_modification_time_descending() throws IOException, InterruptedException {
        Path a = tmpRoot.resolve("a.appmap.json");
        Path b = tmpRoot.resolve("b.appmap.json");
        Files.writeString(a, "{}", StandardCharsets.UTF_8);
        Thread.sleep(20);
        Files.writeString(b, "{}", StandardCharsets.UTF_8);

        var listing = archive.list();
        assertThat(listing).extracting("name")
                .containsExactly("b.appmap.json", "a.appmap.json");
    }

    @Test
    void delete_removes_file() throws IOException {
        Path target = tmpRoot.resolve("doomed.appmap.json");
        Files.writeString(target, "x", StandardCharsets.UTF_8);
        assertThat(archive.delete("doomed.appmap.json")).isTrue();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void delete_nonexistent_returns_false() {
        assertThat(archive.delete("nope.appmap.json")).isFalse();
    }

    @Test
    void read_missing_returns_empty() {
        assertThat(archive.read("ghost.appmap.json")).isEmpty();
    }
}
