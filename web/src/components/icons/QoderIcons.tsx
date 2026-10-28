import { forwardRef } from "react";
import type { IconProps } from "./MiuixIcons";

function createQoderIcon(name: string, renderPaths: () => React.ReactNode) {
  const Icon = forwardRef<SVGSVGElement, IconProps>(function Icon(
    { size = 22, className, width, height, style, ...props },
    ref,
  ) {
    const w = width ?? size;
    const h = height ?? size;
    return (
      <svg
        ref={ref}
        width={w}
        height={h}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={className}
        style={style}
        {...props}
      >
        {renderPaths()}
      </svg>
    );
  });
  Icon.displayName = `QoderIcon.${name}`;
  return Icon;
}

export const QoderBack = createQoderIcon("BACK", () => (
  <path d="M 15 5 L 8 12 L 15 19" />
));

export const QoderChevron = createQoderIcon("CHEVRON", () => (
  <path d="M 9 6 L 15 12 L 9 18" />
));

export const QoderClose = createQoderIcon("CLOSE", () => (
  <>
    <line x1="6" y1="6" x2="18" y2="18" />
    <line x1="18" y1="6" x2="6" y2="18" />
  </>
));

export const QoderMore = createQoderIcon("MORE", () => (
  <>
    <circle cx="5" cy="12" r="1.7" fill="currentColor" stroke="none" />
    <circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none" />
    <circle cx="19" cy="12" r="1.7" fill="currentColor" stroke="none" />
  </>
));

export const QoderPlus = createQoderIcon("PLUS", () => (
  <>
    <line x1="12" y1="4" x2="12" y2="20" />
    <line x1="4" y1="12" x2="20" y2="12" />
  </>
));

export const QoderDown = createQoderIcon("DOWN", () => (
  <>
    <line x1="12" y1="4" x2="12" y2="20" />
    <path d="M 5 13 L 12 20 L 19 13" />
  </>
));

export const QoderSend = createQoderIcon("SEND", () => (
  <>
    <line x1="12" y1="20" x2="12" y2="4" />
    <path d="M 5 11 L 12 4 L 19 11" />
  </>
));

export const QoderStop = createQoderIcon("STOP", () => (
  <rect x="6" y="6" width="12" height="12" rx="1.5" fill="currentColor" stroke="none" />
));

export const QoderCheck = createQoderIcon("CHECK", () => (
  <>
    <circle cx="12" cy="12" r="9" />
    <path d="M 7.5 12 L 10.5 15 L 16 9" />
  </>
));

export const QoderError = createQoderIcon("ERROR", () => (
  <>
    <circle cx="12" cy="12" r="9" />
    <line x1="9" y1="9" x2="15" y2="15" />
    <line x1="15" y1="9" x2="9" y2="15" />
  </>
));

export const QoderThink = createQoderIcon("THINK", () => (
  <path d="M 12 2 L 15 4 L 18 4 L 19 7 L 22 9 L 21 12 L 22 15 L 19 17 L 18 20 L 15 20 L 12 22 L 9 20 L 6 20 L 5 17 L 2 15 L 3 12 L 2 9 L 5 7 L 6 4 L 9 4 Z" />
));

export const QoderWarning = createQoderIcon("WARNING", () => (
  <>
    <path d="M 12 2 L 22 21 L 2 21 Z" />
    <line x1="12" y1="8" x2="12" y2="13" />
    <circle cx="12" cy="17" r="1" fill="currentColor" stroke="none" />
  </>
));

export const QoderComputer = createQoderIcon("COMPUTER", () => (
  <>
    <rect x="3" y="4" width="18" height="13" rx="1" />
    <line x1="2" y1="21" x2="22" y2="21" />
  </>
));

export const QoderMic = createQoderIcon("MIC", () => (
  <>
    <rect x="9" y="2" width="6" height="12" rx="3" strokeWidth={1.8} />
    <path d="M 5 12 A 7 6 0 0 0 19 12" strokeWidth={1.8} />
    <line x1="12" y1="18" x2="12" y2="22" strokeWidth={1.8} />
  </>
));

export const QoderIcons = {
  Back: QoderBack,
  Chevron: QoderChevron,
  Close: QoderClose,
  More: QoderMore,
  Plus: QoderPlus,
  Down: QoderDown,
  Send: QoderSend,
  Stop: QoderStop,
  Check: QoderCheck,
  Error: QoderError,
  Think: QoderThink,
  Warning: QoderWarning,
  Computer: QoderComputer,
  Mic: QoderMic,
};

export default QoderIcons;
