import { DeviceType } from "./devicesApi";
export function DeviceWizardBasics({ name, type, manufacturer, model, deviceTypes, setName, setType, setManufacturer, setModel }: { name: string; type: string; manufacturer: string; model: string; deviceTypes: DeviceType[]; setName: (value: string) => void; setType: (value: string) => void; setManufacturer: (value: string) => void; setModel: (value: string) => void }) {
  return (<div className="wizard-panel">
                  <div className="form-grid-two">
                    <label>
                      Name
                      <input value={name} onChange={(event) => setName(event.target.value)} required placeholder="Kitchen fridge" />
                    </label>
                    <label>
                      Device type
                      <select value={type} onChange={(event) => setType(event.target.value)}>
                        {deviceTypes.map((deviceType) => (
                          <option value={deviceType.code} key={deviceType.code}>
                            {deviceType.displayName}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Manufacturer
                      <input value={manufacturer} onChange={(event) => setManufacturer(event.target.value)} placeholder="Bosch" />
                    </label>
                    <label>
                      Model
                      <input value={model} onChange={(event) => setModel(event.target.value)} placeholder="Unknown" />
                    </label>
                  </div>
                </div>);
}
