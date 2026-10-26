import * as Dialog from "@radix-ui/react-dialog";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import * as SwitchPrimitive from "@radix-ui/react-switch";
import { Check, ChevronLeft, ChevronRight, X, type LucideIcon } from "lucide-react";
import { forwardRef, useEffect, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from "react";

export const cx = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(" ");

type Variant = "primary" | "secondary" | "ghost" | "danger" | "subtle";

const variants: Record<Variant, string> = {
  primary: "bg-primary text-on-primary hover:brightness-105 active:brightness-95",
  secondary: "bg-surface-2 text-text hover:bg-surface-3",
  subtle: "bg-primary-subtle text-primary hover:brightness-105",
  ghost: "text-primary hover:bg-primary-subtle",
  danger: "bg-error-subtle text-error hover:brightness-105",
};

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md" | "lg"; block?: boolean }>(
  function Button({ variant = "primary", size = "md", block, className, ...props }, ref) {
    return (
      <button
        ref={ref}
        type="button"
        className={cx(
          "squircle inline-flex items-center justify-center gap-1.5 rounded-full font-medium transition-[filter,background-color,opacity] disabled:pointer-events-none disabled:opacity-45",
          size === "sm" ? "h-8 px-3.5 text-[13px]" : size === "lg" ? "h-12 px-6 text-[16px]" : "h-10 px-5 text-[15px]",
          block && "w-full",
          variants[variant],
          className,
        )}
        {...props}
      />
    );
  },
);

export const IconButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & { icon: LucideIcon; label: string; tone?: "plain" | "filled" | "chat"; iconSize?: number }>(
  function IconButton({ icon: Icon, label, tone = "plain", iconSize = 20, className, ...props }, ref) {
    return (
      <button
        ref={ref}
        type="button"
        aria-label={label}
        title={label}
        className={cx(
          "inline-flex size-10 shrink-0 items-center justify-center rounded-full transition-colors disabled:opacity-40",
          tone === "filled" ? "bg-surface-2 text-text hover:bg-surface-3" : tone === "chat" ? "text-chat-text hover:bg-bubble" : "text-text hover:bg-surface-2",
          className,
        )}
        {...props}
      >
        <Icon size={iconSize} strokeWidth={1.9} />
      </button>
    );
  },
);

