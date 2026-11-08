package com.tssc.backend;

import com.tssc.backend.dto.ApiDtos.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubstitutionApiTest {
    private final ApiController api = new ApiController(null, null);

    @Test
    void ranksCandidatesByPriorityThenWeeklyLoadAndExcludesBusyAndRequester() {
        RankSubstitutesResponse response = api.rank(new RankSubstitutesRequest("requester",
                List.of("section-high","section-low"), List.of("course"), List.of("busy"),
                List.of("requester","section-high","section-low","course","busy","other"),
                Map.of("section-high",12,"section-low",4,"course",2)));
        assertEquals(List.of("section-low","section-high","course","other"),
                response.candidates().stream().map(RankedCandidate::facultyId).toList());
        assertEquals(List.of(1,1,2,3),response.candidates().stream().map(RankedCandidate::priority).toList());
    }

    @Test
    void resolvesAcceptedCandidateAndReportsRejectionAndTimeoutPenalties() {
        ResolveSubstitutesResponse response=api.resolve(new ResolveSubstitutesRequest(List.of(
                new CandidateState("later",1,"accepted","2026-11-04T10:05:00",7),
                new CandidateState("winner",1,"accepted","2026-11-04T10:08:00",3),
                new CandidateState("reject",2,"rejected",null,4),
                new CandidateState("pending",3,"pending",null,1)),false));
        assertTrue(response.shouldResolve());
        assertEquals("winner",response.winnerFacultyId());
        assertEquals(List.of(new Penalty("reject","rejected"),new Penalty("pending","timed_out")),response.penalties());
    }
}
