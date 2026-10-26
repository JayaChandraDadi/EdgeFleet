// Types mirror the controller's REST API (controller/.../api/ApiController.java).

export type Device = {
  serial: string;
  model: string;
  group: string;
  hostname: string;
  systemIp: string;
  siteId: number;
  region: string;
  state: "ALLOWED" | "ONBOARDED" | "DECOMMISSIONED";
  certSerial: string | null;
  onboardedAt: string | null;
  intendedVersion: number;
  intendedHash: string | null;
  appliedVersion: number;
  runningHash: string | null;
  healthy: boolean | null;
  driftedSince: string | null;
  connected: boolean;
  lastSeenAt: string | null;
};

export type Summary = {
  byState: Record<string, number>;
  onboarded: { connected: number; inSync: number; behind: number; drifted: number; unhealthy: number };
  activeDeployments: number;
};

export type Deployment = {
  id: string;
  template: string;
  templateVersion: number;
  group: string;
  batchPercents: number[];
  maxFailurePct: number;
  healthTimeoutSec: number;
  state: "RUNNING" | "PAUSED" | "COMPLETED" | "ROLLING_BACK" | "ROLLED_BACK" | "CANCELLED";
  currentBatch: number;
  reason: string | null;
  createdAt: string;
  finishedAt: string | null;
};

export type BatchStatus = { batch: number; targets: number; byState: Record<string, number> };
export type DeploymentStatus = { deployment: Deployment; batches: BatchStatus[] };

export type ConfigVersion = {
  version: number;
  hash: string;
  reason: string;
  template: string | null;
  deploymentId: string | null;
  createdAt: string;
};

export type FleetEvent = {
  id: number;
  at: string;
  serial: string | null;
  deploymentId: string | null;
  kind: string;
  detail: Record<string, unknown>;
};

export type DeviceDetail = {
  device: Device;
  inSync: boolean;
  configHistory: ConfigVersion[];
  recentEvents: FleetEvent[];
};

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  summary: () => get<Summary>("/api/fleet/summary"),
  devices: () => get<Device[]>("/api/devices?limit=5000"),
  device: (serial: string) => get<DeviceDetail>(`/api/devices/${encodeURIComponent(serial)}`),
  deployments: () => get<Deployment[]>("/api/deployments?limit=8"),
  deployment: (id: string) => get<DeploymentStatus>(`/api/deployments/${id}`),
  templateDiff: (name: string, from: number, to: number) =>
    get<string[]>(`/api/templates/${encodeURIComponent(name)}/diff?from=${from}&to=${to}`),
  events: (afterId: number) => get<FleetEvent[]>(`/api/events?afterId=${afterId}&limit=500`),
};

/** Where a device stands relative to the config the controller intends for it. */
export type ConfigState = "in-sync" | "behind" | "drifted" | "offline" | "unhealthy" | "waiting" | "retired";

export function configState(d: Device): ConfigState {
  if (d.state === "DECOMMISSIONED") return "retired";
  if (d.state !== "ONBOARDED") return "waiting";
  if (!d.connected) return "offline";
  if (d.driftedSince) return "drifted";
  if (d.healthy === false) return "unhealthy";
  if (d.appliedVersion < d.intendedVersion) return "behind";
  return "in-sync";
}

export const STATE_LABEL: Record<ConfigState, string> = {
  "in-sync": "Running its intended config",
  behind: "Catching up",
  drifted: "Edited outside EdgeFleet",
  unhealthy: "Health check failing",
  offline: "No session",
  waiting: "Not claimed yet",
  retired: "Decommissioned",
};
