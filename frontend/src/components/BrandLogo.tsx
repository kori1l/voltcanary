import { useId } from "react";

type BrandLogoProps = {
  size?: "default" | "large";
  markOnly?: boolean;
};

export function BrandLogo({ size = "default", markOnly = false }: BrandLogoProps) {
  const id = useId().replace(/:/g, "");
  const bodyGradient = `vc-body-${id}`;
  const bellyGradient = `vc-belly-${id}`;
  const wingGradient = `vc-wing-${id}`;
  const glowGradient = `vc-glow-${id}`;

  return (
    <div
      className={`brand-lockup brand-lockup-${size}${markOnly ? " brand-lockup-mark-only" : ""}`}
      aria-label="VoltCanary"
      title="VoltCanary"
    >
      <span className="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 140 112" role="img" focusable="false">
          <defs>
            <linearGradient id={bodyGradient} x1="28" y1="18" x2="110" y2="92">
              <stop offset="0%" stopColor="#fff36b" />
              <stop offset="46%" stopColor="#ffd21f" />
              <stop offset="100%" stopColor="#f59e0b" />
            </linearGradient>

            <linearGradient id={bellyGradient} x1="55" y1="42" x2="100" y2="96">
              <stop offset="0%" stopColor="#ffe45e" stopOpacity="0.9" />
              <stop offset="100%" stopColor="#f97316" stopOpacity="0.82" />
            </linearGradient>

            <linearGradient id={wingGradient} x1="40" y1="42" x2="78" y2="92">
              <stop offset="0%" stopColor="#35d5ff" />
              <stop offset="52%" stopColor="#1778ff" />
              <stop offset="100%" stopColor="#0b3aa7" />
            </linearGradient>

            <radialGradient id={glowGradient} cx="50%" cy="42%" r="58%">
              <stop offset="0%" stopColor="#ffe45e" stopOpacity="0.42" />
              <stop offset="100%" stopColor="#ffe45e" stopOpacity="0" />
            </radialGradient>
          </defs>

          {/* background energy glow */}
          <ellipse className="brand-bird-glow" cx="69" cy="54" rx="56" ry="42" fill={`url(#${glowGradient})`} />

          {/* clean orbit: behind the bird, not attached to the body */}
          <path
            className="brand-bird-orbit"
            d="M17 69C11 41 32 15 66 12c26-2 49 11 59 34"
          />

          {/* two simple tail feathers */}
          <path
            className="brand-bird-tail brand-bird-tail-back"
            d="M44 66 7 96 52 82 69 57Z"
          />
          <path
            className="brand-bird-tail brand-bird-tail-front"
            d="M49 56 19 88 64 72 79 47Z"
          />

          {/* main canary body: one large readable silhouette */}
          <path
            className="brand-bird-body"
            d="M35 67C43 42 65 24 93 26c16 1 29 9 36 22-17 1-31 7-42 20-13 16-32 19-52-1Z"
            fill={`url(#${bodyGradient})`}
          />

          {/* warmer belly volume */}
          <path
            className="brand-bird-belly"
            d="M61 66c14 8 30 6 43-6-7 19-27 29-48 18-8-4-15-10-21-18 8 5 16 6 26 6Z"
            fill={`url(#${bellyGradient})`}
          />

          {/* head roundness */}
          <path
            className="brand-bird-head"
            d="M79 33c7-15 21-22 36-17 10 3 17 11 19 21-16-3-31 2-44 14-8 8-14 2-11-18Z"
            fill={`url(#${bodyGradient})`}
          />

          {/* beak */}
          <path
            className="brand-bird-beak"
            d="M118 31 139 39 118 47c4-5 4-11 0-16Z"
          />

          {/* blue lightning wing, inside the bird */}
          <path
            className="brand-bird-wing"
            d="M57 47h27L71 64h18L43 99l16-27H37Z"
            fill={`url(#${wingGradient})`}
          />

          {/* soft body highlight */}
          <path
            className="brand-bird-highlight"
            d="M52 52c15-14 32-22 53-23"
          />

          {/* eye */}
          <circle className="brand-bird-eye" cx="108" cy="28" r="4.4" />
          <circle className="brand-bird-eye-light" cx="109.4" cy="26.6" r="1.25" />

          {/* small IoT / analytics nodes placed aside, not on the head or tail */}
          <g className="brand-iot-nodes">
            <path d="M86 89 101 79 119 88" />
            <circle cx="86" cy="89" r="3" />
            <circle cx="101" cy="79" r="3" />
            <circle cx="119" cy="88" r="3" />
          </g>

          {/* tiny perch keeps the bird readable as a bird in small sizes */}
          <path className="brand-bird-perch" d="M54 100c17 5 38 5 58 0" />
        </svg>
      </span>

      {!markOnly && (
        <span className="brand-wordmark">
          <span className="brand-volt">Volt</span>
          <span className="brand-canary">Canary</span>
        </span>
      )}
    </div>
  );
}
