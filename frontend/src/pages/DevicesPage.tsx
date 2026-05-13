import { Link } from "react-router-dom";

export function DevicesPage() {
  return (
    <>
      <header className="page-header">
        <h1>Devices</h1>
        <p>Device inventory and status overview.</p>
      </header>
      <section className="placeholder-panel">
        <Link to="/devices/demo-device">Open demo device</Link>
      </section>
    </>
  );
}
