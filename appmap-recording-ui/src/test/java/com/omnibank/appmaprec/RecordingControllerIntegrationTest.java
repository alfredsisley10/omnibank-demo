package com.omnibank.appmaprec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.appmaprec.internal.AppMapAgentBridge;
import com.omnibank.appmaprec.internal.InMemoryRecordingService;
import com.omnibank.appmaprec.internal.RecordingArchive;
import com.omnibank.appmaprec.web.RecordingController;
import com.omnibank.appmaprec.web.RecordingExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test that boots a MockMvc with the RecordingController only —
 * fast, hermetic, and exercises the JSON contract the recording UI's
 * JS client depends on.
 */
class RecordingControllerIntegrationTest {

    private MockMvc mvc;
    private RecordingService service;
    private RecordingArchive archive;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        archive = new RecordingArchive(tmp);
        AppMapAgentBridge bridge = new AppMapAgentBridge(true); // synthetic
        service = new InMemoryRecordingService(bridge, archive, Clock.systemUTC());
        mvc = MockMvcBuilders
                .standaloneSetup(new RecordingController(service, archive))
                .setControllerAdvice(new RecordingExceptionHandler())
                .build();
    }

    @Test
    void status_endpoint_reports_synthetic_mode_enabled() throws Exception {
        mvc.perform(get("/api/v1/appmap/recordings/_status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordingEnabled").value(true))
                .andExpect(jsonPath("$.active").value(0));
    }

    @Test
    void start_then_save_writes_appmap_file_and_returns_dto() throws Exception {
        String startBody = mapper.writeValueAsString(java.util.Map.of(
                "label", "happy path",
                "description", "happy-path scenario"
        ));
        var startResponse = mvc.perform(post("/api/v1/appmap/recordings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECORDING"))
                .andExpect(jsonPath("$.label").value("happy path"))
                .andReturn();
        String json = startResponse.getResponse().getContentAsString();
        String id = (String) mapper.readValue(json, java.util.Map.class).get("id");

        // append a couple of actions
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/appmap/recordings/" + id + "/actions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(java.util.Map.of(
                                    "kind", "test.action",
                                    "description", "step " + i
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actionCount").value(i + 1));
        }

        // save
        mvc.perform(post("/api/v1/appmap/recordings/" + id + "/save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.savedFile").exists());

        // archive should now contain the file
        mvc.perform(get("/api/v1/appmap/recordings/_archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void cancel_marks_terminal_and_appends_reason() throws Exception {
        String startBody = "{\"label\":\"to-cancel\",\"description\":\"\"}";
        var startResponse = mvc.perform(post("/api/v1/appmap/recordings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isCreated())
                .andReturn();
        String id = (String) mapper.readValue(
                startResponse.getResponse().getContentAsString(), java.util.Map.class).get("id");

        mvc.perform(post("/api/v1/appmap/recordings/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"abort test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureReason").value("abort test"));
    }

    @Test
    void unknown_recording_id_returns_404_for_get() throws Exception {
        mvc.perform(get("/api/v1/appmap/recordings/rec-does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknown_recording_returns_400_for_action() throws Exception {
        mvc.perform(post("/api/v1/appmap/recordings/rec-missing/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"k\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recording.bad_request"));
    }

    @Test
    void delete_archived_returns_deleted_flag() throws Exception {
        mvc.perform(delete("/api/v1/appmap/recordings/_archive/missing.appmap.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.deleted").value(false));
    }
}
