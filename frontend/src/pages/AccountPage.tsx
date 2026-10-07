import { AdminWorkspace } from "../features/admin/AdminWorkspace";

// Preserve the existing /account deep link as the dedicated Admin surface.
export function AccountPage() {
  return <AdminWorkspace />;
}
