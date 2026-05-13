import { NavLink, Outlet } from "react-router-dom";

const navigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/devices", label: "Devices" },
  { to: "/alerts", label: "Alerts" },
];

export function AppLayout() {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">VoltCanary</div>
        <nav className="nav">
          {navigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
