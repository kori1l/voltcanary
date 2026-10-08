import { Component, Suspense, lazy, useMemo, useState, type ComponentType, type ReactNode } from "react";

export type RouteLoader = () => Promise<{ default: ComponentType }>;
class DeliveryBoundary extends Component<{ children: ReactNode; retry: () => void; label: string }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <section className="panel" aria-label={`${this.props.label} loading error`}>
      <p role="alert">Unable to load {this.props.label}. Your route and filters are preserved.</p>
      <button type="button" onClick={this.props.retry}>Retry page loading</button>
      <button className="ghost-button" type="button" onClick={() => window.location.reload()}>Reload current page</button>
    </section> : this.props.children;
  }
}
/** A new lazy instance allows an explicit retry after a rejected import. */
export function RouteDelivery({ load, label }: { load: RouteLoader; label: string }) {
  const [attempt, setAttempt] = useState(0);
  const Page = useMemo(() => lazy(load), [load, attempt]);
  return <DeliveryBoundary key={`${label}:${attempt}`} label={label} retry={() => setAttempt((value) => value + 1)}>
    <Suspense fallback={<section className="panel" aria-busy="true"><p role="status">Loading {label}…</p></section>}><Page /></Suspense>
  </DeliveryBoundary>;
}
