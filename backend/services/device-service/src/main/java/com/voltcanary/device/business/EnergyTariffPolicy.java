package com.voltcanary.device.business;

import com.voltcanary.common.web.EnergyTariff;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnergyTariffPolicy {
    private final EnergyTariffRepository tariffs;
    private final BusinessResourceAuthorization access;
    private final DeviceAuditService audit;
    public EnergyTariffPolicy(EnergyTariffRepository tariffs,BusinessResourceAuthorization access,DeviceAuditService audit) {
        this.tariffs=tariffs;
        this.access=access;
        this.audit=audit;
    }
    public List<EnergyTariff> list(AuthenticatedUser actor,UUID accountId,UUID workspaceId) {
        UUID account=access.account(actor,accountId,workspaceId,"tariff.read");
        return tariffs.list(account,workspaceId);
    }
    @Transactional
    public EnergyTariff save(AuthenticatedUser actor,EnergyTariff request,boolean reviewed) {
        if (request==null || !reviewed || request.ratePerKWh()==null || request.ratePerKWh().signum()<0
                || request.ratePerKWh().precision()>18 || request.ratePerKWh().scale()>8 || request.version()<0
                || request.effectiveFrom()==null || request.effectiveTo()==null || !request.effectiveFrom().isBefore(request.effectiveTo())
                || request.reason()==null || request.reason().isBlank() || request.reason().length()>500) throw invalid();
        try {
            Currency.getInstance(request.currency());
            ZoneId.of(request.timezone());
        } catch (Exception bad) {
            throw invalid();
        }
        UUID account=access.account(actor,request.accountId(),request.workspaceId(),"tariff.write");
        tariffs.lock(account);
        var history=tariffs.list(account,request.workspaceId());
        UUID id=request.tariffId()==null?UUID.randomUUID():request.tariffId();
        var previous=history.stream().filter(t->t.tariffId().equals(id)).max(java.util.Comparator.comparingInt(EnergyTariff::version)).orElse(null);
        if (request.version()!=(previous==null?0:previous.version()) || previous!=null && previous.archived()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Tariff revision changed or archived");
        if (history.size()>=1000) throw invalid();
        var current=new java.util.HashMap<UUID,EnergyTariff>();
        history.forEach(t->current.put(t.tariffId(),t));
        for (var tariff:current.values()) if (!tariff.tariffId().equals(id) && !tariff.archived()
                && request.effectiveFrom().isBefore(tariff.effectiveTo()) && tariff.effectiveFrom().isBefore(request.effectiveTo())) throw invalid();
        EnergyTariff saved=new EnergyTariff(id,request.version()+1,account,request.workspaceId(),request.currency(),request.timezone(),request.ratePerKWh(),request.effectiveFrom(),request.effectiveTo(),request.taxesIncluded(),request.reason(),false);
        tariffs.append(saved,actor.id());
        audit.record(actor,account,"TARIFF_REVISION","tariff",id);
        return saved;
    }
    @Transactional
    public void archive(AuthenticatedUser actor,UUID account,UUID workspace,UUID id) {
        account=access.account(actor,account,workspace,"tariff.write");
        tariffs.lock(account);
        if (tariffs.list(account,workspace).stream().noneMatch(t->t.tariffId().equals(id))) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tariff unavailable");
        tariffs.archive(id,actor.id());
        audit.record(actor,account,"TARIFF_ARCHIVED","tariff",id);
    }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Review a valid non-overlapping flat tariff interval, decimal rate, currency and timezone");
    }
}
