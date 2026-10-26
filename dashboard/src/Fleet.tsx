import { useMemo, useState } from "react";
import { STATE_LABEL, configState, type ConfigState, type Device } from "./api";

const REGION_ORDER = ["us-west", "us-east", "eu-west"];
export const STATE_ORDER: ConfigState[] = ["in-sync", "behind", "drifted", "unhealthy", "offline", "waiting", "retired"];

type Props = { devices: Device[]; selected: string | null; onSelect: (serial: string) => void };

export function Fleet({ devices, selected, onSelect }: Props) {
  const [hover, setHover] = useState<Device | null>(null);
  const regions = useMemo(() => {
    const m = new Map<string, Device[]>();
    for (const d of devices) m.set(d.region, [...(m.get(d.region) ?? []), d]);
    const rank = (r: string) => (REGION_ORDER.includes(r) ? REGION_ORDER.indexOf(r) : REGION_ORDER.length);
    return [...m.entries()].sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b));
  }, [devices]);
  const counts = useMemo(() => {
    const c: Partial<Record<ConfigState, number>> = {};
    for (const d of devices) c[configState(d)] = (c[configState(d)] ?? 0) + 1;
    return c;
  }, [devices]);

  return (
    <section className="fleet" aria-labelledby="fleet-title">
      <h2 id="fleet-title">Routers</h2>
      <ul className="key" aria-label="Router key">
        {STATE_ORDER.filter((s) => counts[s]).map((s) => (
          <li key={s}>
            <span className={`chip ${s}`} aria-hidden />
            {STATE_LABEL[s]} <strong>{counts[s]}</strong>
          </li>
        ))}
      </ul>
      {regions.map(([region, list]) => (
        <div key={region} className="region-block">
          <h3>
            {region} <span className="muted">{list.length}</span>
          </h3>
          <div className="grid" role="list">
            {list.map((d) => (
              <button
                key={d.serial}
                type="button"
                role="listitem"
                className={`cell ${configState(d)} ${selected === d.serial ? "is-selected" : ""}`}
                aria-label={`${d.hostname}: ${STATE_LABEL[configState(d)]}`}
                onClick={() => onSelect(d.serial)}
                onMouseEnter={() => setHover(d)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(d)}
                onBlur={() => setHover(null)}
              />
            ))}
          </div>
        </div>
      ))}
      <p className="hover-line" aria-live="polite">
        {hover
          ? `${hover.hostname} (${hover.serial}), ${hover.systemIp}: ${STATE_LABEL[configState(hover)]}, config #${hover.appliedVersion}`
          : "Point at a router to identify it; select one for its history."}
      </p>
    </section>
  );
}
