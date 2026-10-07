import { Location } from "./devicesApi";
export function DeviceIcon({ type }: { type: string }) {
  const normalizedType = type.toUpperCase();
  if (normalizedType.includes("FRIDGE") || normalizedType.includes("FREEZER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="15" y="6" width="18" height="36" rx="3" />
        <line x1="15" y1="20" x2="33" y2="20" />
        <line x1="20" y1="12" x2="20" y2="16" />
        <line x1="20" y1="25" x2="20" y2="31" />
      </svg>
    );
  }
  if (normalizedType.includes("KETTLE")) {
    return <svg viewBox="0 0 48 48" role="img"><path d="M14 18h20l3 20H11z" /><path d="M18 18v-4h12v4M34 22h5c5 0 5 10 0 10h-3M14 21l-7-4 4 12M10 41h28" /><path d="M21 10v-3M27 10v-3" /></svg>;
  }
  if (normalizedType.includes("HEATER") || normalizedType.includes("BOILER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="11" y="14" width="26" height="22" rx="4" />
        <line x1="17" y1="20" x2="31" y2="20" />
        <path d="M18 30c2-4 5-4 7 0s5 4 7 0" />
        <line x1="16" y1="40" x2="16" y2="36" />
        <line x1="32" y1="40" x2="32" y2="36" />
      </svg>
    );
  }
  if (/(?:^|[_ -])(?:AC|AIR|AIRCON|HVAC)(?:$|[_ -])/.test(normalizedType)) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="8" y="12" width="32" height="16" rx="4" />
        <line x1="14" y1="22" x2="34" y2="22" />
        <path d="M17 33c2 2 4 2 6 0" />
        <path d="M25 33c2 2 4 2 6 0" />
      </svg>
    );
  }
  if (normalizedType.includes("WASH")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="12" y="7" width="24" height="34" rx="4" />
        <circle cx="24" cy="27" r="8" />
        <circle cx="18" cy="13" r="1.8" />
        <line x1="23" y1="13" x2="31" y2="13" />
      </svg>
    );
  }
  if (normalizedType.includes("PUMP")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <circle cx="22" cy="24" r="10" />
        <path d="M31 20h7v8h-7" />
        <path d="M12 24H7" />
        <path d="M20 18l7 6-7 6z" />
      </svg>
    );
  }
  if (normalizedType.includes("SERVER") || normalizedType.includes("NAS")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="12" y="8" width="24" height="32" rx="3" />
        <line x1="17" y1="17" x2="31" y2="17" />
        <line x1="17" y1="25" x2="31" y2="25" />
        <circle cx="18" cy="33" r="1.7" />
        <circle cx="24" cy="33" r="1.7" />
      </svg>
    );
  }
  if (normalizedType.includes("PRINTER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="14" y="7" width="20" height="11" rx="2" />
        <rect x="10" y="18" width="28" height="16" rx="3" />
        <rect x="15" y="30" width="18" height="10" rx="2" />
        <circle cx="33" cy="24" r="1.8" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 48 48" role="img">
      <rect x="14" y="10" width="20" height="28" rx="4" />
      <path d="M19 10V6" />
      <path d="M29 10V6" />
      <path d="M20 20h8" />
      <path d="M20 27h8" />
    </svg>
  );
}

export function getDeviceTone(type: string) {
  const normalizedType = type.toUpperCase();
  if (normalizedType.includes("FRIDGE") || normalizedType.includes("FREEZER")) {
    return "cool";
  }
  if (normalizedType.includes("HEATER") || normalizedType.includes("BOILER")) {
    return "warm";
  }
  if (normalizedType.includes("SERVER") || normalizedType.includes("NAS")) {
    return "compute";
  }
  if (normalizedType.includes("PUMP") || normalizedType.includes("WASH")) {
    return "water";
  }
  return "neutral";
}

export function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function formatNumber(value: number) {
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export function formatRelativeTime(value: string) {
  const diffMs = Date.now() - new Date(value).getTime();
  const diffMinutes = Math.max(0, Math.round(diffMs / 60000));
  if (diffMinutes < 1) {
    return "just now";
  }
  if (diffMinutes < 60) {
    return `${diffMinutes} min ago`;
  }
  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} hr ago`;
  }
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

export function locationLabel(location: Location) {
  const hierarchy = [location.building, location.floor ? `Floor ${location.floor}` : null, location.room, location.zone]
    .filter(Boolean)
    .join(" / ");
  return hierarchy ? `${location.name} - ${hierarchy}` : location.name;
}

export function splitTags(value: string) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

export function parseMetadata(value: string) {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "{}") {
    return null;
  }
  const parsed = JSON.parse(trimmed) as Record<string, unknown>;
  const secretKey = Object.keys(parsed).find((key) => /password|token|secret|credential/i.test(key));
  if (secretKey) {
    throw new Error(`Metadata must not contain secret key: ${secretKey}`);
  }
  return parsed;
}

export function downloadText(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
