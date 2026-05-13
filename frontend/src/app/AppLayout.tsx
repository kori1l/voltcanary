import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearTokens } from "../api/client";

const navigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/devices", label: "Devices" },
  { to: "/alerts", label: "Alerts" },
];

export function AppLayout() {
  const navigate = useNavigate();

  function logout() {
    clearTokens();
    navigate("/login", { replace: true });
  }

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
        <button className="ghost-button sidebar-logout" type="button" onClick={logout}>
          Logout
        </button>
      </aside>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
