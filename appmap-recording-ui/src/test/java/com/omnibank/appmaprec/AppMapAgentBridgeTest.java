package com.omnibank.appmaprec;

import com.omnibank.appmaprec.internal.AppMapAgentBridge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CI environment never has the AppMap Java agent attached so these
 * tests describe the synthetic / disabled fallbacks. The agent path is
 * exercised separately by the AppMap Gradle plugin's own tests.
 */
class AppMapAgentBridgeTest {

    @Test
    void disabled_mode_when_no_agent_and_synthetic_off() {
        AppMapAgentBridge bridge = new AppMapAgentBridge(false);
        assertThat(bridge.isOperative()).isFalse();
        assertThat(bridge.usesRealAgent()).isFalse();
        assertThatThrownBy(() -> bridge.start("rec-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AppMap recording is not enabled");
    }

    @Test
    void synthetic_mode_allows_start_and_save() throws IOException {
        AppMapAgentBridge bridge = new AppMapAgentBridge(true);
        assertThat(bridge.isOperative()).isTrue();
        assertThat(bridge.usesRealAgent()).isFalse();

        bridge.start("rec-syn");
        bridge.stop("rec-syn");
        Path tmp = Files.createTempDirectory("synthetic-save-");
        Path target = tmp.resolve("rec-syn.appmap.json");

        assertThat(bridge.save("rec-syn", target)).isTrue();
        assertThat(Files.exists(target)).isTrue();

        String body = Files.readString(target);
        assertThat(body).contains("\"app\":\"omnibank\"");
        assertThat(body).contains("\"name\":\"rec-syn\"");
    }

    @Test
    void uptime_is_positive() {
        AppMapAgentBridge bridge = new AppMapAgentBridge(true);
        assertThat(bridge.jvmUptimeMillis()).isGreaterThan(0L);
    }
}
