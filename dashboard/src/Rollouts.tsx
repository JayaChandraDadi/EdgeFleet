import { api, type Deployment, type DeploymentStatus } from "./api";
import { usePoll } from "./usePoll";
import { ago } from "./format";

/** Fill order inside a batch, left to right. */
const SEGMENTS: { state: string; cls: string; label: string }[] = [
  { state: "SUCCEEDED", cls: "ok", label: "applied and healthy" },
  { state: "ROLLED_BACK", cls: "rolled", label: "rolled back" },
  { state: "FAILED", cls: "failed", label: "failed health check" },
  { state: "PUSHED", cls: "pushed", label: "applying" },
  { state: "DEFERRED", cls: "deferred", label: "offline, will converge" },
];

function verdict(s: DeploymentStatus): string {
  const d = s.deployment;
  const total = s.batches.reduce((n, b) => n + b.targets, 0);
  const touched = s.batches.reduce(
    (n, b) => n + Object.entries(b.byState).filter(([k]) => k !== "PENDING").reduce((m, [, v]) => m + v, 0),
    0,
  );
  switch (d.state) {
    case "COMPLETED":
      return `Reached all ${total} devices. ${d.template} v${d.templateVersion} is now the group baseline.`;
    case "RUNNING":
      return `Batch ${d.currentBatch + 1} of ${d.batchPercents.length} in progress. ${touched} of ${total} devices so far.`;
    case "PAUSED":
      return `Paused at batch ${d.currentBatch + 1}.`;
    case "ROLLING_BACK":
      return `Halted: ${d.reason}. Restoring the previous config.`;
    case "ROLLED_BACK":
      return `Halted: ${d.reason}. Rolled back; ${total - touched} devices never received it.`;
    default:
      return d.state.toLowerCase();
  }
}

function Wave({ s }: { s: DeploymentStatus }) {
  const total = Math.max(1, s.batches.reduce((n, b) => n + b.targets, 0));
  const W = 1000;
  const GAP = 10;
  const usable = W - GAP * (s.batches.length - 1);
  let x = 0;
  return (
    <div className="wave-wrap">
    <svg className="wave" viewBox={`0 0 ${W} 22`} preserveAspectRatio="none" role="img"
         aria-label={`Rollout progress by batch for ${total} devices`}>
      {s.batches.map((b) => {
        const w = Math.max(6, (b.targets / total) * usable);
        const x0 = x;
        x += w + GAP;
        let fill = x0;
        return (
          <g key={b.batch}>
            <rect className="seg-bed" x={x0} y={0} width={w} height={22} />
            {SEGMENTS.map(({ state, cls }) => {
              const n = b.byState[state] ?? 0;
              if (!n) return null;
              const sw = (n / Math.max(1, b.targets)) * w;
              const r = <rect key={state} className={`seg ${cls}`} x={fill} y={0} width={sw} height={22} />;
              fill += sw;
              return r;
            })}
          </g>
        );
      })}
    </svg>
    <p className="wave-caption">
      Canary of {s.batches[0]?.targets ?? 0}
      {s.deployment.batchPercents.slice(1).map((p) => `, then ${p}%`).join("")} of {total} routers
    </p>
    </div>
  );
}

function RolloutRow({ d, selected, onSelect }: { d: Deployment; selected: boolean; onSelect: () => void }) {
  const status = usePoll(() => api.deployment(d.id), d.state === "COMPLETED" || d.state === "ROLLED_BACK" ? 15000 : 1500, [d.id, d.state]);
  const bad = d.state === "ROLLED_BACK" || d.state === "ROLLING_BACK";
  return (
    <li className={`rollout ${selected ? "is-selected" : ""} ${bad ? "is-bad" : ""}`}>
      <button type="button" className="rollout-head" onClick={onSelect} aria-expanded={selected}>
        <span className="rollout-title">
          {d.template} v{d.templateVersion} to {d.group}
        </span>
        <span className={`pill ${d.state.toLowerCase()}`}>{d.state.replace("_", " ").toLowerCase()}</span>
        <span className="muted rollout-when">{ago(d.createdAt)}</span>
      </button>
      {status.data && (
        <>
          <Wave s={status.data} />
          <p className="verdict">{verdict(status.data)}</p>
        </>
      )}
      {selected && <Diff d={d} />}
    </li>
  );
}

function Diff({ d }: { d: Deployment }) {
  const diff = usePoll(
    d.templateVersion > 1 ? () => api.templateDiff(d.template, d.templateVersion - 1, d.templateVersion) : null,
    60000,
    [d.id],
  );
  if (d.templateVersion <= 1) return <p className="muted">First version of this template.</p>;
  return (
    <div className="diff">
      <p className="diff-title">
        Changes from {d.template} v{d.templateVersion - 1}
      </p>
      <pre>
        {(diff.data ?? []).map((line) => (
          <span key={line} className={`diff-line ${line[0] === "+" ? "add" : line[0] === "-" ? "del" : "chg"}`}>
            {line}
            {"\n"}
          </span>
        ))}
      </pre>
    </div>
  );
}

export function Rollouts({ selected, onSelect }: { selected: string | null; onSelect: (id: string | null) => void }) {
  const list = usePoll(api.deployments, 2000);
  return (
    <section className="rollouts" aria-labelledby="rollouts-title">
      <h2 id="rollouts-title">Rollouts</h2>
      <ul className="key" aria-label="Rollout key">
        {SEGMENTS.map((s) => (
          <li key={s.state}>
            <span className={`chip ${s.cls}`} aria-hidden />
            {s.label}
          </li>
        ))}
        <li>
          <span className="chip pending" aria-hidden />
          not reached
        </li>
      </ul>
      {list.data && list.data.length === 0 && (
        <p className="muted">No rollouts yet. Create one with POST /api/deployments.</p>
      )}
      <ol className="rollout-list">
        {list.data?.map((d) => (
          <RolloutRow key={d.id} d={d} selected={selected === d.id} onSelect={() => onSelect(selected === d.id ? null : d.id)} />
        ))}
      </ol>
    </section>
  );
}
