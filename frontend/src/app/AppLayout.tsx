import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { clearTokens, getRefreshToken } from "../api/client";
import { logout as logoutRequest, me, MeResponse } from "../auth/authApi";
import { AssistantDrawer } from "../aiAgent/AssistantDrawer";
import { BrandLogo } from "../components/BrandLogo";

const userNavigation = [
  { to: "/devices", label: "Devices" },
  { to: "/maintenance", label: "Maintenance" },
  { to: "/alerts", label: "Alerts" },
  { to: "/settings", label: "Settings" },
  { to: "/settings/ml", label: "ML Settings" },
];

const adminNavigation = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/devices", label: "Devices" },
  { to: "/maintenance", label: "Maintenance" },
  { to: "/alerts", label: "Alerts" },
  { to: "/ai-agent", label: "AI Agent" },
  { to: "/ai-agent/settings", label: "AI Settings" },
  { to: "/account", label: "Admin" },
  { to: "/settings", label: "Settings" },
  { to: "/settings/ml", label: "ML Settings" },
];

export function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [navigationOpen, setNavigationOpen] = useState(false);
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

  useEffect(() => {
    setNavigationOpen(false);
    window.scrollTo({ top: 0, left: 0 });
  }, [location.pathname]);

  const activeDestination = navigation
    .filter(({ to }) => location.pathname === to || location.pathname.startsWith(`${to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0]?.to;

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
      <a className="skip-link" href="#console-main">Skip to content</a>
      <aside className="sidebar">
        <div className="sidebar-heading">
          <BrandLogo />
          <button className="ghost-button navigation-toggle" type="button" aria-expanded={navigationOpen} aria-controls="console-navigation" onClick={() => setNavigationOpen((open) => !open)}>
            {navigationOpen ? "Close menu" : "Menu"}
          </button>
        </div>
        <nav id="console-navigation" aria-label="Main navigation" className={`nav${navigationOpen ? " nav-open" : ""}`}>
          {navigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to !== activeDestination}
              className={item.to === activeDestination ? "nav-link active" : "nav-link"}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <button className="ghost-button sidebar-logout" type="button" onClick={logout}>
          Logout
        </button>
      </aside>
      <main id="console-main" className="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <AssistantDrawer />
    </div>
  );
}
