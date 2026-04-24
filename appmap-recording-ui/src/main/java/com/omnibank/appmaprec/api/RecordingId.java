package com.omnibank.appmaprec.api;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stable identifier for an interactive AppMap recording session. The id is
 * URL-safe, short, and embeds the wall-clock minute the recording was opened
 * so that it sorts naturally in directory listings without requiring a
 * separate index.
 *
 * <p>The format is {@code rec-yyyymmdd-hhmm-xxxxxxxx} where the trailing
 * 8-char block is the start of a UUID. Strings produced by this class round-
 * trip through {@link #of(String)} but the parser is lenient enough to
 * accept any string of length 1..64 made up of {@code [a-zA-Z0-9_-]}, so a
 * harness or test fixture can supply its own deterministic ids.
 */
public record RecordingId(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public RecordingId {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("RecordingId cannot be blank");
        }
        if (!VALID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "RecordingId must match [A-Za-z0-9_-]{1,64}: '" + value + "'");
        }
        value = trimmed;
    }

    public static RecordingId of(String raw) {
        return new RecordingId(raw);
    }

    public static RecordingId newId(String yyyymmdd, String hhmm) {
        String tail = UUID.randomUUID().toString().substring(0, 8);
        return new RecordingId("rec-" + yyyymmdd + "-" + hhmm + "-" + tail);
    }

    public String safeFileName() {
        return value.toLowerCase(Locale.ROOT) + ".appmap.json";
    }

    @Override
    public String toString() {
        return value;
    }
}
