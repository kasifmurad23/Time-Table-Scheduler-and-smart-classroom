package com.tssc.backend.solver;

import com.tssc.backend.dto.ApiDtos.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TimetableSolverTest {
    private final TimetableSolver solver = new TimetableSolver();

    @Test
    void producesConflictFreeFeasibleScheduleWithRoomAndFacultyRules() {
        GenerateResponse response = solver.solve(demo());
        assertEquals("ok", response.status(), response.message());
        assertEquals(6, response.violations().get("sessions_scheduled"));

        Set<String> sectionSlots = new HashSet<>(), roomSlots = new HashSet<>(), facultySlots = new HashSet<>();
        Map<String,Set<String>> sectionTheoryRooms = new HashMap<>();
        for (TimetableEntry e : response.entries()) {
            assertTrue(sectionSlots.add(e.sectionId()+"/"+e.timeSlotId()), "section clash");
            assertTrue(roomSlots.add(e.roomId()+"/"+e.timeSlotId()), "room clash");
            assertTrue(facultySlots.add(e.facultyId()+"/"+e.timeSlotId()), "faculty clash");
            if (e.courseId().equals("lab")) assertTrue(e.roomId().startsWith("lab"));
            else sectionTheoryRooms.computeIfAbsent(e.sectionId(), x -> new HashSet<>()).add(e.roomId());
            assertTrue(Set.of("f1","f2","f3").contains(e.facultyId()));
        }
        assertTrue(sectionTheoryRooms.values().stream().allMatch(r -> r.size() == 1));
    }

    @Test
    void reportsStructuralRoomAndQualificationFailures() {
        GenerateRequest noRoom = demo();
        noRoom.setRooms(List.of(new Room("small", "classroom", 10), new Room("lab", "lab", 10)));
        GenerateResponse roomFailure = solver.solve(noRoom);
        assertEquals("infeasible", roomFailure.status());
        assertTrue(roomFailure.message().contains("no suitable room"));

        GenerateRequest noFaculty = demo();
        noFaculty.setQualifications(List.of());
        GenerateResponse facultyFailure = solver.solve(noFaculty);
        assertEquals("infeasible", facultyFailure.status());
        assertEquals(List.of("input"), facultyFailure.causes());
        assertTrue(facultyFailure.message().contains("no qualified faculty"));
    }

    @Test
    void facultyWeeklyLoadLimitIsHardAndDiagnosed() {
        GenerateRequest request=demo();
        request.setFaculty(List.of(new Faculty("f1",0),new Faculty("f2",0),new Faculty("f3",0)));
        GenerateResponse response=solver.solve(request);
        assertEquals("infeasible",response.status());
        assertTrue(response.causes().contains("loads"));
    }

    @Test
    void reportsInfeasibleClashingScheduleAndCause() {
        GenerateRequest request = demo();
        request.setTimeSlots(List.of(new TimeSlot("only", 1, 1)));
        GenerateResponse response = solver.solve(request);
        assertEquals("infeasible", response.status());
        assertFalse(response.causes().isEmpty());
    }

    @Test
    void electiveMustUseReservedBlock() {
        GenerateRequest request = demo();
        request.setCourses(List.of(new Course("core", 1, false, true), new Course("lab", 1, true, false)));
        request.setSectionCourses(List.of(new SectionCourse("s1", "core", 1), new SectionCourse("s1", "lab", 1)));
        request.setElectiveBlocks(List.of());
        GenerateResponse response = solver.solve(request);
        assertEquals("infeasible", response.status());
        assertTrue(response.message().toLowerCase().contains("elective"));
    }

    @Test
    void parallelLabBatchesAreSynchronizedIntoDifferentCourses() {
        GenerateRequest request = new GenerateRequest();
        request.setInstitutionId("inst");
        request.setSections(List.of(new Section("s",60,2)));
        request.setCourses(List.of(new Course("a",1,false,false),new Course("al",2,true,false),
                new Course("b",1,false,false),new Course("bl",2,true,false)));
        request.setSectionCourses(List.of(new SectionCourse("s","a",1),new SectionCourse("s","al",2),
                new SectionCourse("s","b",1),new SectionCourse("s","bl",2)));
        request.setFaculty(List.of(new Faculty("fa",10),new Faculty("fb",10)));
        request.setRooms(List.of(new Room("class","classroom",70),new Room("lab1","lab",35),new Room("lab2","lab",35)));
        request.setTimeSlots(week(4));
        request.setQualifications(List.of(new Qualification("fa","a"),new Qualification("fa","al"),
                new Qualification("fb","b"),new Qualification("fb","bl")));
        request.setTimeLimitSeconds(12);
        GenerateResponse response=solver.solve(request);
        assertEquals("ok",response.status(),response.message());
        assertEquals(10,response.entries().size());
        Map<String,List<TimetableEntry>> bySlot=new HashMap<>();
        for(TimetableEntry e:response.entries())bySlot.computeIfAbsent(e.timeSlotId(),x->new ArrayList<>()).add(e);
        int labSlots=0;
        for(List<TimetableEntry> entries:bySlot.values()){
            List<TimetableEntry> labs=entries.stream().filter(e->e.batch()!=null).toList();
            if(!labs.isEmpty()){
                labSlots++; assertEquals(2,labs.size()); assertEquals(Set.of(1,2),new HashSet<>(labs.stream().map(TimetableEntry::batch).toList()));
                assertEquals(2,labs.stream().map(TimetableEntry::courseId).distinct().count());
                assertTrue(entries.stream().noneMatch(e->e.batch()==null));
            }
        }
        assertEquals(4,labSlots);
    }

    @Test
    void appliesTheoryDailySpreadInstructorConsistencyAndConsecutivePeriodRule() {
        GenerateRequest request = demo();
        request.setCourses(List.of(new Course("core",3,false,false),new Course("lab",1,true,false)));
        request.setSectionCourses(List.of(new SectionCourse("s1","core",3),new SectionCourse("s1","lab",1)));
        request.setTimeSlots(week(4));
        GenerateResponse response=solver.solve(request);
        assertEquals("ok",response.status(),response.message());
        List<TimetableEntry> core=response.entries().stream().filter(e->e.courseId().equals("core")).toList();
        assertEquals(1,core.stream().map(TimetableEntry::facultyId).distinct().count());
        Map<String,Integer> dayBySlot=new HashMap<>();for(TimeSlot t:request.getTimeSlots())dayBySlot.put(t.id(),t.dayOfWeek());
        assertEquals(3,core.stream().map(e->dayBySlot.get(e.timeSlotId())).distinct().count());
        for(int i=0;i<core.size();i++)for(int j=i+1;j<core.size();j++){
            String firstSlot=core.get(i).timeSlotId(), secondSlot=core.get(j).timeSlotId();
            TimeSlot a=request.getTimeSlots().stream().filter(t->t.id().equals(firstSlot)).findFirst().orElseThrow();
            TimeSlot b=request.getTimeSlots().stream().filter(t->t.id().equals(secondSlot)).findFirst().orElseThrow();
            assertFalse(a.dayOfWeek()==b.dayOfWeek()&&Math.abs(a.period()-b.period())==1);
        }
    }

    @Test
    void sectionWithEnoughSessionsUsesEveryAvailableWeekday() {
        GenerateRequest request=demo();
        request.setSections(List.of(new Section("s1",40)));
        request.setCourses(List.of(new Course("a",3,false,false),new Course("b",3,false,false)));
        request.setSectionCourses(List.of(new SectionCourse("s1","a",3),new SectionCourse("s1","b",3)));
        request.setTimeSlots(week(4));
        request.setQualifications(List.of(new Qualification("f1","a"),new Qualification("f2","b")));
        GenerateResponse response=solver.solve(request);
        assertEquals("ok",response.status(),response.message());
        Set<Integer> activeDays=new HashSet<>();
        Map<String,Integer> days=new HashMap<>();for(TimeSlot t:request.getTimeSlots())days.put(t.id(),t.dayOfWeek());
        for(TimetableEntry e:response.entries())activeDays.add(days.get(e.timeSlotId()));
        assertEquals(Set.of(1,2,3,4,5),activeDays);
    }

    private static GenerateRequest demo() {
        GenerateRequest r=new GenerateRequest();r.setInstitutionId("demo");
        r.setSections(List.of(new Section("s1",60),new Section("s2",55)));
        r.setCourses(List.of(new Course("core",2,false,false),new Course("lab",1,true,false)));
        r.setSectionCourses(List.of(new SectionCourse("s1","core",2),new SectionCourse("s1","lab",1),
                new SectionCourse("s2","core",2),new SectionCourse("s2","lab",1)));
        r.setFaculty(List.of(new Faculty("f1",10),new Faculty("f2",10),new Faculty("f3",10)));
        r.setRooms(List.of(new Room("r1","classroom",70),new Room("r2","classroom",70),new Room("lab1","lab",65)));
        r.setTimeSlots(week(4));
        r.setQualifications(List.of(new Qualification("f1","core"),new Qualification("f2","core"),
                new Qualification("f2","lab"),new Qualification("f3","lab")));
        r.setTimeLimitSeconds(15);return r;
    }
    private static List<TimeSlot> week(int periods){List<TimeSlot> slots=new ArrayList<>();for(int d=1;d<=5;d++)for(int p=1;p<=periods;p++)slots.add(new TimeSlot("d"+d+"p"+p,d,p));return slots;}
}
