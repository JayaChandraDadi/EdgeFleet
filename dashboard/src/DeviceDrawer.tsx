import { useEffect, useRef } from "react";
import { STATE_LABEL, api, configState } from "./api";
import { usePoll } from "./usePoll";
import { ago, clock, describe, reasonName } from "./format";

export function DeviceDrawer({ serial, onClose }: { serial: string; onClose: () => void }) {
  const detail = usePoll(() => api.device(serial), 2000, [serial]);
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [serial, onClose]);

  const d = detail.data?.device;
  return (
    <aside className="drawer" aria-label={`Router ${serial}`}>
      <div className="drawer-head">
        <h2>{d ? d.hostname : serial}</h2>
        <button ref={closeRef} type="button" className="close" onClick={onClose}>
          Close
        </button>
      </div>
      {d && (
        <>
          <p className={`state-line ${configState(d)}`}>{STATE_LABEL[configState(d)]}</p>
          <dl className="facts">
            <dt>Serial</dt>
            <dd>{d.serial}</dd>
            <dt>System IP</dt>
            <dd>{d.systemIp}</dd>
            <dt>Site</dt>
            <dd>
              {d.siteId} in {d.region}
            </dd>
            <dt>Config</dt>
            <dd>
              running #{d.appliedVersion}, intended #{d.intendedVersion}
            </dd>
            <dt>Certificate</dt>
            <dd className="mono">{d.certSerial ? d.certSerial.slice(0, 16) + "…" : "none yet"}</dd>
            <dt>Last heard</dt>
            <dd>{ago(d.lastSeenAt)}</dd>
          </dl>
          <h3>Config versions</h3>
          <ol className="versions">
            {detail.data!.configHistory.map((v) => (
              <li key={v.version}>
                <strong>#{v.version}</strong> {reasonName(v.reason)}
                {v.template && <span className="muted"> of template {v.template}</span>}
                <span className="muted"> {ago(v.createdAt)}</span>
              </li>
            ))}
          </ol>
          <h3>Recent events</h3>
          <ol className="feed compact">
            {[...detail.data!.recentEvents].reverse().map((e) => (
              <li key={e.id} className="feed-item">
                <time dateTime={e.at}>{clock(e.at)}</time>
                <span>{describe(e).replace(`${d.serial} `, "")}</span>
              </li>
            ))}
          </ol>
        </>
      )}
      {detail.error && <p className="error">Couldn't load this router: {detail.error}</p>}
    </aside>
  );
}
