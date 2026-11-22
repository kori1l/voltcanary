import { formatEnum, locationLabel } from "./deviceInventoryPresentation";
import { DeviceWizardBasics } from "./DeviceWizardBasics";
import { Link } from "react-router-dom";
import { Dialog, DialogClose } from "../components/Dialog";
import { EntitySelector } from "../components/EntitySelector";
import { SectionFeedback } from "../components/SectionFeedback";
import { SimulatorStatusIcon } from "../simulation/SimulatorStatusIcon";
import { DeviceActivationSummary, activationRoute } from "./DeviceActivationSummary";
import { DeviceSetupReconciliation } from "./DeviceSetupReconciliation";

import { useDeviceCreation, type DeviceCreationProps } from "./useDeviceCreation";

export function DeviceCreationWizard(props: DeviceCreationProps) {
  const {
    confirmation,
    navigate,
    isSubmitting,
    completedSetup,
    selectedLocationId,
    setSelectedLocationId,
    newLocationName,
    setNewLocationName,
    newLocationParentId,
    setNewLocationParentId,
    newLocationBuilding,
    setNewLocationBuilding,
    newLocationFloor,
    setNewLocationFloor,
    newLocationRoom,
    setNewLocationRoom,
    newLocationZone,
    setNewLocationZone,
    newLocationTags,
    setNewLocationTags,
    name,
    setName,
    type,
    setType,
    manufacturer,
    setManufacturer,
    model,
    setModel,
    metadataText,
    setMetadataText,
    sourceId,
    setSourceId,
    sourceMode,
    setSourceMode,
    demoProfile,
    setDemoProfile,
    demoPreset,
    setDemoPreset,
    startDemoImmediately,
    setStartDemoImmediately,
    wizardStep,
    setWizardStep,
    setupProgress,
    setSetupRevision,
    createdLocation,
    setupError,
    setSetupError,
    locationMode,
    setLocationMode,
    isCreateOpen,
    setIsCreateOpen,
    presetResource,
    locationById,
    typeByCode,
    demoPresetOptions,
    selectedPreset,
    sourceIdPreview,
    canAdvanceWizard,
    saveSetupCheckpoint,
    recovery,
    restoringSetup,
    restoreFailed,
    openCreateWizard,
    resetCreateForm,
    onSubmit,
    wizardSteps,
  } = useDeviceCreation(props);
  const { referencesReady, deviceTypes, locations, locationOptions } = props;
  return (
    <>
      {confirmation.dialog}
      <button
        className="primary-button"
        type="button"
        disabled={!referencesReady || restoringSetup}
        onClick={openCreateWizard}
      >
        Add device
      </button>
      {isCreateOpen ? (
        <Dialog
          key={completedSetup ? "complete" : "draft"}
          title={completedSetup ? "Device registered" : "Add device"}
          onClose={() => {
            setIsCreateOpen(false);
            if (
              completedSetup ||
              (!setupProgress.current.device &&
                !setupProgress.current.unknownStage &&
                !restoreFailed)
            )
              resetCreateForm();
          }}
          guardChanges={!completedSetup}
          busy={isSubmitting}
          footer={
            <>
              <DialogClose />
              {!completedSetup && wizardStep > 0 ? (
                <button
                  className="ghost-button"
                  type="button"
                  onClick={() => setWizardStep((current) => Math.max(current - 1, 0))}
                  disabled={
                    isSubmitting ||
                    Boolean(setupProgress.current.unknownStage) ||
                    (Boolean(setupProgress.current.device) && wizardStep <= 2)
                  }
                >
                  Back
                </button>
              ) : null}
              {completedSetup ? (
                <button
                  className="primary-button"
                  type="button"
                  onClick={() => {
                    const destination = activationRoute(
                      completedSetup.device.id,
                      completedSetup.dataSource.id,
                    );
                    setIsCreateOpen(false);
                    resetCreateForm();
                    navigate(destination);
                  }}
                >
                  Done · open activation
                </button>
              ) : (
                <button
                  className="primary-button"
                  type="submit"
                  form="create-device-form"
                  disabled={
                    isSubmitting ||
                    restoringSetup ||
                    restoreFailed ||
                    Boolean(setupProgress.current.unknownStage) ||
                    !canAdvanceWizard
                  }
                >
                  {isSubmitting
                    ? "Adding..."
                    : setupProgress.current.device
                      ? "Resume setup"
                      : wizardStep === wizardSteps.length - 1
                        ? "Add device"
                        : "Next"}
                </button>
              )}
            </>
          }
        >
          {completedSetup ? (
            <DeviceActivationSummary
              device={completedSetup.device}
              source={completedSetup.dataSource}
              run={completedSetup.simulationRun}
            />
          ) : (
            <form
              data-draft-id="create-device-form"
              id="create-device-form"
              className="device-wizard form-stack"
              onSubmit={onSubmit}
            >
              {restoreFailed ? (
                <button type="button" className="ghost-button" onClick={recovery.retry}>
                  Retry setup recovery reads
                </button>
              ) : null}
              {restoringSetup ? <p role="status">Restoring authorized setup references…</p> : null}
              {setupProgress.current.unknownStage ? (
                <DeviceSetupReconciliation
                  progress={setupProgress.current}
                  externalId={sourceId}
                  onReviewed={() => {
                    saveSetupCheckpoint();
                    setSetupError(null);
                    setWizardStep(3);
                    setSetupRevision((revision) => revision + 1);
                  }}
                  onDevice={(device) => {
                    setName(device.name);
                    setType(device.type);
                    setSelectedLocationId(device.locationId ?? "");
                  }}
                  onLocation={(id) => {
                    createdLocation.current = id;
                    setSelectedLocationId(id);
                    setLocationMode("existing");
                  }}
                />
              ) : null}
              {recovery.error ? (
                <div className="error-message" role="alert">
                  {recovery.error}
                </div>
              ) : null}
              {setupError ? (
                <div className="error-message" role="alert">
                  {setupError}
                </div>
              ) : null}
              {setupProgress.current.device ? (
                <div className="status-note">
                  Device {setupProgress.current.device.name} has been created. Retry continues its
                  setup.{" "}
                  <Link to={`/devices/${setupProgress.current.device.id}`}>
                    Open created device
                  </Link>
                  <details>
                    <summary>Completed steps</summary>
                    <p>Device: {setupProgress.current.device.id}</p>
                    {setupProgress.current.dataSource ? (
                      <p>Source: {setupProgress.current.dataSource.id}</p>
                    ) : null}
                    {setupProgress.current.simulationConfig ? (
                      <p>Simulator configuration saved</p>
                    ) : null}
                  </details>
                </div>
              ) : null}
              <div className="wizard-steps" aria-label="Add device steps">
                {wizardSteps.map((step, index) => (
                  <button
                    className={
                      index === wizardStep
                        ? "wizard-step wizard-step-active"
                        : index < wizardStep
                          ? "wizard-step wizard-step-done"
                          : "wizard-step"
                    }
                    type="button"
                    key={step}
                    onClick={() => setWizardStep(index)}
                    aria-current={index === wizardStep ? "step" : undefined}
                    disabled={
                      isSubmitting ||
                      Boolean(setupProgress.current.unknownStage) ||
                      (Boolean(setupProgress.current.device) && index < 2) ||
                      index > wizardStep
                    }
                  >
                    <span>{index + 1}</span>
                    {step}
                  </button>
                ))}
              </div>

              {wizardStep === 0 ? (
                <DeviceWizardBasics
                  name={name}
                  type={type}
                  manufacturer={manufacturer}
                  model={model}
                  deviceTypes={deviceTypes}
                  setName={setName}
                  setType={(value) => {
                    setType(value);
                    setDemoProfile(value === "VEHICLE" ? "VEHICLE_GPS" : "FRIDGE");
                    setDemoPreset(value === "VEHICLE" ? "CITY_LOOP" : "NORMAL_OPERATION");
                  }}
                  setManufacturer={setManufacturer}
                  setModel={setModel}
                />
              ) : null}

              {wizardStep === 1 ? (
                <div className="wizard-panel">
                  <label>
                    Location choice
                    <select
                      value={locationMode}
                      onChange={(event) => setLocationMode(event.target.value)}
                    >
                      <option value="existing">Use existing location</option>
                      <option value="new">Create new location</option>
                    </select>
                  </label>
                  {locationMode === "existing" ? (
                    <>
                      <EntitySelector
                        loadOptions={locationOptions}
                        label="Existing location"
                        options={locations.map((location) => ({
                          id: location.id,
                          name: locationLabel(location),
                        }))}
                        value={selectedLocationId}
                        onChange={setSelectedLocationId}
                      />
                    </>
                  ) : (
                    <>
                      <label>
                        New location
                        <input
                          value={newLocationName}
                          onChange={(event) => setNewLocationName(event.target.value)}
                          placeholder="Kitchen"
                        />
                      </label>
                      {newLocationName.trim() ? (
                        <details>
                          <summary>Additional location details</summary>
                          <label>
                            Parent location
                            <select
                              value={newLocationParentId}
                              onChange={(event) => setNewLocationParentId(event.target.value)}
                            >
                              <option value="">No parent</option>
                              {locations.map((location) => (
                                <option value={location.id} key={location.id}>
                                  {locationLabel(location)}
                                </option>
                              ))}
                            </select>
                          </label>
                          <div className="form-grid-two">
                            <label>
                              Building
                              <input
                                value={newLocationBuilding}
                                onChange={(event) => setNewLocationBuilding(event.target.value)}
                                placeholder="HQ"
                              />
                            </label>
                            <label>
                              Floor
                              <input
                                value={newLocationFloor}
                                onChange={(event) => setNewLocationFloor(event.target.value)}
                                placeholder="2"
                              />
                            </label>
                            <label>
                              Room
                              <input
                                value={newLocationRoom}
                                onChange={(event) => setNewLocationRoom(event.target.value)}
                                placeholder="204"
                              />
                            </label>
                            <label>
                              Zone
                              <input
                                value={newLocationZone}
                                onChange={(event) => setNewLocationZone(event.target.value)}
                                placeholder="north"
                              />
                            </label>
                          </div>
                          <label>
                            Tags
                            <input
                              value={newLocationTags}
                              onChange={(event) => setNewLocationTags(event.target.value)}
                              placeholder="critical, it"
                            />
                          </label>
                        </details>
                      ) : null}
                    </>
                  )}
                </div>
              ) : null}

              {wizardStep === 2 ? (
                <div className="wizard-panel">
                  <div className="source-choice-grid">
                    <button
                      className={
                        sourceMode === "API"
                          ? "source-choice source-choice-active"
                          : "source-choice"
                      }
                      type="button"
                      disabled={Boolean(setupProgress.current.dataSource)}
                      onClick={() => setSourceMode("API")}
                    >
                      <strong>Smart plug/API</strong>
                      <span>REST telemetry source for meters, plugs, and integrations.</span>
                    </button>
                    <button
                      className={
                        sourceMode === "MQTT"
                          ? "source-choice source-choice-active"
                          : "source-choice"
                      }
                      type="button"
                      disabled={Boolean(setupProgress.current.dataSource)}
                      onClick={() => setSourceMode("MQTT")}
                    >
                      <strong>MQTT sensor</strong>
                      <span>MQTT telemetry source for gateway and sensor feeds.</span>
                    </button>
                    <button
                      className={
                        sourceMode === "SIMULATOR"
                          ? "source-choice source-choice-active"
                          : "source-choice"
                      }
                      type="button"
                      disabled={Boolean(setupProgress.current.dataSource)}
                      onClick={() => setSourceMode("SIMULATOR")}
                    >
                      <strong>
                        Simulator/demo source <SimulatorStatusIcon resource={presetResource} />
                      </strong>
                      <span>Creates simulator telemetry without editing YAML first.</span>
                    </button>
                  </div>

                  {sourceMode === "SIMULATOR" ? (
                    <div className="demo-source-panel">
                      <SectionFeedback
                        resource={presetResource}
                        label="Simulation presets"
                        onRetry={() => void presetResource.retry()}
                      />
                      <div className="form-grid-two">
                        <label>
                          Demo profile
                          <select
                            disabled={Boolean(setupProgress.current.simulationConfig)}
                            value={demoProfile}
                            onChange={(event) => setDemoProfile(event.target.value)}
                          >
                            {type === "VEHICLE" ? (
                              <option value="VEHICLE_GPS">Vehicle GPS</option>
                            ) : (
                              <>
                                <option value="FRIDGE">Fridge</option>
                                <option value="GENERIC_APPLIANCE">Generic appliance</option>
                              </>
                            )}
                          </select>
                        </label>
                        <label>
                          Preset
                          <select
                            disabled={Boolean(setupProgress.current.simulationConfig)}
                            value={demoPreset}
                            onChange={(event) => setDemoPreset(event.target.value)}
                          >
                            {demoPresetOptions.map((preset) => (
                              <option value={preset.value} key={preset.value}>
                                {preset.label}
                              </option>
                            ))}
                          </select>
                        </label>
                      </div>
                      <div className="status-note">
                        {selectedPreset.detail}{" "}
                        {type === "VEHICLE"
                          ? "GPS coordinates, speed and course · City loop · 5 seconds · 30 minutes · 36 km/h. Adjust the saved configuration in Demo Tools."
                          : "Expected metrics include active power, voltage, current, and accumulated energy."}
                      </div>
                      <label className="checkbox-row">
                        <input
                          type="checkbox"
                          checked={startDemoImmediately}
                          onChange={(event) => setStartDemoImmediately(event.target.checked)}
                        />
                        Start telemetry after creation
                      </label>
                    </div>
                  ) : null}

                  <details className="assistant-advanced">
                    <summary>Advanced</summary>
                    <label>
                      Telemetry source ID
                      <input
                        disabled={Boolean(setupProgress.current.dataSource)}
                        value={sourceId}
                        onChange={(event) => setSourceId(event.target.value)}
                        placeholder="Auto-generated"
                      />
                    </label>
                    <label>
                      Metadata JSON
                      <textarea
                        disabled={Boolean(setupProgress.current.device)}
                        className="small-textarea"
                        value={metadataText}
                        onChange={(event) => setMetadataText(event.target.value)}
                      />
                    </label>
                  </details>
                </div>
              ) : null}

              {wizardStep === 3 ? (
                <div className="wizard-panel wizard-review">
                  <dl>
                    <div>
                      <dt>Device</dt>
                      <dd>
                        {name || "Unnamed device"} - {typeByCode.get(type) ?? formatEnum(type)}
                      </dd>
                    </div>
                    <div>
                      <dt>Location</dt>
                      <dd>
                        {locationMode === "new"
                          ? newLocationName.trim()
                          : locationById.get(selectedLocationId) || "No location selected"}
                      </dd>
                    </div>
                    <div>
                      <dt>Telemetry</dt>
                      <dd>
                        {sourceMode === "SIMULATOR"
                          ? `${selectedPreset.label} demo source`
                          : sourceMode === "MQTT"
                            ? "MQTT sensor"
                            : "Smart plug/API"}
                      </dd>
                    </div>
                    <div>
                      <dt>Source ID</dt>
                      <dd>{sourceIdPreview}</dd>
                    </div>
                  </dl>
                </div>
              ) : null}
            </form>
          )}
        </Dialog>
      ) : null}
    </>
  );
}
