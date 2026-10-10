import {LayoutDashboard,Refrigerator,CalendarDays,Bell,Settings,BrainCircuit,Bot,Users} from "lucide-react";
import { Fragment, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import { clearTokens, getRefreshToken, sessionIdentityRevision, subscribeSession } from "../api/client";
import { logout as logoutRequest, me, MeResponse } from "../auth/authApi";
import { AssistantDrawer } from "../aiAgent/AssistantDrawer";
import {Dialog} from "../components/Dialog";
import {DashboardNavigation} from "../features/dashboards/components/DashboardNavigation";
import { BrandLogo } from "../components/BrandLogo";
import { WorkflowDraftProvider } from "../components/WorkflowDraftGuard";
import { TaskEvidenceProvider } from "../aiAgent/taskEvidence";
import {NavigationVisibility,SidebarToggle} from "../components/NavigationVisibility";

const navigationIcons:Record<string,typeof Settings>={"/dashboards":LayoutDashboard,"/devices":Refrigerator,"/maintenance":CalendarDays,"/alerts":Bell,"/settings":Settings,"/settings/ml":BrainCircuit,"/ai-agent":Bot,"/ai-agent/settings":Settings,"/account":Users};

const userNavigation = [
  { to: "/dashboards", label: "Dashboards" },
  { to: "/devices", label: "Devices" },
  { to: "/maintenance", label: "Maintenance" },
  { to: "/alerts", label: "Alerts" },
  { to: "/settings", label: "Settings" },
  { to: "/settings/ml", label: "ML workspace" },
];

const adminNavigation = [
  { to: "/dashboards", label: "Dashboards" },
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
  const [mobile,setMobile]=useState(window.innerWidth<768);
  useEffect(()=>{const resize=()=>setMobile(window.innerWidth<768);resize();window.addEventListener("resize",resize);return()=>window.removeEventListener("resize",resize);},[]);
  const [sidebarHidden,setSidebarHidden]=useState(false);
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
    .filter(({ to }) => location.pathname === to || location.pathname.startsWith(`${to}/`) || (to==="/dashboards" && location.pathname==="/dashboard"))
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

  const navigationLinks=(
        <nav aria-label="Main navigation" className="nav">
          {["Operations", "ML workspace", "Personal settings", "Administration"].map((group) => {
            const items = navigation.filter((item) => (item.to === "/settings/ml" ? "ML workspace" : item.to === "/settings" ? "Personal settings" : ["/account", "/ai-agent", "/ai-agent/settings"].includes(item.to) ? "Administration" : "Operations") === group);
            return items.length ? <div className="nav-group" key={group}><span className="nav-group-label">{group}</span>{items.map((item) => {const Icon=navigationIcons[item.to] ?? Settings;return (
            <Fragment key={item.to}><Link
              to={item.to}
              aria-current={item.to===activeDestination && !(item.to==="/dashboards" && location.pathname.startsWith("/dashboards/"))?"page":undefined}
              className={item.to === activeDestination ? "nav-link active" : "nav-link"}
            >
              <Icon size={18} aria-hidden="true"/><span>{item.label}</span>
            </Link>{item.to==="/dashboards"?<DashboardNavigation actorId={profile?.id}/>:null}</Fragment>
          );})}</div> : null;
          })}
        </nav>
  );
  const kiosk = /^\/dashboards\/[^/]+$/.test(location.pathname) && new URLSearchParams(location.search).get("kiosk") === "true";
  if (kiosk) return <div className="app-shell" style={{gridTemplateColumns:"minmax(0, 1fr)"}}><a className="skip-link" href="#console-main">Skip to content</a><main id="console-main" className="main-content" tabIndex={-1}><Outlet /></main></div>;

  return (
    <NavigationVisibility.Provider value={{mobile,hidden:mobile?!navigationOpen:sidebarHidden,toggle:()=>mobile?setNavigationOpen((open)=>!open):setSidebarHidden((hidden)=>!hidden)}}><div className={`app-shell${sidebarHidden?" app-shell-navigation-hidden":""}`}>
      <a className="skip-link" href="#console-main">Skip to content</a>
      <aside id="console-sidebar" className="sidebar" hidden={sidebarHidden || mobile}>
        <div className="sidebar-heading">
          <BrandLogo />

        </div>
        {navigationLinks}
        <button className="ghost-button sidebar-logout" type="button" onClick={logout}>
          Logout
        </button>
      </aside>
      <main id="console-main" className="main-content" tabIndex={-1}>
        {!location.pathname.startsWith("/dashboards/")?<div className="navigation-restore"><SidebarToggle/></div>:null}
        <Outlet />
      </main>
      {mobile && navigationOpen?<Dialog id="console-navigation-drawer" title="Navigation" side="left" onClose={()=>setNavigationOpen(false)}><BrandLogo/>{navigationLinks}<button type="button" className="ghost-button sidebar-logout" onClick={()=>void logout()}>Logout</button></Dialog>:null}
      <AssistantDrawer />
    </div></NavigationVisibility.Provider>
  );
}
