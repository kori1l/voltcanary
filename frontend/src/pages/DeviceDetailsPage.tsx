import { useParams } from "react-router-dom";

export function DeviceDetailsPage() {
  const { deviceId } = useParams();

  return (
    <>
      <header className="page-header">
        <h1>Device Details</h1>
        <p>{deviceId}</p>
      </header>
      <section className="placeholder-panel">Telemetry and analytics panels are not implemented yet.</section>
    </>
  );
}
