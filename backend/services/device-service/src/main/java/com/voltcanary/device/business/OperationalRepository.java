package com.voltcanary.device.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.OperationalFact;
import com.voltcanary.common.web.OperatingSchedule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationalRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OperationalRepository(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;
        this.mapper=mapper;
    }
    public void lock(UUID account) {
        jdbc.query("SELECT id FROM accounts WHERE id=? FOR UPDATE",(r,i)->r.getObject("id",UUID.class),account);
    }
    public boolean sourceOwned(UUID device,UUID source) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM data_sources WHERE device_id=? AND id=?)",Boolean.class,device,source));
    }
    public List<OperationalFact> facts(UUID account,UUID device,Instant from,Instant to) {
        return jdbc.query("SELECT f.* FROM operational_fact_revisions f WHERE account_id=? AND device_id=? AND start_at<? AND (end_at IS NULL OR end_at>?) AND version=(SELECT max(version) FROM operational_fact_revisions v WHERE v.event_id=f.event_id) ORDER BY start_at,event_id LIMIT 501",(r,i)->new OperationalFact(r.getObject("event_id",UUID.class),r.getInt("version"),device,r.getObject("source_id",UUID.class),r.getString("source_event_id"),r.getString("kind"),r.getString("completeness"),r.getTimestamp("start_at").toInstant(),r.getTimestamp("end_at")==null?null:r.getTimestamp("end_at").toInstant(),r.getString("reason"),r.getString("evidence")),account,device,Timestamp.from(to),Timestamp.from(from));
    }
    public OperationalFact bySource(UUID account,UUID source,String event) {
        var results=jdbc.query("SELECT * FROM operational_fact_revisions WHERE account_id=? AND source_id=? AND source_event_id=? ORDER BY version DESC LIMIT 1",(r,i)->new OperationalFact(r.getObject("event_id",UUID.class),r.getInt("version"),r.getObject("device_id",UUID.class),source,event,r.getString("kind"),r.getString("completeness"),r.getTimestamp("start_at").toInstant(),r.getTimestamp("end_at")==null?null:r.getTimestamp("end_at").toInstant(),r.getString("reason"),r.getString("evidence")),account,source,event);
        return results.isEmpty()?null:results.getFirst();
    }
    public void append(UUID account,UUID actor,OperationalFact fact) {
        jdbc.update("INSERT INTO operational_fact_revisions(event_id,version,account_id,device_id,source_id,source_event_id,kind,completeness,start_at,end_at,reason,evidence,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",fact.eventId(),fact.version(),account,fact.deviceId(),fact.sourceId(),fact.sourceEventId(),fact.kind(),fact.completeness(),Timestamp.from(fact.startAt()),fact.endAt()==null?null:Timestamp.from(fact.endAt()),fact.reason(),fact.evidence(),actor);
    }
    public List<OperatingSchedule> schedules(UUID account,UUID device) {
        return jdbc.query("SELECT * FROM operating_schedule_revisions WHERE account_id=? AND device_id=? ORDER BY schedule_id,version LIMIT 101",(r,i)->new OperatingSchedule(r.getObject("schedule_id",UUID.class),r.getInt("version"),device,r.getString("timezone"),r.getTimestamp("effective_from").toInstant(),r.getTimestamp("effective_to").toInstant(),decode(r.getString("week_slots"),OperatingSchedule.Slot.class),decode(r.getString("holidays"),java.time.LocalDate.class),r.getString("reason")),account,device);
    }
    public void append(UUID account,UUID actor,OperatingSchedule schedule) {
        jdbc.update("INSERT INTO operating_schedule_revisions(schedule_id,version,account_id,device_id,timezone,effective_from,effective_to,week_slots,holidays,reason,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",schedule.scheduleId(),schedule.version(),account,schedule.deviceId(),schedule.timezone(),Timestamp.from(schedule.effectiveFrom()),Timestamp.from(schedule.effectiveTo()),encode(schedule.weekSlots()),encode(schedule.holidays()),schedule.reason(),actor);
    }
    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException bad) {
            throw new IllegalArgumentException("Invalid operational fact",bad);
        }
    }
    private <T> List<T> decode(String json,Class<T> type) {
        try {
            return mapper.readerForListOf(type).readValue(json);
        } catch (java.io.IOException bad) {
            throw new IllegalArgumentException("Invalid stored operating schedule",bad);
        }
    }
}
