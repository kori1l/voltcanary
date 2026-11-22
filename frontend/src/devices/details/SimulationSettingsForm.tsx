import { DurationInput } from "./DurationInput";
import { SimulationSettings } from "./simulationYaml";

export function SimulationSettingsForm({
  settings,
  profile,
  onChange,
}: {
  settings: SimulationSettings;
  profile: string;
  onChange: (field: keyof SimulationSettings, value: string | boolean) => void;
}) {
  return (
    <section className="sim-config-form" aria-label="Simulation settings">
      <div className="sim-form-section">
        <h3>Device signal</h3>
        <div className="sim-form-grid">
          <label>
            Simulator name
            <input
              value={settings.name}
              onChange={(event) => onChange("name", event.target.value)}
            />
          </label>
          <label>
            Source ID
            <input
              value={settings.sourceId}
              onChange={(event) => onChange("sourceId", event.target.value)}
            />
          </label>
          <label>
            Interval
            <DurationInput
              value={settings.interval}
              onChange={(value) => onChange("interval", value)}
            />
          </label>
          <label>
            Duration
            <DurationInput
              value={settings.duration}
              onChange={(value) => onChange("duration", value)}
            />
          </label>
          <label>
            Speed
            <input
              disabled={profile === "VEHICLE_GPS"}
              type="number"
              step="0.1"
              value={settings.speed}
              onChange={(event) => onChange("speed", event.target.value)}
            />
          </label>
        </div>
      </div>
      {profile === "VEHICLE_GPS" ? (
        <div className="sim-form-section">
          <h3>GPS movement</h3>
          <p>City loop · Belgrade · straight segments</p>
          <label>
            Physical speed (km/h)
            <input
              type="number"
              min="1"
              max="120"
              value={settings.gpsSpeedKmh}
              onChange={(event) => onChange("gpsSpeedKmh", event.target.value)}
            />
          </label>
        </div>
      ) : null}
      <div hidden={profile === "VEHICLE_GPS"} className="sim-form-section">
        <h3>Normal behavior</h3>
        <div className="sim-form-grid">
          <label>
            Voltage
            <input
              type="number"
              step="0.1"
              value={settings.voltageV}
              onChange={(event) => onChange("voltageV", event.target.value)}
            />
          </label>
          <label>
            Signal RSSI
            <input
              type="number"
              step="1"
              value={settings.rssi}
              onChange={(event) => onChange("rssi", event.target.value)}
            />
          </label>
          <label>
            Idle power
            <input
              type="number"
              step="0.1"
              value={settings.idlePowerW}
              onChange={(event) => onChange("idlePowerW", event.target.value)}
            />
          </label>
          <label>
            Active power
            <input
              type="number"
              step="0.1"
              value={settings.activePowerW}
              onChange={(event) => onChange("activePowerW", event.target.value)}
            />
          </label>
          <label>
            Active noise
            <input
              type="number"
              step="0.1"
              value={settings.activeNoiseW}
              onChange={(event) => onChange("activeNoiseW", event.target.value)}
            />
          </label>
        </div>
      </div>
      <div hidden={profile === "VEHICLE_GPS"} className="sim-form-section">
        <h3>Cycle timing</h3>
        <div className="sim-form-grid">
          <label>
            On min
            <DurationInput value={settings.onMin} onChange={(value) => onChange("onMin", value)} />
          </label>
          <label>
            On max
            <DurationInput value={settings.onMax} onChange={(value) => onChange("onMax", value)} />
          </label>
          <label>
            Off min
            <DurationInput
              value={settings.offMin}
              onChange={(value) => onChange("offMin", value)}
            />
          </label>
          <label>
            Off max
            <DurationInput
              value={settings.offMax}
              onChange={(value) => onChange("offMax", value)}
            />
          </label>
        </div>
      </div>
      <div hidden={profile === "VEHICLE_GPS"} className="sim-form-section sim-anomaly-section">
        <h3>Anomalies</h3>
        <div className="sim-anomaly-grid">
          <article>
            <label className="sim-toggle">
              <input
                type="checkbox"
                checked={settings.powerSpikeEnabled}
                onChange={(event) => onChange("powerSpikeEnabled", event.target.checked)}
              />
              Power spike
            </label>
            <label>
              Start after
              <DurationInput
                value={settings.powerSpikeAfter}
                onChange={(value) => onChange("powerSpikeAfter", value)}
              />
            </label>
            <label>
              Duration
              <DurationInput
                value={settings.powerSpikeDuration}
                onChange={(value) => onChange("powerSpikeDuration", value)}
              />
            </label>
            <label>
              Spike power
              <input
                type="number"
                value={settings.powerSpikeWatts}
                onChange={(event) => onChange("powerSpikeWatts", event.target.value)}
              />
            </label>
          </article>
          <article>
            <label className="sim-toggle">
              <input
                type="checkbox"
                checked={settings.offlineEnabled}
                onChange={(event) => onChange("offlineEnabled", event.target.checked)}
              />
              Offline window
            </label>
            <label>
              Start after
              <DurationInput
                value={settings.offlineAfter}
                onChange={(value) => onChange("offlineAfter", value)}
              />
            </label>
            <label>
              Duration
              <DurationInput
                value={settings.offlineDuration}
                onChange={(value) => onChange("offlineDuration", value)}
              />
            </label>
          </article>
          <article>
            <label className="sim-toggle">
              <input
                type="checkbox"
                checked={settings.growthEnabled}
                onChange={(event) => onChange("growthEnabled", event.target.checked)}
              />
              Consumption growth
            </label>
            <label>
              Start after
              <DurationInput
                value={settings.growthAfter}
                onChange={(value) => onChange("growthAfter", value)}
              />
            </label>
            <label>
              Growth percent
              <input
                type="number"
                value={settings.growthPercent}
                onChange={(event) => onChange("growthPercent", event.target.value)}
              />
            </label>
            <label>
              Ramp duration
              <DurationInput
                value={settings.growthRamp}
                onChange={(value) => onChange("growthRamp", value)}
              />
            </label>
          </article>
        </div>
      </div>
    </section>
  );
}
