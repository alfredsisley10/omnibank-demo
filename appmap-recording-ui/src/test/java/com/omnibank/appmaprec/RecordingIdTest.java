package com.omnibank.appmaprec;

import com.omnibank.appmaprec.api.RecordingId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingIdTest {

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> RecordingId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordingId.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_characters() {
        assertThatThrownBy(() -> RecordingId.of("rec/with/slashes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordingId.of("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_overlong() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> RecordingId.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trims_surrounding_whitespace() {
        RecordingId id = RecordingId.of("  rec-1234  ");
        assertThat(id.value()).isEqualTo("rec-1234");
    }

    @Test
    void newId_uses_supplied_date_components() {
        RecordingId id = RecordingId.newId("20260424", "1530");
        assertThat(id.value()).startsWith("rec-20260424-1530-");
        assertThat(id.value()).hasSize("rec-yyyymmdd-hhmm-".length() + 8);
    }

    @Test
    void safeFileName_is_lowercase_and_carries_extension() {
        RecordingId id = RecordingId.of("REC-ABCDEF");
        assertThat(id.safeFileName()).isEqualTo("rec-abcdef.appmap.json");
    }
}
