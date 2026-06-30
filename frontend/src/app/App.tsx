import { Navigate, Route, Routes } from "react-router-dom";
import { AppLayout } from "./AppLayout";
import { isAuthenticated } from "../api/client";
import { AlertsPage } from "../pages/AlertsPage";
import { AiAgentPage } from "../pages/AiAgentPage";
import { AiAgentSettingsPage } from "../pages/AiAgentSettingsPage";
import { AccountPage } from "../pages/AccountPage";
import { DashboardPage } from "../pages/DashboardPage";
import { DeviceDetailsPage } from "../pages/DeviceDetailsPage";
import { DevicesPage } from "../pages/DevicesPage";
import { SettingsPage } from "../pages/SettingsPage";
import { ForgotPasswordPage } from "../pages/ForgotPasswordPage";
import { LoginPage } from "../pages/LoginPage";
import { MaintenanceCalendarPage } from "../maintenance/MaintenanceCalendarPage";
import { RegisterPage } from "../pages/RegisterPage";
import { ResetPasswordPage } from "../pages/ResetPasswordPage";
import { VerifyEmailPage } from "../pages/VerifyEmailPage";

function RequireAuth({ children }: { children: JSX.Element }) {
  return isAuthenticated() ? children : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Navigate to="/devices" replace />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/maintenance" element={<MaintenanceCalendarPage />} />
        <Route path="/devices/:deviceId" element={<DeviceDetailsPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/ai-agent" element={<AiAgentPage />} />
        <Route path="/ai-agent/settings" element={<AiAgentSettingsPage />} />
        <Route path="/account" element={<AccountPage />} />
      </Route>
      <Route path="*" element={<Navigate to={isAuthenticated() ? "/devices" : "/login"} replace />} />
    </Routes>
  );
}
