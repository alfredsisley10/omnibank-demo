package com.omnibank.appmaprec.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based bridge to the AppMap Java agent's runtime control API.
 *
 * <p>The agent ships its remote-recording control class as part of the
 * {@code -javaagent:appmap-agent.jar} attachment, NOT as a Maven
 * dependency, so this module cannot reference it at compile time without
 * forcing every consumer to add the jar to their classpath. The bridge
 * therefore looks the agent class up reflectively at call time and
 * gracefully degrades when the agent isn't attached — in which case we
 * fall back to a fully synthetic recording mode that records action
 * narrative only (no captured frames).</p>
 *
 * <p>Three modes are observable:
 * <ul>
 *   <li><b>AGENT</b> — the AppMap agent is attached. Start/stop calls
 *       drive its remote recorder and we ask the agent to write the
 *       captured JSON to {@code tmp/appmap/}.</li>
 *   <li><b>SYNTHETIC</b> — agent not attached but the {@code
 *       omnibank.appmap.synthetic-recording} flag is on. Recordings
 *       carry actions but produce a placeholder JSON suitable for
 *       integration tests of the UI itself.</li>
 *   <li><b>DISABLED</b> — neither agent nor synthetic flag. Start calls
 *       throw {@link IllegalStateException}.</li>
 * </ul>
 */
public class AppMapAgentBridge {

    private static final Logger log = LoggerFactory.getLogger(AppMapAgentBridge.class);

    /** Fully-qualified name of AppMap agent's remote recording control class. */
    private static final String REMOTE_RECORDING_CLASS = "com.appland.appmap.record.Recorder";

    private final boolean syntheticMode;
    private final Map<String, Long> activeStarts = new ConcurrentHashMap<>();
    private final Optional<Class<?>> agentClass;

    public AppMapAgentBridge(boolean syntheticMode) {
        this.syntheticMode = syntheticMode;
        this.agentClass = lookupAgentClass();
        if (agentClass.isPresent()) {
            log.info("AppMap agent bridge: AGENT mode (recorder class found on classpath)");
        } else if (syntheticMode) {
            log.info("AppMap agent bridge: SYNTHETIC mode (agent not attached, omnibank.appmap.synthetic-recording=true)");
        } else {
            log.info("AppMap agent bridge: DISABLED (agent not attached, synthetic mode off)");
        }
    }

    /**
     * Whether at least one recording mode is operative for this JVM.
     */
    public boolean isOperative() {
        return agentClass.isPresent() || syntheticMode;
    }

    /**
     * Whether recordings will produce real captured frames as opposed to
     * an action-only synthetic placeholder.
     */
    public boolean usesRealAgent() {
        return agentClass.isPresent();
    }

    /**
     * Begin remote capture under the supplied recording id. Returns the
     * monotonic start time we should record on the {@link
     * com.omnibank.appmaprec.api.Recording} snapshot.
     */
    public long start(String recordingId) {
        if (!isOperative()) {
            throw new IllegalStateException("AppMap recording is not enabled. Restart with -javaagent:appmap-agent.jar or set omnibank.appmap.synthetic-recording=true.");
        }
        long now = System.nanoTime();
        activeStarts.put(recordingId, now);
        invokeAgent("start", recordingId);
        return now;
    }

    /**
     * Stop remote capture without saving. Returns the number of events the
     * agent reports it captured.
     */
    public long stop(String recordingId) {
        Long started = activeStarts.remove(recordingId);
        if (started == null) {
            return 0;
        }
        Object result = invokeAgent("stop", recordingId);
        return parseEventCount(result);
    }

    /**
     * Ask the agent to persist the captured JSON to {@code path}. Returns
     * true if the file exists at the path after the call returns.
     */
    public boolean save(String recordingId, java.nio.file.Path path) {
        try {
            Object payload = invokeAgent("checkpoint", recordingId);
            byte[] data;
            if (payload instanceof byte[] b) {
                data = b;
            } else if (payload instanceof String s) {
                data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                data = syntheticPayload(recordingId);
            }
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, data);
            return java.nio.file.Files.exists(path);
        } catch (java.io.IOException e) {
            log.warn("Failed to write appmap JSON for {}: {}", recordingId, e.toString());
            return false;
        }
    }

    /**
     * For diagnostics only — exposes which JVM PID and uptime we are
     * dealing with so the UI can render a sensible "recording 23s into
     * the JVM" indicator.
     */
    public long jvmUptimeMillis() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private Object invokeAgent(String op, String recordingId) {
        if (agentClass.isEmpty()) {
            return null;
        }
        try {
            Class<?> cls = agentClass.get();
            Method m = cls.getMethod(op, String.class);
            Object recorder = cls.getMethod("getInstance").invoke(null);
            return m.invoke(recorder, recordingId);
        } catch (NoSuchMethodException nsme) {
            log.debug("AppMap agent does not expose op '{}': {}", op, nsme.toString());
            return null;
        } catch (InvocationTargetException | IllegalAccessException e) {
            log.warn("AppMap agent call '{}' failed for {}: {}", op, recordingId, e.toString());
            return null;
        }
    }

    private long parseEventCount(Object result) {
        if (result instanceof Number n) {
            return n.longValue();
        }
        if (result instanceof Map<?, ?> map && map.get("events") instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private byte[] syntheticPayload(String recordingId) {
        String body = "{\"version\":\"1.12\",\"metadata\":{\"name\":\""
                + recordingId
                + "\",\"app\":\"omnibank\",\"recorder\":{\"type\":\"synthetic\",\"name\":\"appmap-recording-ui\"}},\"classMap\":[],\"events\":[]}";
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Optional<Class<?>> lookupAgentClass() {
        try {
            return Optional.of(Class.forName(REMOTE_RECORDING_CLASS, false,
                    Thread.currentThread().getContextClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
