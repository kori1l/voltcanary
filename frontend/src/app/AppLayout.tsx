import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { clearTokens, getRefreshToken, sessionIdentityRevision, subscribeSession } from "../api/client";
import { logout as logoutRequest, me, MeResponse } from "../auth/authApi";
import { AssistantDrawer } from "../aiAgent/AssistantDrawer";
import { BrandLogo } from "../components/BrandLogo";
import { WorkflowDraftProvider } from "../components/WorkflowDraftGuard";
import { TaskEvidenceProvider } from "../aiAgent/taskEvidence";

const userNavigation = [
  { to: "/devices", label: "Devices" },
  { to: "/maintenance", label: "Maintenance" },
  { to: "/alerts", label: "Alerts" },
  { to: "/settings", label: "Settings" },
  { to: "/settings/ml", label: "ML workspace" },
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
  { to: "/settings/ml", label: "ML workspace" },
];

export function AppLayout() {
  const identity = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  return <WorkflowDraftProvider key={identity}><TaskEvidenceProvider><AppLayoutScreen /></TaskEvidenceProvider></WorkflowDraftProvider>;
}
function AppLayoutScreen() {
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
          {["Operations", "ML workspace", "Personal settings", "Administration"].map((group) => {
            const items = navigation.filter((item) => (item.to === "/settings/ml" ? "ML workspace" : item.to === "/settings" ? "Personal settings" : ["/account", "/ai-agent", "/ai-agent/settings"].includes(item.to) ? "Administration" : "Operations") === group);
            return items.length ? <div className="nav-group" key={group}><span className="nav-group-label">{group}</span>{items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to !== activeDestination}
              className={item.to === activeDestination ? "nav-link active" : "nav-link"}
            >
              {item.label}
            </NavLink>
          ))}</div> : null;
          })}
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
