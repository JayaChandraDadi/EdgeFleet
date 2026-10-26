import { useEffect, useRef, useState } from "react";

export type Poll<T> = { data: T | undefined; error: string | undefined; updatedAt: number | undefined };

/** Calls `load` now and every `intervalMs`, keeping the last good result through errors. */
export function usePoll<T>(load: (() => Promise<T>) | null, intervalMs: number, deps: unknown[] = []): Poll<T> {
  const [state, setState] = useState<Poll<T>>({ data: undefined, error: undefined, updatedAt: undefined });
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (!loadRef.current) {
      setState({ data: undefined, error: undefined, updatedAt: undefined });
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const data = await loadRef.current!();
        if (!cancelled) setState({ data, error: undefined, updatedAt: Date.now() });
      } catch (e) {
        if (!cancelled) setState((s) => ({ ...s, error: e instanceof Error ? e.message : String(e) }));
      }
      if (!cancelled) timer = setTimeout(tick, intervalMs);
    };
    tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, ...deps]);

  return state;
}
