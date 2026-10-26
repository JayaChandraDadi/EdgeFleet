#!/usr/bin/env python3
"""Generates a provisioning file: N branch routers across groups and regions.

usage: make_inventory.py --devices 500 --group branches > inventory.json
       curl -s -X POST localhost:8090/api/inventory -H 'Content-Type: application/json' \
            --data @inventory.json > tokens.json
"""
import argparse
import json

REGIONS = ["us-west", "us-east", "eu-west"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--devices", type=int, default=500)
    ap.add_argument("--group", default="branches")
    ap.add_argument("--model", default="C8300")
    ap.add_argument("--start", type=int, default=0, help="offset for serials, IPs and site ids")
    a = ap.parse_args()

    devices = []
    for i in range(a.start, a.start + a.devices):
        devices.append({
            "serial": f"FGL{2300000 + i:07d}",
            "model": a.model,
            "group": a.group,
            "hostname": f"branch-{100 + i}",
            "systemIp": f"10.1.{i // 250}.{i % 250 + 1}",
            "siteId": 100 + i,
            "region": REGIONS[i % 10 // 4] if i % 10 < 8 else "eu-west",
            "variables": {"mpls_if": "GigabitEthernet0/0/0", "inet_if": "GigabitEthernet0/0/1"},
        })
    print(json.dumps(devices, indent=1))


if __name__ == "__main__":
    main()
