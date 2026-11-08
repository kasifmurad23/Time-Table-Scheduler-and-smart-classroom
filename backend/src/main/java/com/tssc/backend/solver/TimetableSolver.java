package com.tssc.backend.solver;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearArgument;
import com.tssc.backend.dto.ApiDtos.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Java CP-SAT implementation of the TS-SC timetable model. */
@Service
public class TimetableSolver {
    private static final String LOADS = "loads", ROOMS = "rooms", QUALIFICATIONS = "qualifications", ELECTIVES = "electives";
    static { Loader.loadNativeLibraries(); }

    private record Session(String sectionId, String courseId, int strength, boolean labRequired,
                           boolean elective, Integer batch) {}
    private record Key(int session, int item) {}
    private record CourseKey(String sectionId, String courseId, Integer batch) {}
    private static final class Built {
        CpModel model = new CpModel();
        List<Session> sessions = new ArrayList<>();
        Map<Key,BoolVar> slots = new HashMap<>(), rooms = new HashMap<>(), faculty = new HashMap<>();
        Map<Integer,LinearArgument> facultyLoads = new LinkedHashMap<>();
        List<LinearArgument> gaps = new ArrayList<>(), usedDays = new ArrayList<>();
        IntVar maxLoad;
    }
    private static final class StructuralInfeasibility extends RuntimeException {
        StructuralInfeasibility(String message) { super(message); }
    }
    private record SolveResult(CpSolver solver, CpSolverStatus status) {}

