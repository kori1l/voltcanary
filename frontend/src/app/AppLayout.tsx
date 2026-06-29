import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearTokens, getRefreshToken } from "../api/client";
import { logout as logoutRequest, me, MeResponse } from "../auth/authApi";
import { AssistantDrawer } from "../aiAgent/AssistantDrawer";
import { BrandLogo } from "../components/BrandLogo";

const userNavigation = [
  { to: "/devices", label: "Devices" },
  { to: "/alerts", label: "Alerts" },
  { to: "/settings", label: "Settings" },
];

const adminNavigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/devices", label: "Devices" },
  { to: "/alerts", label: "Alerts" },
  { to: "/ai-agent", label: "AI Agent" },
  { to: "/ai-agent/settings", label: "AI Settings" },
  { to: "/account", label: "Admin" },
  { to: "/settings", label: "Settings" },
];

export function AppLayout() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<MeResponse | null>(null);

  useEffect(() => {
    let mounted = true;
    me()
      .then((currentProfile) => {
        if (mounted) {
          setProfile(currentProfile);
        }
      })
      .catch(() => {
        if (mounted) {
          setProfile(null);
        }
      });

    return () => {
      mounted = false;
    };
  }, []);

  const navigation = useMemo(
    () => (profile?.role === "ADMIN" ? adminNavigation : userNavigation),
    [profile?.role],
  );

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
    setProfile(null);
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
      <AssistantDrawer />
    </div>
  );
}
