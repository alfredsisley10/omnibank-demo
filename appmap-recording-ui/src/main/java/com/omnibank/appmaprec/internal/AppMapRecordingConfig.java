package com.omnibank.appmaprec.internal;

import com.omnibank.appmaprec.api.RecordingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/**
 * Wires the AppMap interactive recording service into the Spring context.
 *
 * <p>All beans here are {@code public} so {@code app-bootstrap}'s
 * component scan picks them up; the interactive UI is a thin layer on top
 * of {@link RecordingService} so swapping implementations (eg. for an
 * out-of-process agent control plane) only requires replacing this
 * configuration.</p>
 *
 * <p>Properties (all optional, sensible defaults):
 * <ul>
 *   <li>{@code omnibank.appmap.archive-dir} — where saved appmap JSON
 *       lands. Default: {@code tmp/appmap/interactive}.</li>
 *   <li>{@code omnibank.appmap.synthetic-recording} — when true and the
 *       agent isn't attached, record action narratives only and write a
 *       placeholder JSON. Useful for driving the UI in tests.</li>
 * </ul>
 */
@Configuration
public class AppMapRecordingConfig {

    @Bean
    public AppMapAgentBridge appMapAgentBridge(
            @Value("${omnibank.appmap.synthetic-recording:false}") boolean syntheticMode) {
        return new AppMapAgentBridge(syntheticMode);
    }

    @Bean
    public RecordingArchive recordingArchive(
            @Value("${omnibank.appmap.archive-dir:tmp/appmap/interactive}") String dir) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        return new RecordingArchive(root);
    }

    @Bean
    public RecordingService recordingService(AppMapAgentBridge bridge,
                                             RecordingArchive archive,
                                             Clock clock) {
        return new InMemoryRecordingService(bridge, archive, clock);
    }
}
