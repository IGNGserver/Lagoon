/** The Lagoon mark (same artwork as the site favicon). */
export function BrandMark({ size = 40 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden="true" className="squircle rounded-[22%]">
      <defs>
        <linearGradient id="lagoon-mark" x1="8" y1="40" x2="42" y2="8" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#4752D6" />
          <stop offset="1" stopColor="#59D2E6" />
        </linearGradient>
      </defs>
      <rect width="48" height="48" rx="12" fill="#0A1838" />
      <path d="M35 13C32.9 10.8 29.8 9.5 26.2 9.5H20C13.9 9.5 9 14.4 9 20.5V27.5C9 33.6 13.9 38.5 20 38.5H28.5C32.2 38.5 35.4 36.7 37.3 33.8"
        fill="none" stroke="url(#lagoon-mark)" strokeWidth="5.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M33.5 22H37.5L43 27.5L37.5 33H33.5L39 27.5Z" fill="#59D2E6" />
      <circle cx="39" cy="9" r="3" fill="#52E0C4" />
    </svg>
  );
}
