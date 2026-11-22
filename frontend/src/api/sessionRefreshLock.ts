const PREFIX = "voltcanary.refresh-lock.";
const LEASE_MS = 60_000;
type Ticket = { choosing: boolean; ticket: number; expires: number };

function tickets(): Array<[string, Ticket]> {
  const result: Array<[string, Ticket]> = [];
  for (let index = 0; index < localStorage.length; index++) {
    const key = localStorage.key(index);
    if (!key?.startsWith(PREFIX)) continue;
    try {
      const value: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
      if (!value || typeof value !== "object") continue;
      const item = value as Ticket;
      if (
        typeof item.choosing !== "boolean" ||
        !Number.isSafeInteger(item.ticket) ||
        item.ticket < 0 ||
        !Number.isFinite(item.expires)
      )
        continue;
      if (item.expires > Date.now()) result.push([key, item]);
    } catch {
      /* An incomplete old coordination entry grants no ownership. */
    }
  }
  return result;
}

/** Web Locks serialize tabs. The fallback is Lamport's bakery ordering over per-contender storage keys. */
export async function withSessionRefreshLock<T>(operation: () => Promise<T>): Promise<T> {
  if (navigator.locks?.request)
    return navigator.locks.request("voltcanary.session.refresh", operation);
  const key = PREFIX + crypto.randomUUID();
  const write = (ticket: Ticket) => localStorage.setItem(key, JSON.stringify(ticket));
  write({ choosing: true, ticket: 0, expires: Date.now() + LEASE_MS });
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  try {
    const ticket = Math.max(0, ...tickets().map(([, item]) => item.ticket)) + 1;
    write({ choosing: false, ticket, expires: Date.now() + LEASE_MS });
    const deadline = Date.now() + 20_000;
    while (
      tickets().some(
        ([other, item]) =>
          other !== key &&
          (item.choosing || item.ticket < ticket || (item.ticket === ticket && other < key)),
      )
    ) {
      if (Date.now() >= deadline) throw new Error("Session renewal coordination timed out");
      await new Promise((resolve) => setTimeout(resolve, 15));
    }
    const own = tickets().find(([id]) => id === key);
    if (!own) throw new Error("Session renewal coordination expired");
    heartbeat = setInterval(
      () => write({ choosing: false, ticket, expires: Date.now() + LEASE_MS }),
      5_000,
    );
    return await operation();
  } finally {
    if (heartbeat !== undefined) clearInterval(heartbeat);
    localStorage.removeItem(key);
  }
}
