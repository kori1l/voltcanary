import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearTokens, getRefreshToken } from "../api/client";
import { logout as logoutRequest } from "../auth/authApi";
import { BrandLogo } from "../components/BrandLogo";

const navigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/devices", label: "Devices" },
  { to: "/alerts", label: "Alerts" },
  { to: "/ai-agent", label: "AI Agent" },
  { to: "/account", label: "Account" },
];

export function AppLayout() {
  const navigate = useNavigate();

  async function logout() {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await logoutRequest(refreshToken);
      } catch {
        // Local logout should still complete if the token was already invalid.
      }
    }
    clearTokens();
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <BrandLogo />
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
