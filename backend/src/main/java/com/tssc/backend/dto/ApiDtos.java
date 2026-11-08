package com.tssc.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
    private ApiDtos() {}

    public static class GenerateRequest {
        @NotBlank private String institutionId;
        @Valid private List<Section> sections = new ArrayList<>();
        @Valid private List<Course> courses = new ArrayList<>();
        @Valid private List<SectionCourse> sectionCourses = new ArrayList<>();
        @Valid private List<Faculty> faculty = new ArrayList<>();
        @Valid private List<Room> rooms = new ArrayList<>();
        @Valid private List<TimeSlot> timeSlots = new ArrayList<>();
        @Valid private List<Qualification> qualifications = new ArrayList<>();
        private List<String> electiveBlocks = new ArrayList<>();
        @Valid private Weights weights = new Weights(10, 5, 5);
        @Min(1) private double timeLimitSeconds = 60;

        public String getInstitutionId() { return institutionId; }
        public void setInstitutionId(String v) { institutionId = v; }
        public List<Section> getSections() { return sections; }
        public void setSections(List<Section> v) { sections = v == null ? new ArrayList<>() : v; }
        public List<Course> getCourses() { return courses; }
        public void setCourses(List<Course> v) { courses = v == null ? new ArrayList<>() : v; }
        public List<SectionCourse> getSectionCourses() { return sectionCourses; }
        public void setSectionCourses(List<SectionCourse> v) { sectionCourses = v == null ? new ArrayList<>() : v; }
        public List<Faculty> getFaculty() { return faculty; }
        public void setFaculty(List<Faculty> v) { faculty = v == null ? new ArrayList<>() : v; }
        public List<Room> getRooms() { return rooms; }
        public void setRooms(List<Room> v) { rooms = v == null ? new ArrayList<>() : v; }
        public List<TimeSlot> getTimeSlots() { return timeSlots; }
        public void setTimeSlots(List<TimeSlot> v) { timeSlots = v == null ? new ArrayList<>() : v; }
        public List<Qualification> getQualifications() { return qualifications; }
        public void setQualifications(List<Qualification> v) { qualifications = v == null ? new ArrayList<>() : v; }
        public List<String> getElectiveBlocks() { return electiveBlocks; }
        public void setElectiveBlocks(List<String> v) { electiveBlocks = v == null ? new ArrayList<>() : v; }
        public Weights getWeights() { return weights; }
        public void setWeights(Weights v) { weights = v == null ? new Weights(10, 5, 5) : v; }
        public double getTimeLimitSeconds() { return timeLimitSeconds; }
        public void setTimeLimitSeconds(double v) { timeLimitSeconds = v; }
    }

    public record Section(@NotBlank String id, @Min(0) int strength, @Min(0) Integer batchCount) {
        public Section { if (batchCount == null) batchCount = 1; }
        public Section(String id, int strength) { this(id, strength, 1); }
    }
    public record Course(@NotBlank String id, @Min(0) int sessionsPerWeek,
                         boolean labRequired, boolean isElective) {}
    public record SectionCourse(@NotBlank String sectionId, @NotBlank String courseId,
                                @Min(0) Integer sessionsPerWeek) {}
    public record Faculty(@NotBlank String id, @Min(0) int maxWeeklyLoad) {}
    public record Room(@NotBlank String id, String roomType, @Min(0) int capacity) {
        public Room { if (roomType == null || roomType.isBlank()) roomType = "classroom"; }
    }
    public record TimeSlot(@NotBlank String id, int dayOfWeek, int period) {}
    public record Qualification(@NotBlank String facultyId, @NotBlank String courseId) {}
    public static class Weights {
        private double gap = 10;
        private double spread = 5;
        private double load = 5;
        public Weights() {}
        public Weights(double gap, double spread, double load) { this.gap = gap; this.spread = spread; this.load = load; }
        public double getGap() { return gap; }
        public void setGap(double gap) { this.gap = gap; }
        public double getSpread() { return spread; }
        public void setSpread(double spread) { this.spread = spread; }
        public double getLoad() { return load; }
        public void setLoad(double load) { this.load = load; }
    }
    public record TimetableEntry(String sectionId, String courseId, String facultyId, String roomId,
                                 String timeSlotId, boolean isElective, Integer batch) {}
    public record GenerateResponse(String status, String message, List<TimetableEntry> entries,
                                   Map<String, Object> violations, List<String> causes) {
        public static GenerateResponse infeasible(String message, List<String> causes) {
            return new GenerateResponse("infeasible", message, List.of(), Map.of(), causes);
        }
        public static GenerateResponse success(List<TimetableEntry> entries, Map<String,Object> violations) {
            return new GenerateResponse("ok", null, entries, violations, List.of());
        }
    }

    public record RankSubstitutesRequest(@NotBlank String requesterFacultyId,
            List<String> sectionFacultyIds, List<String> courseFacultyIds,
            List<String> busyFacultyIds, List<String> allFacultyIds,
            Map<String, Integer> facultyLoads) {
        public RankSubstitutesRequest {
            sectionFacultyIds = safe(sectionFacultyIds); courseFacultyIds = safe(courseFacultyIds);
            busyFacultyIds = safe(busyFacultyIds); allFacultyIds = safe(allFacultyIds);
            facultyLoads = facultyLoads == null ? Map.of() : facultyLoads;
        }
    }
    public record RankedCandidate(String facultyId, int priority, int weeklyLoad) {}
    public record RankSubstitutesResponse(List<RankedCandidate> candidates) {}
    public record CandidateState(@NotBlank String facultyId, int priority, String response,
                                 String respondedAt, int weeklyLoad) {
        public CandidateState { if (response == null || response.isBlank()) response = "pending"; }
    }
    public record ResolveSubstitutesRequest(@Valid List<CandidateState> candidates, boolean deadlinePassed) {
        public ResolveSubstitutesRequest { candidates = safe(candidates); }
    }
    public record Penalty(String facultyId, String reason) {}
    public record ResolveSubstitutesResponse(String winnerFacultyId, boolean shouldResolve,
                                             List<Penalty> penalties) {}
    public record SendEmailRequest(@Email @NotBlank String toEmail, @NotBlank String toName,
            @NotBlank String subject, @NotBlank String bodyHtml, String bodyText) {}
    public record SendEmailResponse(boolean success, String message) {}
    public record HealthResponse(String status) {}

    private static <T> List<T> safe(List<T> value) { return value == null ? new ArrayList<>() : value; }
    public static Map<String,Object> linkedMap() { return new LinkedHashMap<>(); }
}