    public GenerateResponse solve(GenerateRequest request) {
        Built built;
        try { built = build(request, Set.of()); }
        catch (IllegalArgumentException | StructuralInfeasibility e) {
            return GenerateResponse.infeasible(e.getMessage(), List.of("input"));
        }
        if (built.sessions.isEmpty()) return GenerateResponse.success(List.of(), Map.of());

        SolveResult result = run(built, request.getTimeLimitSeconds());
        CpSolver solver = result.solver();
        CpSolverStatus status = result.status();
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            return response(request, built, solver);
        }
        return GenerateResponse.infeasible("No feasible timetable exists for the given constraints.", diagnose(request));
    }

    private Built build(GenerateRequest req, Set<String> relax) {
        Map<String,Section> sections = req.getSections().stream().collect(Collectors.toMap(Section::id, x -> x, (a,b)->b, LinkedHashMap::new));
        Map<String,Course> courses = req.getCourses().stream().collect(Collectors.toMap(Course::id, x -> x, (a,b)->b, LinkedHashMap::new));
        List<Faculty> faculty = req.getFaculty(); List<Room> rooms = req.getRooms(); List<TimeSlot> slots = req.getTimeSlots();
        Map<String,Integer> slotIndex = new HashMap<>(); for (int i=0;i<slots.size();i++) slotIndex.put(slots.get(i).id(),i);
        Set<String> qualified = req.getQualifications().stream().map(q -> q.facultyId()+"\u0000"+q.courseId()).collect(Collectors.toSet());
        Set<String> electiveBlocks = new HashSet<>(req.getElectiveBlocks());

        Built b = new Built();
        for (SectionCourse sc : req.getSectionCourses()) {
            Section sec = sections.get(sc.sectionId()); Course course = courses.get(sc.courseId());
            if (sec == null || course == null) throw new IllegalArgumentException("section_courses references unknown section/course: " + sc);
            int n = sc.sessionsPerWeek() == null || sc.sessionsPerWeek() == 0 ? course.sessionsPerWeek() : sc.sessionsPerWeek();
            int batches = Math.max(1, sec.batchCount());
            if (course.labRequired() && batches > 1) {
                int strength = (sec.strength() + batches - 1) / batches;
                for (int batch=1;batch<=batches;batch++) for(int i=0;i<n;i++)
                    b.sessions.add(new Session(sec.id(),course.id(),strength,true,course.isElective(),batch));
            } else for(int i=0;i<n;i++) b.sessions.add(new Session(sec.id(),course.id(),sec.strength(),course.labRequired(),course.isElective(),null));
        }
        if (b.sessions.isEmpty()) return b;

        for (int si=0;si<b.sessions.size();si++) {
            Session s=b.sessions.get(si);
            List<Integer> allowedSlots = new ArrayList<>();
            if (relax.contains(ELECTIVES)) { for(int i=0;i<slots.size();i++) allowedSlots.add(i); }
            else if (s.elective()) {
                if (electiveBlocks.stream().noneMatch(slotIndex::containsKey)) throw new StructuralInfeasibility("elective course has no reserved slot-blocks");
                for(String block:electiveBlocks) if(slotIndex.containsKey(block)) allowedSlots.add(slotIndex.get(block));
            } else for(int i=0;i<slots.size();i++) if(!electiveBlocks.contains(slots.get(i).id())) allowedSlots.add(i);

            List<Integer> allowedFaculty = new ArrayList<>();
            for(int i=0;i<faculty.size();i++) if(relax.contains(QUALIFICATIONS) || qualified.contains(faculty.get(i).id()+"\u0000"+s.courseId())) allowedFaculty.add(i);
            if (allowedFaculty.isEmpty()) throw new StructuralInfeasibility("no qualified faculty for course " + s.courseId());

            List<Integer> allowedRooms = new ArrayList<>();
            for(int i=0;i<rooms.size();i++) {
                Room r=rooms.get(i);
                if(relax.contains(ROOMS) || (r.capacity()>=s.strength() && r.roomType().equals(s.labRequired()?"lab":"classroom"))) allowedRooms.add(i);
            }
            if (allowedRooms.isEmpty()) throw new StructuralInfeasibility("no suitable room for course " + s.courseId()+" (strength "+s.strength()+", lab="+s.labRequired()+")");

            List<BoolVar> xs=new ArrayList<>(), rs=new ArrayList<>(), fs=new ArrayList<>();
            for(int t:allowedSlots) { BoolVar v=b.model.newBoolVar("slot_"+si+"_"+t); b.slots.put(new Key(si,t),v); xs.add(v); }
            for(int r:allowedRooms) { BoolVar v=b.model.newBoolVar("room_"+si+"_"+r); b.rooms.put(new Key(si,r),v); rs.add(v); }
            for(int f:allowedFaculty) { BoolVar v=b.model.newBoolVar("fac_"+si+"_"+f); b.faculty.put(new Key(si,f),v); fs.add(v); }
            b.model.addEquality(sum(xs),1); b.model.addEquality(sum(rs),1); b.model.addEquality(sum(fs),1);
        }

        addClashes(b, slots, rooms, faculty, sections);
        for(int fi=0;fi<faculty.size();fi++) {
            List<LinearArgument> assigned=new ArrayList<>();
            for(int si=0;si<b.sessions.size();si++) if(b.faculty.containsKey(new Key(si,fi))) assigned.add(b.faculty.get(new Key(si,fi)));
            LinearArgument load=sum(assigned); b.facultyLoads.put(fi,load);
            b.model.addLessOrEqual(load,relax.contains(LOADS)?1_000_000_000L:faculty.get(fi).maxWeeklyLoad());
        }
        addFacultyConsistency(b,faculty.size());
        addTheoryRoomConsistency(b,rooms.size(),relax);
        addDailyDistribution(b,slots,courses,sections);
        addSoftConstraints(b,req,slots);
        return b;
    }

    private void addClashes(Built b,List<TimeSlot> slots,List<Room> rooms,List<Faculty> faculty,Map<String,Section> sections) {
        Map<String,List<Integer>> theory=new HashMap<>(); Map<String,List<Integer>> labsBySection=new HashMap<>();
        Map<String,List<Integer>> labsByCourse=new HashMap<>(); Map<String,Set<String>> labCourses=new HashMap<>();
        for(int si=0;si<b.sessions.size();si++) { Session s=b.sessions.get(si);
            if(s.batch()==null) theory.computeIfAbsent(s.sectionId(),k->new ArrayList<>()).add(si);
            else { labsBySection.computeIfAbsent(s.sectionId()+"\u0000"+s.batch(),k->new ArrayList<>()).add(si);
                labsByCourse.computeIfAbsent(s.sectionId()+"\u0000"+s.courseId(),k->new ArrayList<>()).add(si);
                labCourses.computeIfAbsent(s.sectionId(),k->new HashSet<>()).add(s.courseId()); }
        }
        for(int t=0;t<slots.size();t++) {
            for(Section sec:sections.values()) {
                int batches=Math.max(1,sec.batchCount()); List<LinearArgument> tv=slotVars(b,theory.getOrDefault(sec.id(),List.of()),t);
                if(!tv.isEmpty()) b.model.addLessOrEqual(sum(tv),1);
                if(batches<=1) { List<LinearArgument> all=new ArrayList<>(tv); all.addAll(slotVars(b,labsBySection.getOrDefault(sec.id()+"\u00001",List.of()),t)); if(!all.isEmpty()) b.model.addLessOrEqual(sum(all),1); }
                else {
                    Map<Integer,List<LinearArgument>> batchVars=new HashMap<>();
                    for(int batch=1;batch<=batches;batch++) { List<LinearArgument> bv=slotVars(b,labsBySection.getOrDefault(sec.id()+"\u0000"+batch,List.of()),t); batchVars.put(batch,bv);
                        if(!tv.isEmpty()||!bv.isEmpty()) { List<LinearArgument> x=new ArrayList<>(tv); x.addAll(bv); b.model.addLessOrEqual(sum(x),1); } }
                    if(labCourses.getOrDefault(sec.id(),Set.of()).size()>=batches)
                        for(int batch=2;batch<=batches;batch++) b.model.addEquality(sum(batchVars.get(1)),sum(batchVars.get(batch)));
                    for(String cid:labCourses.getOrDefault(sec.id(),Set.of())) { List<LinearArgument> cv=slotVars(b,labsByCourse.getOrDefault(sec.id()+"\u0000"+cid,List.of()),t); if(!cv.isEmpty()) b.model.addLessOrEqual(sum(cv),1); }
                }
            }
            for(int ri=0;ri<rooms.size();ri++) { List<LinearArgument> uses=new ArrayList<>();
                for(int si=0;si<b.sessions.size();si++) { BoolVar rv=b.rooms.get(new Key(si,ri)), sv=b.slots.get(new Key(si,t)); if(rv!=null&&sv!=null) { BoolVar use=b.model.newBoolVar("use_room_"+si+"_"+ri+"_"+t); b.model.addMultiplicationEquality(use,rv,sv); uses.add(use); } }
                if(!uses.isEmpty()) b.model.addLessOrEqual(sum(uses),1);
            }
            for(int fi=0;fi<faculty.size();fi++) { List<LinearArgument> uses=new ArrayList<>();
                for(int si=0;si<b.sessions.size();si++) { BoolVar fv=b.faculty.get(new Key(si,fi)), sv=b.slots.get(new Key(si,t)); if(fv!=null&&sv!=null) { BoolVar use=b.model.newBoolVar("use_fac_"+si+"_"+fi+"_"+t); b.model.addMultiplicationEquality(use,fv,sv); uses.add(use); } }
                if(!uses.isEmpty()) b.model.addLessOrEqual(sum(uses),1);
            }
        }
    }
    private static List<LinearArgument> slotVars(Built b,List<Integer> sessions,int t) { List<LinearArgument> out=new ArrayList<>(); for(int si:sessions) { BoolVar v=b.slots.get(new Key(si,t)); if(v!=null) out.add(v); } return out; }

    private void addFacultyConsistency(Built b,int count) {
        Map<String,List<Integer>> groups=new HashMap<>(); for(int si=0;si<b.sessions.size();si++) { Session s=b.sessions.get(si); groups.computeIfAbsent(s.sectionId()+"\u0000"+s.courseId(),k->new ArrayList<>()).add(si); }
        for(List<Integer> group:groups.values()) for(int j=1;j<group.size();j++) for(int f=0;f<count;f++) { BoolVar a=b.faculty.get(new Key(group.get(0),f)), c=b.faculty.get(new Key(group.get(j),f)); if(a!=null&&c!=null)b.model.addEquality(a,c); }
    }

    private void addTheoryRoomConsistency(Built b,int count,Set<String> relax) {
        if(relax.contains(ROOMS)) return;
        Map<String,List<Integer>> groups=new HashMap<>(); for(int si=0;si<b.sessions.size();si++) if(!b.sessions.get(si).labRequired()) groups.computeIfAbsent(b.sessions.get(si).sectionId(),k->new ArrayList<>()).add(si);
        for(List<Integer> group:groups.values()) for(int j=1;j<group.size();j++) for(int r=0;r<count;r++) { BoolVar a=b.rooms.get(new Key(group.get(0),r)), c=b.rooms.get(new Key(group.get(j),r)); if(a!=null&&c!=null)b.model.addEquality(a,c); }
        List<Integer> first=groups.values().stream().filter(x->!x.isEmpty()).map(x->x.get(0)).toList(); Set<Integer> available=new HashSet<>();
        for(int si:first) for(int r=0;r<count;r++) if(b.rooms.containsKey(new Key(si,r))) available.add(r);
        if(available.size()>=first.size()) for(int r:available) { List<LinearArgument> use=new ArrayList<>(); for(int si:first) { BoolVar v=b.rooms.get(new Key(si,r)); if(v!=null)use.add(v); } if(use.size()>1)b.model.addLessOrEqual(sum(use),1); }
    }

    private void addDailyDistribution(Built b,List<TimeSlot> slots,Map<String,Course> courses,Map<String,Section> sections) {
        SortedSet<Integer> days=slots.stream().map(TimeSlot::dayOfWeek).collect(Collectors.toCollection(TreeSet::new)); if(days.isEmpty())return;
        Map<String,Integer> slotAt=new HashMap<>(); Map<Integer,SortedSet<Integer>> periods=new HashMap<>(); Map<Integer,Integer> slotCount=new HashMap<>();
        for(int i=0;i<slots.size();i++){TimeSlot t=slots.get(i);slotAt.put(t.dayOfWeek()+"\u0000"+t.period(),i);periods.computeIfAbsent(t.dayOfWeek(),k->new TreeSet<>()).add(t.period());slotCount.merge(t.dayOfWeek(),1,Integer::sum);}
        int pMax=slotCount.values().stream().max(Integer::compare).orElse(1);
        Map<CourseKey,List<Integer>> courseGroups=new HashMap<>(); for(int si=0;si<b.sessions.size();si++){Session s=b.sessions.get(si);courseGroups.computeIfAbsent(new CourseKey(s.sectionId(),s.courseId(),s.batch()),k->new ArrayList<>()).add(si);}
        for(var entry:courseGroups.entrySet()) { CourseKey key=entry.getKey(); List<Integer> sis=entry.getValue(); Course crs=courses.get(key.courseId()); if(crs==null)continue;
            int maxDaily;
            if(!crs.labRequired()) maxDaily=sis.size()<=days.size()?1:Math.min(3,Math.max(2,(sis.size()+days.size()-1)/days.size()));
            else maxDaily=Math.min(2,sis.size());
            for(int d:days) { List<LinearArgument> daily=new ArrayList<>(); for(int si:sis) for(int t=0;t<slots.size();t++)if(slots.get(t).dayOfWeek()==d){BoolVar v=b.slots.get(new Key(si,t));if(v!=null)daily.add(v);} if(!daily.isEmpty())b.model.addLessOrEqual(sum(daily),maxDaily);
                if(!crs.labRequired()) { List<Integer> ps=new ArrayList<>(periods.getOrDefault(d,new TreeSet<>())); for(int pi=0;pi+1<ps.size();pi++)if(ps.get(pi+1)==ps.get(pi)+1){Integer t1=slotAt.get(d+"\u0000"+ps.get(pi)),t2=slotAt.get(d+"\u0000"+ps.get(pi+1)); if(t1!=null&&t2!=null){List<LinearArgument>x=slotVars(b,sis,t1);x.addAll(slotVars(b,sis,t2));if(!x.isEmpty())b.model.addLessOrEqual(sum(x),1);}} }
            }
        }
        if(days.size()>1) for(Section sec:sections.values()) { int batchCount=Math.max(1,sec.batchCount()); List<Integer> selected=new ArrayList<>(); for(int si=0;si<b.sessions.size();si++){Session s=b.sessions.get(si);if(s.sectionId().equals(sec.id())&&(batchCount<=1||s.batch()==null||s.batch()==1))selected.add(si);}
            int n=selected.size(), avg=(n+days.size()-1)/days.size(), cap=Math.max(avg,Math.min(pMax,Math.max(4,avg+1)));
            for(int d:days){List<LinearArgument> daily=new ArrayList<>();for(int t=0;t<slots.size();t++)if(slots.get(t).dayOfWeek()==d)daily.addAll(slotVars(b,selected,t));if(!daily.isEmpty()){b.model.addLessOrEqual(sum(daily),cap);if(n>=days.size())b.model.addGreaterOrEqual(sum(daily),1);}}
        }
    }

    private void addSoftConstraints(Built b,GenerateRequest req,List<TimeSlot> slots) {
        if(b.sessions.isEmpty())return;
        double[] weights={req.getWeights().getGap(),req.getWeights().getSpread(),req.getWeights().getLoad()};
        long wg=(long)Math.rint(weights[0]*100), ws=(long)Math.rint(weights[1]*100), wl=(long)Math.rint(weights[2]*100);
        SortedSet<Integer> days=slots.stream().map(TimeSlot::dayOfWeek).collect(Collectors.toCollection(TreeSet::new));
        Map<Integer,SortedSet<Integer>> periods=new HashMap<>();Map<String,Integer> slotAt=new HashMap<>();
        for(int t=0;t<slots.size();t++){TimeSlot sl=slots.get(t);periods.computeIfAbsent(sl.dayOfWeek(),k->new TreeSet<>()).add(sl.period());slotAt.put(sl.dayOfWeek()+"\u0000"+sl.period(),t);}
        Set<String> sectionIds=b.sessions.stream().map(Session::sectionId).collect(Collectors.toCollection(TreeSet::new)); Map<String,BoolVar> occupied=new HashMap<>();
        for(String sec:sectionIds)for(int d:days)for(int p:periods.getOrDefault(d,new TreeSet<>())){int t=slotAt.get(d+"\u0000"+p);List<LinearArgument> vars=new ArrayList<>();for(int si=0;si<b.sessions.size();si++)if(b.sessions.get(si).sectionId().equals(sec)){BoolVar v=b.slots.get(new Key(si,t));if(v!=null)vars.add(v);}BoolVar occ=b.model.newBoolVar("occ_"+sec+"_"+d+"_"+p);if(!vars.isEmpty())b.model.addMaxEquality(occ,toArray(vars));else b.model.addEquality(occ,0);occupied.put(sec+"\u0000"+d+"\u0000"+p,occ);}
        for(String sec:sectionIds)for(int d:days){List<LinearArgument> occs=new ArrayList<>();for(int p:periods.getOrDefault(d,new TreeSet<>()))occs.add(occupied.get(sec+"\u0000"+d+"\u0000"+p));if(!occs.isEmpty()){BoolVar used=b.model.newBoolVar("sec_day_used_"+sec+"_"+d);b.model.addMaxEquality(used,toArray(occs));b.usedDays.add(used);}}
        for(String sec:sectionIds)for(int d:days){List<Integer> ps=new ArrayList<>(periods.getOrDefault(d,new TreeSet<>()));for(int i=1;i+1<ps.size();i++){int prev=ps.get(i-1),p=ps.get(i),next=ps.get(i+1);if(next!=p+1||p!=prev+1)continue;BoolVar gap=b.model.newBoolVar("gap_"+sec+"_"+d+"_"+p);b.model.addGreaterOrEqual(gap,LinearExpr.newBuilder().add((LinearArgument)occupied.get(sec+"\u0000"+d+"\u0000"+prev)).add((LinearArgument)occupied.get(sec+"\u0000"+d+"\u0000"+next)).addTerm(occupied.get(sec+"\u0000"+d+"\u0000"+p),-1).add(-1).build());b.gaps.add(gap);}}
        Map<CourseKey,List<Integer>> groups=new HashMap<>();for(int si=0;si<b.sessions.size();si++){Session s=b.sessions.get(si);groups.computeIfAbsent(new CourseKey(s.sectionId(),s.courseId(),s.batch()),k->new ArrayList<>()).add(si);}
        for(var entry:groups.entrySet())for(int d:days){List<LinearArgument> onDay=new ArrayList<>();for(int si:entry.getValue())for(int t=0;t<slots.size();t++)if(slots.get(t).dayOfWeek()==d){BoolVar v=b.slots.get(new Key(si,t));if(v!=null)onDay.add(v);}if(!onDay.isEmpty()){BoolVar used=b.model.newBoolVar("used_"+entry.getKey().sectionId()+"_"+entry.getKey().courseId()+"_"+entry.getKey().batch()+"_"+d);for(LinearArgument v:onDay)b.model.addGreaterOrEqual(used,v);b.usedDays.add(used);}}
        int maxPossible=b.sessions.size();b.maxLoad=b.model.newIntVar(0,maxPossible,"max_load");for(LinearArgument load:b.facultyLoads.values())b.model.addGreaterOrEqual(b.maxLoad,load);
        com.google.ortools.sat.LinearExprBuilder objective=LinearExpr.newBuilder();for(LinearArgument gap:b.gaps)objective.addTerm(gap,wg);for(LinearArgument used:b.usedDays)objective.addTerm(used,-ws);objective.addTerm(b.maxLoad,wl);b.model.minimize(objective.build());
    }

    private GenerateResponse response(GenerateRequest req,Built b,CpSolver solver) {
        List<TimetableEntry> entries=new ArrayList<>();for(int si=0;si<b.sessions.size();si++){int ti=selected(b.slots,si,req.getTimeSlots().size(),solver),ri=selected(b.rooms,si,req.getRooms().size(),solver),fi=selected(b.faculty,si,req.getFaculty().size(),solver);Session s=b.sessions.get(si);entries.add(new TimetableEntry(s.sectionId(),s.courseId(),req.getFaculty().get(fi).id(),req.getRooms().get(ri).id(),req.getTimeSlots().get(ti).id(),s.elective(),s.batch()));}
        Map<String,Object> violations=new LinkedHashMap<>();violations.put("total_gaps",b.gaps.stream().mapToLong(solver::value).sum());violations.put("course_days_used",b.usedDays.stream().mapToLong(solver::value).sum());violations.put("max_faculty_load",solver.value(b.maxLoad));Map<String,Long> loads=new LinkedHashMap<>();for(var e:b.facultyLoads.entrySet())loads.put(req.getFaculty().get(e.getKey()).id(),solver.value(e.getValue()));violations.put("faculty_loads",loads);violations.put("sessions_scheduled",entries.size());return GenerateResponse.success(entries,violations);
    }
    private static int selected(Map<Key,BoolVar> variables,int si,int count,CpSolver solver){for(int i=0;i<count;i++){BoolVar v=variables.get(new Key(si,i));if(v!=null&&solver.booleanValue(v))return i;}throw new IllegalStateException("CP-SAT returned an incomplete assignment");}
    private List<String> diagnose(GenerateRequest req){List<String> causes=new ArrayList<>();for(String group:List.of(LOADS,ROOMS,QUALIFICATIONS,ELECTIVES)){try{Built b=build(req,Set.of(group));if(b.sessions.isEmpty())continue;SolveResult result=run(b,Math.min(req.getTimeLimitSeconds(),15));if(result.status()==CpSolverStatus.OPTIMAL||result.status()==CpSolverStatus.FEASIBLE)causes.add(group);}catch(RuntimeException ignored){}}
        return causes.isEmpty()?List.of("unknown"):causes;}
    private static SolveResult run(Built b,double seconds){CpSolver solver=new CpSolver();solver.getParameters().setMaxTimeInSeconds(seconds);solver.getParameters().setNumSearchWorkers(8);CpSolverStatus status=solver.solve(b.model);return new SolveResult(solver,status);}
    private static LinearExpr sum(List<? extends LinearArgument> vars){return vars.isEmpty()?LinearExpr.constant(0):LinearExpr.sum(vars.toArray(LinearArgument[]::new));}
    private static LinearArgument[] toArray(List<? extends LinearArgument> vars){return vars.toArray(LinearArgument[]::new);}
}
