import { useEffect, useRef, useState } from "react";
import { api, type FleetEvent } from "./api";
import { ROUTINE, clock, describe } from "./format";

const KEEP = 60;

/** Live tail of the controller's event log. Routine per-device chatter is hidden unless asked for. */
export function Activity({ onSelectDevice }: { onSelectDevice: (serial: string) => void }) {
  const [events, setEvents] = useState<FleetEvent[]>([]);
  const [showRoutine, setShowRoutine] = useState(false);
  const after = useRef(0);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        // Skip straight to the recent past on first load.
        if (after.current === 0) {
          const first = await api.events(0);
          if (first.length === 500) {
            let page = first;
            while (page.length === 500 && !cancelled) {
              after.current = page[page.length - 1].id;
              page = await api.events(after.current);
            }
            if (!cancelled) setEvents(page.slice(-KEEP).reverse());
            if (page.length) after.current = page[page.length - 1].id;
          } else if (!cancelled) {
            setEvents(first.slice(-KEEP).reverse());
            if (first.length) after.current = first[first.length - 1].id;
          }
        } else {
          const page = await api.events(after.current);
          if (page.length && !cancelled) {
            after.current = page[page.length - 1].id;
            setEvents((prev) => [...page.reverse(), ...prev].slice(0, 400));
          }
        }
      } catch {
        /* keep the last good list; the header shows connection state */
      }
      if (!cancelled) timer = setTimeout(tick, 1500);
    };
    tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, []);

  const shown = events.filter((e) => showRoutine || !ROUTINE.has(e.kind)).slice(0, KEEP);

  return (
    <section className="activity" aria-labelledby="activity-title">
      <div className="activity-head">
        <h2 id="activity-title">Activity</h2>
        <label className="toggle">
          <input type="checkbox" checked={showRoutine} onChange={(e) => setShowRoutine(e.target.checked)} />
          Show connects and assignments
        </label>
      </div>
      <ol className="feed">
        {shown.map((e) => (
          <li key={e.id} className={`feed-item k-${e.kind.split(".")[0]} ${e.kind.endsWith("failed") || e.kind.endsWith("halted") || e.kind.includes("drift_detected") ? "is-bad" : ""}`}>
            <time dateTime={e.at}>{clock(e.at)}</time>
            {e.serial ? (
              <button type="button" className="linkish" onClick={() => onSelectDevice(e.serial!)}>
                {describe(e)}
              </button>
            ) : (
              <span>{describe(e)}</span>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
