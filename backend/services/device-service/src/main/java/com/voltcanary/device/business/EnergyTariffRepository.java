package com.voltcanary.device.business;

import com.voltcanary.common.web.EnergyTariff;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EnergyTariffRepository {
    private final JdbcTemplate jdbc;
    public EnergyTariffRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }
    public void lock(UUID account) {
        jdbc.query("SELECT id FROM accounts WHERE id=? FOR UPDATE",(r,i)->r.getObject("id",UUID.class),account);
    }
    public List<EnergyTariff> list(UUID account,UUID workspace) {
        return jdbc.query("SELECT r.*, a.archived_at, a.tariff_id IS NOT NULL AS archived FROM energy_tariff_revisions r LEFT JOIN energy_tariff_archive a ON a.tariff_id=r.tariff_id WHERE account_id=? AND workspace_id IS NOT DISTINCT FROM ? ORDER BY tariff_id,version LIMIT 1000",(r,i)->new EnergyTariff(r.getObject("tariff_id",UUID.class),r.getInt("version"),account,workspace,r.getString("currency"),r.getString("timezone"),r.getBigDecimal("rate_per_kwh"),r.getTimestamp("effective_from").toInstant(),r.getTimestamp("effective_to").toInstant(),r.getBoolean("taxes_included"),r.getString("reason"),r.getBoolean("archived"),r.getTimestamp("archived_at")==null?null:r.getTimestamp("archived_at").toInstant()),account,workspace);
    }
    public void append(EnergyTariff tariff,UUID actor) {
        jdbc.update("INSERT INTO energy_tariff_revisions(tariff_id,version,account_id,workspace_id,currency,timezone,rate_per_kwh,effective_from,effective_to,taxes_included,reason,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                tariff.tariffId(),tariff.version(),tariff.accountId(),tariff.workspaceId(),tariff.currency(),tariff.timezone(),tariff.ratePerKWh(),Timestamp.from(tariff.effectiveFrom()),Timestamp.from(tariff.effectiveTo()),tariff.taxesIncluded(),tariff.reason(),actor);
    }
    public void archive(UUID id,UUID actor) {
        jdbc.update("INSERT INTO energy_tariff_archive(tariff_id,archived_by) VALUES (?,?) ON CONFLICT DO NOTHING",id,actor);
    }
}
