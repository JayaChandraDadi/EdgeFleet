import { useCallback, useState } from "react";
import { api, configState } from "./api";
import { usePoll } from "./usePoll";
import { Rollouts } from "./Rollouts";
import { Fleet } from "./Fleet";
import { Activity } from "./Activity";
import { DeviceDrawer } from "./DeviceDrawer";

export function App() {
  const devices = usePoll(api.devices, 2000);
  const [deployment, setDeployment] = useState<string | null>(null);
  const [device, setDevice] = useState<string | null>(null);
  const closeDrawer = useCallback(() => setDevice(null), []);

  const list = devices.data ?? [];
  const onboarded = list.filter((d) => d.state === "ONBOARDED");
  const retired = list.filter((d) => d.state === "DECOMMISSIONED").length;
  const waiting = list.length - onboarded.length - retired;
  const inSync = onboarded.filter((d) => configState(d) === "in-sync").length;
  const off = onboarded.length - inSync;

  return (
    <div className="page">
      <header className="masthead">
        <h1>EdgeFleet</h1>
        <p className="lede">
          {devices.error && !devices.data ? (
            <>Can't reach the controller ({devices.error}). Start it and this page reconnects on its own.</>
          ) : list.length === 0 ? (
            <>No routers on the provisioning list yet. Import one with POST /api/inventory.</>
          ) : (
            <>
              {onboarded.length} routers onboarded
              {waiting > 0 && <>, {waiting} waiting to be claimed</>}
              {retired > 0 && <>, {retired} decommissioned</>}.{" "}
              {off === 0 ? (
                <>Every one runs exactly the config intended for it.</>
              ) : (
                <>
                  {inSync} run exactly their intended config; <strong>{off} need attention</strong>.
                </>
              )}
            </>
          )}
        </p>
      </header>

      <main className="layout">
        <Rollouts selected={deployment} onSelect={setDeployment} />
        <Fleet devices={list} selected={device} onSelect={setDevice} />
        <Activity onSelectDevice={setDevice} />
      </main>

      {device && <DeviceDrawer serial={device} onClose={closeDrawer} />}
    </div>
  );
}
