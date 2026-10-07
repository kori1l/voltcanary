import type { ReactNode } from "react";
import { BrandLogo } from "../components/BrandLogo";

export function AuthShell({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <main className="auth-page"><section className="auth-panel"><BrandLogo /><h1>{title}</h1><p className="auth-description">{description}</p>{children}</section></main>;
}
