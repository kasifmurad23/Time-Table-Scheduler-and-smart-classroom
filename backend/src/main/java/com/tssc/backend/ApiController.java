package com.tssc.backend;

import com.tssc.backend.dto.ApiDtos.*;
import com.tssc.backend.solver.TimetableSolver;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class ApiController {
    private final TimetableSolver solver;
    private final EmailService emailService;

    public ApiController(TimetableSolver solver, EmailService emailService) {
        this.solver = solver;
        this.emailService = emailService;
    }

    @GetMapping({"/health", "/api/health"})
    public HealthResponse health() { return new HealthResponse("ok"); }

    @PostMapping({"/generate", "/api/generate"})
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        return solver.solve(request);
    }

    @PostMapping({"/substitutions/rank", "/api/substitutions/rank"})
    public RankSubstitutesResponse rank(@Valid @RequestBody RankSubstitutesRequest request) {
        Set<String> sections = new HashSet<>(request.sectionFacultyIds());
        Set<String> courses = new HashSet<>(request.courseFacultyIds());
        Set<String> busy = new HashSet<>(request.busyFacultyIds());
        List<RankedCandidate> ranked = new ArrayList<>();
        for (String id : request.allFacultyIds()) {
            if (id.equals(request.requesterFacultyId()) || busy.contains(id)) continue;
            int priority = sections.contains(id) ? 1 : courses.contains(id) ? 2 : 3;
            ranked.add(new RankedCandidate(id, priority, request.facultyLoads().getOrDefault(id, 0)));
        }
        ranked.sort(Comparator.comparingInt(RankedCandidate::priority).thenComparingInt(RankedCandidate::weeklyLoad));
        return new RankSubstitutesResponse(ranked);
    }

    @PostMapping({"/substitutions/resolve", "/api/substitutions/resolve"})
    public ResolveSubstitutesResponse resolve(@Valid @RequestBody ResolveSubstitutesRequest request) {
        List<CandidateState> candidates = request.candidates();
        Optional<CandidateState> accepted = candidates.stream().filter(c -> "accepted".equals(c.response()))
                .min(Comparator.comparingInt(CandidateState::priority)
                        .thenComparingInt(CandidateState::weeklyLoad)
                        .thenComparing(c -> c.respondedAt() == null ? "" : c.respondedAt()));
        boolean shouldResolve = request.deadlinePassed() || accepted.isPresent();
        List<Penalty> penalties = new ArrayList<>();
        if (shouldResolve) for (CandidateState c : candidates) {
            if ("rejected".equals(c.response())) penalties.add(new Penalty(c.facultyId(), "rejected"));
            else if ("pending".equals(c.response()) || "timed_out".equals(c.response())) penalties.add(new Penalty(c.facultyId(), "timed_out"));
        }
        return new ResolveSubstitutesResponse(shouldResolve ? accepted.map(CandidateState::facultyId).orElse(null) : null,
                shouldResolve, penalties);
    }

    @PostMapping({"/notifications/email", "/api/notifications/email"})
    public ResponseEntity<SendEmailResponse> email(@Valid @RequestBody SendEmailRequest request) {
        boolean ok = emailService.send(request);
        return ResponseEntity.ok(new SendEmailResponse(ok,
                ok ? "Email sent successfully" : "Failed to dispatch email"));
    }
}
