#!/usr/bin/env bash
# End-to-end scenario with measurements, no Docker needed:
#   onboarding -> staged rollout -> bad config halted at the canary -> drift
#   remediation -> controller SIGKILLed mid-rollout and restarted.
# Results go to <run-dir>/results.json.
#
#   mvn -q install -DskipTests && ./scripts/e2e.sh [devices] [run-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
N="${1:-500}"
RUN="${2:-$ROOT/.e2e}"
API=http://localhost:8090/api
rm -rf "$RUN" && mkdir -p "$RUN/bin"
cp "$ROOT/controller/target/controller-0.1.0.jar" "$ROOT/device-sim/target/device-sim-0.1.0-exec.jar" "$RUN/bin/"
cd "$RUN"

now() { python3 -c 'import time; print(time.time())'; }
since() { python3 -c "import time; print(round(time.time() - $1, 1))"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
state() { curl -s "$API/deployments/$1" | json 'd["deployment"]["state"]'; }
summary() { curl -s "$API/fleet/summary"; }
in_sync_all() { summary | python3 -c "import json,sys; s=json.load(sys.stdin)['onboarded']; sys.exit(0 if s['inSync']==$N and s['connected']==$N else 1)"; }
wait_for() { local t=$1; shift; for _ in $(seq 1 "$t"); do "$@" && return 0; sleep 0.5; done; echo "timed out: $*" >&2; return 1; }
deployment_is() { [ "$(state "$1")" = "$2" ]; }
template() { # name-suffix, python edit of the body
  python3 - "$ROOT/examples/branch-template.json" "$1" "$2" > "t-$1.json" <<'PY'
import json, sys
t = json.load(open(sys.argv[1])); body = t["body"]; exec(sys.argv[3]); t["comment"] = sys.argv[2]
print(json.dumps(t))
PY
  curl -s -X POST "$API/templates" -H 'Content-Type: application/json' --data @"t-$1.json" | json 'd["version"]'
}
deploy() { # version, batches
  curl -s -X POST "$API/deployments" -H 'Content-Type: application/json' \
    -d "{\"template\":\"branch\",\"version\":$1,\"group\":\"branches\",\"batchPercents\":$2,\"maxFailurePct\":5,\"healthTimeoutSec\":30}" \
    | json 'd["id"]'
}
start_controller() {
  PKI_DIR="$RUN/pki" EMBEDDED_PG_DIR="$RUN/pgdata" nohup java -Xmx1g -jar "$RUN/bin/controller-0.1.0.jar" \
    >> "$RUN/controller.log" 2>&1 &
  echo $! > "$RUN/controller.pid"
  wait_for 240 curl -sf http://localhost:8090/actuator/health -o /dev/null
}

echo "== controller"
start_controller
v1=$(template baseline 'pass')
curl -s -X PUT "$API/groups/branches/baseline" -H 'Content-Type: application/json' -d "{\"template\":\"branch\",\"version\":$v1}" >/dev/null
python3 "$ROOT/tools/make_inventory.py" --devices "$N" > inventory.json
curl -s -X POST "$API/inventory" -H 'Content-Type: application/json' --data @inventory.json > tokens.json
curl -s "$API/pki/ca.crt" > ca.crt

echo "== onboarding $N devices"
t0=$(now)
nohup java -Xmx1g -jar bin/device-sim-0.1.0-exec.jar --ca ca.crt --tokens tokens.json --duration-sec 3600 \
  --heartbeat-ms 5000 --drift 20 --drift-after-sec 120 > sim.log 2>&1 &
echo $! > sim.pid
wait_for 600 in_sync_all
onboard_s=$(since "$t0")
claim=$(grep -o 'claim p50 [0-9]*ms p99 [0-9]*ms' sim.log)
echo "   all $N in sync on day-0 config after ${onboard_s}s ($claim)"

echo "== staged rollout"
v2=$(template v2 'body["bfd"]["multiplier"]=5; body["ntp"]=["10.0.0.10","10.0.0.12"]')
t=$(now); dep=$(deploy "$v2" '[5,25,100]'); wait_for 600 deployment_is "$dep" COMPLETED
rollout_s=$(since "$t"); echo "   $N devices on v$v2 in ${rollout_s}s"

echo "== bad config"
v3=$(template v3-bad 'body["bfd"]["multiplier"]=5; body["ntp"]=["10.0.0.10","10.0.0.12"]; body["transports"][1]["mtu"]=1200')
t=$(now); bad=$(deploy "$v3" '[5,25,100]'); wait_for 600 deployment_is "$bad" ROLLED_BACK
rollback_s=$(since "$t")
touched=$(curl -s "$API/deployments/$bad" | json 'sum(n for b in d["batches"] for s,n in b["byState"].items() if s!="PENDING")')
reason=$(curl -s "$API/deployments/$bad" | json 'd["deployment"]["reason"]')
wait_for 120 in_sync_all
echo "   halted and rolled back in ${rollback_s}s; $touched of $N devices touched ($reason)"

echo "== drift"
wait_for 600 grep -q "hand-edited" sim.log
edit_ms=$(grep -o 'hand-edited [0-9]* devices at [0-9]*' sim.log | awk '{print $NF}')
wait_for 120 in_sync_all
drift=$(python3 - "$API" "$edit_ms" <<'PY'
import json, sys, urllib.request
from datetime import datetime
api, edit = sys.argv[1], int(sys.argv[2]) / 1000
ev, after = [], 0
while True:
    page = json.load(urllib.request.urlopen(f"{api}/events?afterId={after}&limit=2000"))
    if not page: break
    ev += page; after = page[-1]["id"]
ts = lambda e: datetime.fromisoformat(e["at"].replace("Z", "+00:00")).timestamp()
det = sorted(ts(e) - edit for e in ev if e["kind"] == "config.drift_detected" and ts(e) >= edit)
clr = sorted(ts(e) - edit for e in ev if e["kind"] == "config.drift_cleared" and ts(e) >= edit)
p = lambda xs, q: round(xs[min(len(xs) - 1, int(q * len(xs)))], 2) if xs else None
print(json.dumps({"edited": 20, "detected": len(det), "remediated": len(clr),
                  "detectP50s": p(det, .5), "detectMaxS": p(det, 1), "inSyncAgainP50s": p(clr, .5),
                  "inSyncAgainMaxS": p(clr, 1)}))
PY
)
echo "   $drift"

echo "== controller killed mid-rollout"
v4=$(template v4 'body["bfd"]["multiplier"]=5; body["ntp"]=["10.0.0.10","10.0.0.12","10.0.0.13"]')
dep4=$(deploy "$v4" '[2,10,40,100]')
wait_for 600 sh -c "curl -s $API/deployments/$dep4 | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)[\"deployment\"][\"currentBatch\"]>=2 else 1)'"
before_attempts=$(grep -o 'reconnect attempts [0-9]*' sim.log | tail -1 | awk '{print $3}')
kill -9 "$(cat controller.pid)"
tk=$(now)
sleep 20
start_controller
up_s=$(since "$tk")
wait_for 600 deployment_is "$dep4" COMPLETED
done_s=$(since "$tk")
wait_for 300 in_sync_all
resync_s=$(since "$tk")
sleep 2
after_attempts=$(grep -o 'reconnect attempts [0-9]*' sim.log | tail -1 | awk '{print $3}')
attempts=$((after_attempts - before_attempts))
echo "   controller down 20s; serving ${up_s}s after kill; rollout completed ${done_s}s after kill;"
echo "   all $N reconnected and in sync ${resync_s}s after kill; $attempts reconnect attempts"

python3 - <<PY > results.json
import json
print(json.dumps({
  "devices": $N,
  "onboarding": {"allInSyncSeconds": $onboard_s, "claims": "$claim"},
  "rollout": {"batches": [5, 25, 100], "completedSeconds": $rollout_s},
  "badConfig": {"haltedAndRolledBackSeconds": $rollback_s, "devicesTouched": $touched, "reason": "$reason"},
  "drift": $drift,
  "controllerKill": {"downSeconds": 20, "servingAfterKillSeconds": $up_s, "rolloutCompletedAfterKillSeconds": $done_s,
                     "allResyncedAfterKillSeconds": $resync_s, "reconnectAttempts": $attempts}
}, indent=2))
PY
cat results.json
kill "$(cat sim.pid)" "$(cat controller.pid)" 2>/dev/null || true