export function Spinner({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg className={cx("spin text-primary", className)} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeOpacity="0.2" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export function Switch({ checked, onChange, disabled, label }: { checked: boolean; onChange: (value: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <SwitchPrimitive.Root
      checked={checked}
      onCheckedChange={onChange}
      disabled={disabled}
      aria-label={label}
      className="relative inline-flex h-[30px] w-[50px] shrink-0 items-center rounded-full bg-surface-3 transition-colors data-[state=checked]:bg-primary disabled:opacity-45"
    >
      <SwitchPrimitive.Thumb className="block size-[24px] translate-x-[3px] rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[23px]" />
    </SwitchPrimitive.Root>
  );
}

export const TextField = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { label?: string; hint?: ReactNode }>(
  function TextField({ label, hint, className, id, ...props }, ref) {
    const inputId = id ?? (label ? `field-${label}` : undefined);
    return (
      <label className="block" htmlFor={inputId}>
        {label && <span className="mb-1.5 block px-1 text-[13px] text-text-2">{label}</span>}
        <input
          ref={ref}
          id={inputId}
          className={cx("squircle h-12 w-full rounded-2xl bg-surface-2 px-4 text-[16px] text-text outline-none placeholder:text-text-3 focus:ring-2 focus:ring-primary/40", className)}
          {...props}
        />
        {hint && <span className="mt-1.5 block px-1 text-[12.5px] leading-relaxed text-text-2">{hint}</span>}
      </label>
    );
  },
);

/** A textarea that grows with its content up to [maxRows]. */
export const AutoTextarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { maxRows?: number }>(
  function AutoTextarea({ maxRows = 8, className, value, ...props }, forwarded) {
    const inner = useRef<HTMLTextAreaElement | null>(null);
    useEffect(() => {
      const element = inner.current;
      if (!element) return;
      element.style.height = "auto";
      const line = parseFloat(getComputedStyle(element).lineHeight) || 22;
      element.style.height = `${Math.min(element.scrollHeight, line * maxRows + 4)}px`;
    }, [value, maxRows]);
    return (
      <textarea
        ref={(node) => {
          inner.current = node;
          if (typeof forwarded === "function") forwarded(node);
          else if (forwarded) forwarded.current = node;
        }}
        rows={1}
        value={value}
        className={cx("block w-full resize-none bg-transparent outline-none", className)}
        {...props}
      />
    );
  },
);

/** Grouped settings card (HyperOS style): a rounded surface with divided rows. */
export function ListGroup({ title, children, footer, className }: { title?: ReactNode; children: ReactNode; footer?: ReactNode; className?: string }) {
  return (
    <section className={cx("mb-5", className)}>
      {title && <h3 className="mb-2 px-5 text-[13px] text-text-2">{title}</h3>}
      <div className="squircle mx-3 overflow-hidden rounded-[22px] bg-surface [&>*+*]:border-t [&>*+*]:border-divider">{children}</div>
      {footer && <p className="mt-2 px-5 text-[12.5px] leading-relaxed text-text-2">{footer}</p>}
    </section>
  );
}

export function ListItem({ title, summary, trailing, onClick, chevron, danger, icon: Icon, disabled }: {
  title: ReactNode; summary?: ReactNode; trailing?: ReactNode; onClick?: () => void; chevron?: boolean; danger?: boolean; icon?: LucideIcon; disabled?: boolean;
}) {
  const content = (
    <>
      {Icon && <Icon size={20} className={cx("shrink-0", danger ? "text-error" : "text-text-2")} />}
      <span className="min-w-0 flex-1">
        <span className={cx("block truncate text-[16px]", danger ? "text-error" : "text-text")}>{title}</span>
        {summary && <span className="mt-0.5 block text-[13px] leading-snug text-text-2 [overflow-wrap:anywhere]">{summary}</span>}
      </span>
      {trailing}
      {chevron && <ChevronRight size={18} className="shrink-0 text-text-3" />}
    </>
  );
  const base = "flex w-full items-center gap-3 px-4 py-3.5 text-left min-h-[56px]";
  return onClick ? (
    <button type="button" disabled={disabled} onClick={onClick} className={cx(base, "transition-colors hover:bg-surface-2 active:bg-surface-2 disabled:opacity-45")}>{content}</button>
  ) : (
    <div className={base}>{content}</div>
  );
}

/** Page header: back, a title (optionally large), a subtitle and trailing actions. Respects the notch. */
export function PageHeader({ title, subtitle, onBack, actions, large, className }: {
  title: ReactNode; subtitle?: ReactNode; onBack?: () => void; actions?: ReactNode; large?: boolean; className?: string;
}) {
  return (
    <header className={cx("safe-top sticky top-0 z-20 shrink-0 bg-inherit", className)}>
      <div className="flex min-h-14 items-center gap-1 px-2 py-1.5">
        {onBack && <IconButton icon={ChevronLeft} label="返回" onClick={onBack} iconSize={24} />}
        <div className={cx("min-w-0 flex-1", !onBack && "pl-3")}>
          {!large && <h1 className="truncate text-[17px] font-semibold">{title}</h1>}
          {!large && subtitle && <div className="truncate text-[12.5px] text-text-2">{subtitle}</div>}
        </div>
        {actions}
      </div>
      {large && (
        <div className="px-5 pb-2">
          <h1 className="text-[28px] font-semibold leading-tight">{title}</h1>
          {subtitle && <div className="mt-1 text-[13px] text-text-2">{subtitle}</div>}
        </div>
      )}
    </header>
  );
}

/**
 * A modal sheet: slides up from the bottom on phones, a centered card on wide screens. Radix handles
 * focus, Escape and the scroll lock.
 */
export function Sheet({ open, onOpenChange, title, children, footer, wide, chat }: {
  open: boolean; onOpenChange: (open: boolean) => void; title: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean; chat?: boolean;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fade-in fixed inset-0 z-40 bg-scrim" />
        <Dialog.Content
          aria-describedby={undefined}
          className={cx(
            "fixed z-50 flex max-h-[88dvh] flex-col overflow-hidden outline-none",
            chat ? "bg-sheet text-chat-text" : "bg-surface text-text",
            "inset-x-0 bottom-0 rounded-t-[28px] pb-[var(--safe-bottom)] [animation:lagoon-sheet-in_.26s_cubic-bezier(.2,.8,.2,1)]",
            "md:inset-x-auto md:bottom-auto md:left-1/2 md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2 md:w-[min(92vw,var(--sheet-width))] md:rounded-[28px] md:pb-0 md:[animation:lagoon-pop-in_.18s_ease-out]",
            "squircle",
          )}
          style={{ ["--sheet-width" as string]: wide ? "760px" : "520px" }}
          onOpenAutoFocus={(event) => { if (window.matchMedia("(pointer: coarse)").matches) event.preventDefault(); }}
        >
          <div className="mx-auto mt-2 h-1 w-10 rounded-full bg-text-3/40 md:hidden" />
          <div className="flex items-center gap-2 px-5 pb-2 pt-3">
            <Dialog.Title className="min-w-0 flex-1 truncate text-[18px] font-semibold">{title}</Dialog.Title>
            <Dialog.Close asChild>
              <IconButton icon={X} label="关闭" tone={chat ? "chat" : "plain"} />
            </Dialog.Close>
          </div>
          <div className="scroll-area min-h-0 flex-1 px-4 pb-4">{children}</div>
          {footer && <div className="border-t border-divider px-4 py-3">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function ConfirmDialog({ open, onOpenChange, title, message, confirm, danger, busy, onConfirm }: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; message: ReactNode; confirm: string; danger?: boolean; busy?: boolean; onConfirm: () => void;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => { if (!busy) onOpenChange(next); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fade-in fixed inset-0 z-40 bg-scrim" />
        <Dialog.Content
          aria-describedby={undefined}
          className="squircle fixed left-1/2 top-1/2 z-50 w-[min(90vw,400px)] -translate-x-1/2 -translate-y-1/2 rounded-[28px] bg-surface p-6 text-text [animation:lagoon-pop-in_.18s_ease-out]"
        >
          <Dialog.Title className="text-[18px] font-semibold">{title}</Dialog.Title>
          <div className="mt-3 whitespace-pre-line text-[15px] leading-relaxed text-text-2 [overflow-wrap:anywhere]">{message}</div>
          <div className="mt-6 flex gap-3">
            <Button variant="secondary" block disabled={busy} onClick={() => onOpenChange(false)}>取消</Button>
            <Button variant={danger ? "danger" : "primary"} block disabled={busy} onClick={onConfirm}>{busy ? <Spinner /> : confirm}</Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export interface MenuItem {
  label: string;
  icon?: LucideIcon;
  danger?: boolean;
  checked?: boolean;
  disabled?: boolean;
  onSelect: () => void;
}

/** A dropdown menu of sections; [trigger] is rendered as the anchor. */
export function Menu({ trigger, sections, align = "end", open, onOpenChange }: {
  trigger: ReactNode; sections: { title?: string; items: MenuItem[] }[]; align?: "start" | "end"; open?: boolean; onOpenChange?: (open: boolean) => void;
}) {
  const visible = sections.filter((it) => it.items.length > 0);
  return (
    <DropdownMenu.Root open={open} onOpenChange={onOpenChange} modal={false}>
      <DropdownMenu.Trigger asChild>{trigger}</DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={align}
          sideOffset={6}
          collisionPadding={12}
          className="squircle fade-in z-50 min-w-[200px] max-w-[min(86vw,320px)] overflow-hidden rounded-[22px] bg-surface py-1.5 text-text shadow-[0_12px_40px_rgba(0,0,0,0.18)] ring-1 ring-divider"
        >
          {visible.map((section, index) => (
            <DropdownMenu.Group key={index} className={cx(index > 0 && "mt-1.5 border-t border-divider pt-1.5")}>
              {section.title && <DropdownMenu.Label className="px-4 pb-1 pt-1.5 text-[12px] text-text-2">{section.title}</DropdownMenu.Label>}
              {section.items.map((item) => (
                <DropdownMenu.Item
                  key={item.label}
                  disabled={item.disabled}
                  onSelect={item.onSelect}
                  className={cx(
                    "flex cursor-pointer items-center gap-3 px-4 py-2.5 text-[15px] outline-none data-[highlighted]:bg-surface-2 data-[disabled]:opacity-45",
                    item.danger && "text-error",
                  )}
                >
                  {item.icon && <item.icon size={18} className={item.danger ? "text-error" : "text-text-2"} />}
                  <span className="flex-1">{item.label}</span>
                  {item.checked && <Check size={16} className="text-primary" />}
                </DropdownMenu.Item>
              ))}
            </DropdownMenu.Group>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

export function Empty({ title, detail, action }: { title: string; detail?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-8 py-14 text-center">
      <p className="text-[15px] text-text-2">{title}</p>
      {detail && <p className="mt-2 text-[13px] leading-relaxed text-text-3">{detail}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function SegmentedControl<T extends string>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (value: T) => void }) {
  return (
    <div className="squircle flex rounded-2xl bg-surface-2 p-1" role="radiogroup">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          onClick={() => onChange(option.value)}
          className={cx("squircle h-10 flex-1 rounded-xl text-[14.5px] transition-colors", value === option.value ? "bg-surface font-medium text-text shadow-sm" : "text-text-2")}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
