import type { ReactNode } from "react";

export function PageHeader({ title, description, context, actions }: {
  title: string; description?: string; context?: ReactNode; actions?: ReactNode;
}) {
  return <header className="page-header page-header-row">
    <div>{context ? <div className="page-context">{context}</div> : null}<h1>{title}</h1>{description ? <p>{description}</p> : null}</div>
    {actions ? <div className="header-actions">{actions}</div> : null}
  </header>;
}
