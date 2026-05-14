import { useId } from "react";

type BrandLogoProps = {
  size?: "default" | "large";
  markOnly?: boolean;
};

export function BrandLogo({ size = "default", markOnly = false }: BrandLogoProps) {
  const id = useId().replace(/:/g, "");
  const birdGradient = `vc-bird-${id}`;

  return (
    <div
      className={`brand-lockup brand-lockup-${size}${markOnly ? " brand-lockup-mark-only" : ""}`}
      aria-label="VoltCanary"
      title="VoltCanary"
    >
      <span className="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 227 236" role="img" focusable="false">
          <defs>
            <linearGradient
              id={birdGradient}
              x1="40"
              y1="35"
              x2="170"
              y2="210"
              gradientUnits="userSpaceOnUse"
            >
              <stop offset="0%" stopColor="#fff36b" />
              <stop offset="45%" stopColor="#ffd21f" />
              <stop offset="100%" stopColor="#f5b000" />
            </linearGradient>
          </defs>

          <path
            className="brand-bird-shape"
            d="
              M 215 66
              L 178 42
              L 167 42
              L 156 47
              L 148 55
              L 131 86
              L 88 132
              L 65 168
              L 76 165
              L 81 167
              L 28 235
              L 120 151
              L 105 152
              L 152 103
              L 153 106
              L 131 139
              L 147 140
              L 87 194
              L 138 167
              L 139 171
              L 129 183
              L 147 205
              L 167 208
              L 150 203
              L 135 186
              L 136 180
              L 165 160
              L 180 144
              L 190 124
              L 194 84
              L 201 74
              Z
            "
            fill={`url(#${birdGradient})`}
          />
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
