import { Navigate, Route, Routes } from "react-router-dom";
import { AppLayout } from "./AppLayout";
import { AlertsPage } from "../pages/AlertsPage";
import { DashboardPage } from "../pages/DashboardPage";
import { DeviceDetailsPage } from "../pages/DeviceDetailsPage";
import { DevicesPage } from "../pages/DevicesPage";
import { LoginPage } from "../pages/LoginPage";

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/devices/:deviceId" element={<DeviceDetailsPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
      </Route>
    </Routes>
  );
}
