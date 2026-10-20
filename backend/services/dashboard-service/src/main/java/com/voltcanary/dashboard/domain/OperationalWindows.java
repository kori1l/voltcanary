package com.voltcanary.dashboard.domain;

import com.voltcanary.common.web.OperationalFact;
import com.voltcanary.common.web.OperatingSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Confirmed observation, never measurement absence, defines an uptime denominator. */
public final class OperationalWindows {
    public record Interval(Instant from,Instant to) {
    }
    public record Result(double scheduledSeconds,double observedSeconds,double downtimeSeconds,double plannedSeconds,
            Double uptimeRatio,String coverage,String dstPolicy) {
    }
    public List<Interval> union(List<Interval> intervals) {
        var sorted=intervals.stream().filter(i->i.from().isBefore(i.to())).sorted(Comparator.comparing(Interval::from)).toList();
        List<Interval> result=new ArrayList<>();
        for (Interval next:sorted) {
            if (result.isEmpty() || next.from().isAfter(result.getLast().to())) result.add(next);
            else {
                Interval last=result.removeLast();
                result.add(new Interval(last.from(),last.to().isAfter(next.to())?last.to():next.to()));
            }
        }
        return result;
    }
    public List<Interval> intersection(List<Interval> left,List<Interval> right) {
        List<Interval> result=new ArrayList<>();
        for (var a:left) for (var b:right) {
            Instant from=a.from().isAfter(b.from())?a.from():b.from();
            Instant to=a.to().isBefore(b.to())?a.to():b.to();
            if (from.isBefore(to)) result.add(new Interval(from,to));
        }
        return union(result);
    }
    public double seconds(List<Interval> intervals) {
        return union(intervals).stream().mapToDouble(i->Duration.between(i.from(),i.to()).toMillis()/1000d).sum();
    }
    public List<Interval> scheduled(List<OperatingSchedule> schedules,Instant from,Instant to) {
        var boundaries=new java.util.TreeSet<Instant>(List.of(from,to));
        for(var schedule:schedules) {
            if(schedule.effectiveFrom().isAfter(from) && schedule.effectiveFrom().isBefore(to)) boundaries.add(schedule.effectiveFrom());
            if(schedule.effectiveTo().isAfter(from) && schedule.effectiveTo().isBefore(to)) boundaries.add(schedule.effectiveTo());
        }
        var edges=List.copyOf(boundaries); List<Interval> combined=new ArrayList<>();
        for(int i=1;i<edges.size();i++) {
            Instant left=edges.get(i-1),right=edges.get(i);
            var latest=new java.util.HashMap<java.util.UUID,OperatingSchedule>();
            schedules.stream().filter(s->!s.effectiveFrom().isAfter(left) && !s.effectiveTo().isBefore(right))
                .forEach(s->latest.merge(s.scheduleId(),s,(a,b)->a.version()>b.version()?a:b));
            combined.addAll(scheduledPeriod(List.copyOf(latest.values()),left,right));
        }
        return union(combined);
    }
    private List<Interval> scheduledPeriod(List<OperatingSchedule> schedules,Instant from,Instant to) {
        List<Interval> result=new ArrayList<>();
        for (var schedule:schedules) {
            ZoneId zone=ZoneId.of(schedule.timezone());
            var date=from.atZone(zone).toLocalDate().minusDays(1);
            var last=to.atZone(zone).toLocalDate();
            for (;!date.isAfter(last);date=date.plusDays(1)) {
                if (schedule.holidays().contains(date)) continue;
                for (var slot:schedule.weekSlots()) if (slot.dayOfWeek()==date.getDayOfWeek().getValue()) {
                    Instant start=date.atTime(slot.from()).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
                    Instant end=date.atTime(slot.to()).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
                    start=List.of(start,from,schedule.effectiveFrom()).stream().max(Instant::compareTo).orElseThrow();
                    end=List.of(end,to,schedule.effectiveTo()).stream().min(Instant::compareTo).orElseThrow();
                    if (start.isBefore(end)) result.add(new Interval(start,end));
                }
            }
        }
        return union(result);
    }
    public Result calculate(List<OperationalFact> facts,List<OperatingSchedule> schedules,Instant from,Instant to) {
        List<Interval> window=List.of(new Interval(from,to));
        List<Interval> scheduled=scheduled(schedules,from,to);
        List<Interval> observed=intersection(intervals(facts,"OBSERVATION"),window);
        List<Interval> denominator=schedules.isEmpty()?observed:intersection(observed,scheduled);
        double duration=seconds(denominator),expected=schedules.isEmpty()?seconds(window):seconds(scheduled);
        double down=seconds(intersection(intervals(facts,"DOWNTIME"),denominator));
        double planned=seconds(intersection(intervals(facts,"PLANNED_DOWNTIME"),denominator));
        return new Result(seconds(scheduled),duration,down,planned,duration==0?null:(duration-down)/duration,
                duration==0?"UNKNOWN":duration<expected?"PARTIAL":"COMPLETE","GAP_SHIFT_FORWARD_OVERLAP_EARLIER_OFFSET_HALF_OPEN");
    }
    private List<Interval> intervals(List<OperationalFact> facts,String kind) {
        return union(facts.stream().filter(f->kind.equals(f.kind()) && "COMPLETED".equals(f.completeness()) && f.endAt()!=null)
                .map(f->new Interval(f.startAt(),f.endAt())).toList());
    }
}
