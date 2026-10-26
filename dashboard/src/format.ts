import type { FleetEvent } from "./api";

export function ago(iso: string | null, now = Date.now()): string {
  if (!iso) return "never";
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (s < 5) return "just now";
  if (s < 90) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 90) return `${m} min ago`;
  return `${Math.round(m / 60)} h ago`;
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
}

const str = (v: unknown) => (v == null ? "" : String(v));

/** One plain sentence per event, as an operator would say it. */
export function describe(e: FleetEvent): string {
  const d = e.detail;
  const who = e.serial ?? "";
  switch (e.kind) {
    case "device.allowed":
      return `${who} added to the provisioning list`;
    case "device.onboarded":
      return `${who} claimed its identity and joined`;
    case "device.claim_denied":
      return `${who} claim refused`;
    case "device.decommissioned":
      return `${who} decommissioned; its certificate no longer works`;
    case "session.connected":
      return `${who} connected`;
    case "session.disconnected":
      return `${who} lost its session`;
    case "config.assigned":
      return `${who} assigned config #${str(d.version)} (${reasonName(str(d.reason))}${d.template ? ` of ${str(d.template)}` : ""})`;
    case "config.applied":
      return `${who} applied config #${str(d.version)} and passed its health check`;
    case "config.failed":
      return `${who} could not run config #${str(d.version)}: ${str(d.detail || d.status)}`;
    case "config.drift_detected":
      return `${who} is running a config edited outside EdgeFleet`;
    case "config.drift_cleared":
      return `${who} is back on its intended config`;
    case "template.created":
      return `Template ${str(d.name)} v${str(d.version)} created`;
    case "deployment.created":
      return `Rollout of ${str(d.template)} to ${str(d.group)} started (${str(d.targets)} routers)`;
    case "deployment.batch_started":
      return `Batch ${str(d.batch)} started: ${str(d.pushed)} pushed, ${str(d.deferred)} offline`;
    case "deployment.batch_passed":
      return `Batch ${str(d.batch)} passed: ${str(d.succeeded)} healthy, ${str(d.failed)} failed`;
    case "deployment.completed":
      return `Rollout of ${str(d.template)} completed; it is now the ${str(d.group)} baseline`;
    case "deployment.halted":
      return `Rollout halted: ${str(d.reason)}`;
    case "deployment.rollback_pushed":
      return `Previous config pushed back to ${str(d.devices)} routers`;
    case "deployment.rolled_back":
      return `Rollback finished`;
    case "deployment.paused":
      return `Rollout paused by an operator`;
    case "deployment.resumed":
      return `Rollout resumed`;
    case "deployment.rollback_requested":
      return `Rollback requested by an operator`;
    default:
      return `${e.kind} ${who}`;
  }
}

/** Events that read as routine noise when hundreds arrive at once. */
export const ROUTINE = new Set(["session.connected", "config.assigned", "device.allowed"]);

/** How a config version came about, in words. */
export function reasonName(reason: string): string {
  return (
    { day0: "day-0", deployment: "rollout", rollback: "rollback", "drift-remediation": "drift repair" } as Record<
      string,
      string
    >
  )[reason] ?? reason;
}
