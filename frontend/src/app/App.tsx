import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useSyncExternalStore, type ReactElement } from "react";
import { RouteDelivery } from "./RouteDelivery";
import { isAuthenticated, sessionStatus, subscribeSession } from "../api/client";
import { safeReturnTo } from "./consoleRouteContext";
import { ForgotPasswordPage } from "../pages/ForgotPasswordPage";
import { LoginPage } from "../pages/LoginPage";
import { RegisterPage } from "../pages/RegisterPage";
import { ResetPasswordPage } from "../pages/ResetPasswordPage";
import { VerifyEmailPage } from "../pages/VerifyEmailPage";

const loadLayout = () => import("./AppLayout").then((m) => ({ default: m.AppLayout }));
const loadAlerts = () => import("../pages/AlertsPage").then((m) => ({ default: m.AlertsPage }));
const loadAi = () => import("../pages/AiAgentPage").then((m) => ({ default: m.AiAgentPage }));
const loadAiSettings = () => import("../pages/AiAgentSettingsPage").then((m) => ({ default: m.AiAgentSettingsPage }));
const loadAccount = () => import("../pages/AccountPage").then((m) => ({ default: m.AccountPage }));
const loadDashboard = () => import("../pages/PersonalDashboardsPage").then((m) => ({ default: m.DashboardDefaultPage }));
const loadDashboards = () => import("../pages/PersonalDashboardsPage").then((m) => ({ default: m.PersonalDashboardsPage }));
const loadPersonalDashboard = () => import("../pages/PersonalDashboardsPage").then((m) => ({ default: m.PersonalDashboardPage }));
const loadDevice = () => import("../pages/DeviceDetailsPage").then((m) => ({ default: m.DeviceDetailsPage }));
const loadDevices = () => import("../pages/DevicesPage").then((m) => ({ default: m.DevicesPage }));
const loadSettings = () => import("../pages/SettingsPage").then((m) => ({ default: m.SettingsPage }));
const loadMaintenance = () => import("../maintenance/MaintenanceCalendarPage").then((m) => ({ default: m.MaintenanceCalendarPage }));
const loadMl = () => import("../pages/MlSettingsPage").then((m) => ({ default: m.MlSettingsPage }));

export function RequireAuth({ children }: { children: ReactElement }) {
  const status = useSyncExternalStore(subscribeSession, sessionStatus);
  const location = useLocation();
  const query = new URLSearchParams({ returnTo: safeReturnTo(`${location.pathname}${location.search}`) });
  if (status === "expired") query.set("reason", "expired");
  return status === "authenticated" ? children : <Navigate to={`/login?${query}`} replace />;
}

export function App() {
  return <Routes>{appRouteElements()}</Routes>;
}
export function appRouteElements() {
  return (
    <>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route
        element={
          <RequireAuth>
            <RouteDelivery load={loadLayout} label="console" />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<RouteDelivery load={loadDashboard} label="dashboard" />} />
        <Route path="/dashboards" element={<RouteDelivery load={loadDashboards} label="my dashboards" />} />
        <Route path="/dashboards/:dashboardId" element={<RouteDelivery load={loadPersonalDashboard} label="personal dashboard" />} />
        <Route path="/dashboards/:dashboardId/edit" element={<RouteDelivery load={loadPersonalDashboard} label="dashboard editor" />} />
        <Route path="/devices" element={<RouteDelivery load={loadDevices} label="devices" />} />
        <Route path="/maintenance" element={<RouteDelivery load={loadMaintenance} label="maintenance" />} />
        <Route path="/devices/:deviceId" element={<RouteDelivery load={loadDevice} label="device details" />} />
        <Route path="/alerts" element={<RouteDelivery load={loadAlerts} label="alerts" />} />
        <Route path="/settings" element={<RouteDelivery load={loadSettings} label="settings" />} />
        <Route path="/settings/ml" element={<RouteDelivery load={loadMl} label="ML workspace" />} />
        <Route path="/ai-agent" element={<RouteDelivery load={loadAi} label="AI agent" />} />
        <Route path="/ai-agent/settings" element={<RouteDelivery load={loadAiSettings} label="AI settings" />} />
        <Route path="/account" element={<RouteDelivery load={loadAccount} label="administration" />} />
      </Route>
      <Route path="*" element={<Navigate to={isAuthenticated() ? "/devices" : "/login"} replace />} />
    </>
  );
}
