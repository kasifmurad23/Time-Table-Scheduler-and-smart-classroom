package com.tssc.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired private MockMvc mvc;

    @Test
    void healthAndGenerateRoutesRunInSpringApplication() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institution_id\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void malformedGenerationInputGetsClientError() throws Exception {
        mvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void generationKeepsTheExistingSnakeCaseRequestAndResponseContract() throws Exception {
        String request = """
                {"institution_id":"i","sections":[{"id":"s","strength":20}],
                 "courses":[{"id":"c","sessions_per_week":1,"lab_required":false,"is_elective":false}],
                 "section_courses":[{"section_id":"s","course_id":"c"}],
                 "faculty":[{"id":"f","max_weekly_load":1}],
                 "rooms":[{"id":"r","room_type":"classroom","capacity":30}],
                 "time_slots":[{"id":"d1p1","day_of_week":1,"period":1}],
                 "qualifications":[{"faculty_id":"f","course_id":"c"}]}
                """;
        mvc.perform(post("/generate").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.entries[0].section_id").value("s"))
                .andExpect(jsonPath("$.entries[0].time_slot_id").value("d1p1"))
                .andExpect(jsonPath("$.entries[0].is_elective").value(false));
    }
}
