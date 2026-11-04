#!/usr/bin/env python3
"""A/B active GTCEu recipe load with JFR, CPU and Forge tick-event durations.

Uses the *same* CI-only subscriber injected into each LDLib JAR at build time.
The subscriber refills actual GTCEu recipe IO on the server thread. It records
raw tick-event durations (NOT full-server MSPT) and observed recipe progress.
No connected Minecraft client is used; do not infer client packet performance.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import time

from ldlib_benchmark import (
    PROFILES, STARTUP_TIMEOUT_SECONDS, java_pid, percentile, reduction,
    sample_cpu, send_command, wait_log,
)

READY_TIMEOUT_SECONDS = 125
MIN_TICKS_PER_SECOND = 8.0


def telemetry(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as file:
        return [
            {
                "tick": int(row["tick"]),
                "tick_event_ms": float(row["tick_event_ms"]),
                "detected_machines": int(row["detected_machines"]),
                "working_machines": int(row["working_machines"]),
                "output_items": int(row["output_items"]),
            }
            for row in csv.DictReader(file)
        ]


def wait_working(
    path: Path, launcher: subprocess.Popen, expected: int, timeout: int,
) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if launcher.poll() is not None:
            raise RuntimeError(f"Forge exited {launcher.returncode} before recipe activation")
        readings = telemetry(path)
        if readings:
            last = readings[-1]
            if last["detected_machines"] >= expected and (
                last["working_machines"] >= max(1, expected // 4)
            ):
                return last
        time.sleep(2)
    last = telemetry(path)[-1:] or []
    raise TimeoutError(
        f"Only {last!r} observed; expected {expected} GTCEu machines and "
        "at least 25% actually processing recipes. No benchmark claim allowed."
    )


def analyze_stage(samples: list[dict], expected: int, duration: int) -> dict:
    """Treat actual recipe outputs and activity as hard validity gates."""
    if len(samples) < int(duration * MIN_TICKS_PER_SECOND):
        raise RuntimeError(f"Too few recorded server ticks: {len(samples)}")
    relevant = [
        r for r in samples if r["detected_machines"] >= expected
    ]
    if len(relevant) != len(samples):
        raise RuntimeError("Machine fixture disappeared during timed phase")
    working = [r["working_machines"] for r in samples]
    if max(working) < max(1, expected // 4):
        raise RuntimeError("Timed phase did not exercise enough active recipes")
    output_before, output_after = samples[0]["output_items"], samples[-1]["output_items"]
    # New recipe load must produce actual GTCEu output items, not just update
    # arbitrary fields or send synthetic sync packets.
    if output_after <= output_before:
        raise RuntimeError(
            f"No genuine completed GTCEu recipes: outputs {output_before} -> "
            f"{output_after} (possible missing recipe or stalled machines)"
        )
    ms = [r["tick_event_ms"] for r in samples]
    return {
        "server_tick_interval_p50_ms": round(statistics.median(ms), 3),
        "server_tick_interval_p95_ms": round(percentile(ms, 0.95), 3),
        "server_tick_interval_max_ms": round(max(ms), 3),
        "server_tick_interval_mean_ms": round(statistics.mean(ms), 3),
        "sampled_server_ticks": len(ms),
        "working_machines_p50": round(statistics.median(working), 1),
        "completed_item_delta": output_after - output_before,
    }


def run_variant(
    name: str, jar: Path, template: Path, output: Path,
    warmup: int, duration: int,
) -> dict:
    world = output / name
    shutil.copytree(template, world)
    shutil.copy2(jar, world / "mods" / jar.name)
    (world / "server.properties").write_text(
        "level-seed=393931\n"
        "level-type=minecraft:flat\n"
        "level-name=world\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "spawn-protection=0\n"
        "max-tick-time=180000\n"
        "motd=LDLib real GTCEu benchmark\n",
        encoding="utf-8",
    )
    args_file = world / "user_jvm_args.txt"
    args_file.write_text(
        args_file.read_text()
        + "\n-Dldlib.bench.active=true\n"
        + "-XX:StartFlightRecording=filename=ldlib-active-flight.jfr,"
        + "settings=profile,dumponexit=true,maxsize=128m\n"
    )
    csv_file = world / "ldlib-active-metrics.csv"
    stages = []
    with (world / "benchmark-console.log").open("w", encoding="utf-8") as console:
        launcher = subprocess.Popen(
            ["bash", "./run.sh", "nogui"],
            cwd=world,
            stdin=subprocess.PIPE,
            stdout=console,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        try:
            wait_log(world / "logs" / "latest.log", "Done (", launcher, STARTUP_TIMEOUT_SECONDS)
            pid = java_pid(launcher)
            send_command(launcher, "forceload add -16 -16 15 15")
            time.sleep(5)
            for count, half in PROFILES:
                lo, hi = -half, half - 1
                send_command(
                    launcher,
                    f"fill {lo} 80 {lo} {hi} 80 {hi} gtceu:lv_macerator",
                )
                wait_working(csv_file, launcher, count, READY_TIMEOUT_SECONDS)
                time.sleep(warmup)
                before = len(telemetry(csv_file))
                cpu = sample_cpu(pid, duration)
                active_rows = telemetry(csv_file)[before:]
                tick = analyze_stage(active_rows, count, duration)
                stages.append({"machines": count, **cpu, **tick})
                print(
                    f"{name}/{count}: {tick['sampled_server_ticks']} ticks; "
                    f"{tick['working_machines_p50']:.0f} working; "
                    f"{tick['completed_item_delta']} output items; "
                    f"tick-event p95={tick['server_tick_interval_p95_ms']:.2f}ms; "
                    f"JVM CPU={cpu['server_cpu_mean_pct_one_core']:.2f}%",
                    flush=True,
                )
        finally:
            if launcher.poll() is None:
                try:
                    send_command(launcher, "stop")
                    launcher.wait(timeout=65)
                except (OSError, subprocess.TimeoutExpired, RuntimeError):
                    launcher.kill()
                    launcher.wait(timeout=15)
            if launcher.stdin is not None:
                launcher.stdin.close()
    log = (world / "logs" / "latest.log").read_text(errors="replace")
    jfr = world / "ldlib-active-flight.jfr"
    if not jfr.is_file() or jfr.stat().st_size < 1024:
        raise RuntimeError(f"{name} JFR missing/empty; no performance certification")
    if "GTCEU_ACTIVE_BENCHMARK_FATAL" in log:
        raise RuntimeError(f"{name} GTCEu bridge reported a fatal error")
    return {
        "name": name,
        "jar": jar.name,
        "jfr_size_bytes": jfr.stat().st_size,
        "async_error_count": log.count("asyncThreadLogic error"),
        "concurrent_modification_count": log.count("ConcurrentModificationException"),
        "profiles": stages,
    }


def summarize(results: dict) -> str:
    upstream, fork = results["upstream"]["profiles"], results["fork"]["profiles"]
    lines = [
        "# LDLib active GTCEu benchmark (informational)",
        "",
        "Real GTCEu 7.5.3 LV macerators were supplied with copper ingots and EU "
        "by the **identical CI-only Forge subscriber** in each comparison build. "
        "The gate verifies recipe progress and completed item output.",
        "",
        "| Machines | Upstream tick-event P95 ms | Fork tick-event P95 ms "
        "| Reduction | Upstream JVM CPU % | Fork JVM CPU % | Reduction "
        "| Upstream items | Fork items |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for a, b in zip(upstream, fork):
        if a["machines"] != b["machines"]:
            raise RuntimeError("Cannot compare mismatched machine workloads")
        count = a["machines"]
        ta, tb = a["server_tick_interval_p95_ms"], b["server_tick_interval_p95_ms"]
        ca, cb = a["server_cpu_mean_pct_one_core"], b["server_cpu_mean_pct_one_core"]
        lines.append(
            f"| {count} | {ta:.3f} | {tb:.3f} | {reduction(ta, tb)} "
            f"| {ca:.2f} | {cb:.2f} | {reduction(ca, cb)} "
            f"| {a['completed_item_delta']} | {b['completed_item_delta']} |"
        )
    lines.extend([
        "",
        "**Important:** tick-event timing spans Forge server START to END events; "
        "it does **not** cover every phase of the full Minecraft server tick "
        "and must not be labeled whole-server MSPT. CPU is expressed as % of "
        "one logical core. JFR files contain the per-variant profiling detail.",
        "No connected client, client GUI, or modded network packet acceptance "
        "is exercised here. Real production TFG requires separate paired "
        "client/server and fixed-world tests; no production world was touched.",
        "Shared GitHub runner, sequential versions, JIT/GC and load-order "
        "effects remain. Repeat and compare distributions before claiming "
        "a stable throughput or latency advantage.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True, type=Path)
    parser.add_argument("--upstream-jar", required=True, type=Path)
    parser.add_argument("--fork-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--warmup", type=int, default=12)
    parser.add_argument("--duration", type=int, default=35)
    parser.add_argument("--order", choices=("upstream-first", "fork-first"), default="upstream-first")
    args = parser.parse_args()
    if args.warmup < 5 or args.duration < 15:
        parser.error("Benchmark needs at least 5 seconds warmup and 15 seconds data")
    args.output.mkdir(parents=True, exist_ok=True)
    order = ["upstream", "fork"] if args.order == "upstream-first" else ["fork", "upstream"]
    jars = {"upstream": args.upstream_jar.resolve(), "fork": args.fork_jar.resolve()}
    results: dict = {}
    try:
        for name in order:
            results[name] = run_variant(
                name, jars[name], args.template.resolve(), args.output.resolve(),
                args.warmup, args.duration,
            )
            (args.output / "active-measurements.json").write_text(json.dumps({
                "mode": "gtceu_active_recipe_ci_only",
                "run_order": order,
                "variants": results,
            }, indent=2) + "\n")
        summary = summarize(results)
        (args.output / "ACTIVE_SUMMARY.md").write_text(summary + "\n")
        print(summary)
    except Exception:
        (args.output / "active-incomplete.txt").write_text(
            "Incomplete A/B comparison. Inspect console, telemetry and JVM JFR; "
            "do not publish a performance claim.\n"
        )
        raise


if __name__ == "__main__":
    main()
