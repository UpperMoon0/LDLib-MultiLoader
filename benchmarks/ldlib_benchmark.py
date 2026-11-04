#!/usr/bin/env python3
"""Headless, informational LDLib A/B benchmark against real Forge + GTCEu.

Measures Linux CPU time from /proc for the whole server JVM and LDLib async
threads. Both variants use a fresh copy of an identical Forge/GTCEu template,
a fixed world seed, and the same idle-machine block workloads.

This is an *idle managed-sync scanning* workload, not a GTCEu recipe throughput
benchmark. CI runner scheduling and the non-atomic collection-snapshot behavior
are not controlled by this script. Never use a single run as a release gate.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time


PROFILES = [(64, 4), (256, 8), (1024, 16)]
STARTUP_TIMEOUT_SECONDS = 300
READY_TIMEOUT_SECONDS = 90


def parse_stat(stat: str) -> int:
    """Return utime+stime jiffies; handle spaces and ')' in comm safely."""
    end = stat.rfind(") ")
    if end < 0:
        raise ValueError("Malformed Linux /proc stat")
    fields = stat[end + 2 :].split()  # starts at field 3, process state
    return int(fields[11]) + int(fields[12])


def read_jiffies(path: Path) -> int | None:
    try:
        return parse_stat(path.read_text())
    except (OSError, ValueError, IndexError):
        return None


def process_children(pid: int) -> list[int]:
    try:
        raw = Path(f"/proc/{pid}/task/{pid}/children").read_text()
    except OSError:
        return []
    return [int(p) for p in raw.split()]


def java_pid(launcher: subprocess.Popen) -> int:
    """Track the actual JVM, not a shell wrapper around Forge run.sh."""
    pending = [launcher.pid]
    seen = set()
    while pending:
        pid = pending.pop()
        if pid in seen:
            continue
        seen.add(pid)
        try:
            command = Path(f"/proc/{pid}/comm").read_text().strip().lower()
        except OSError:
            continue
        if command == "java":
            return pid
        pending.extend(process_children(pid))
    raise RuntimeError("Cannot locate Forge Java process under run.sh")


def cpu_snapshot(pid: int) -> dict:
    ticks = os.sysconf("SC_CLK_TCK")
    process = read_jiffies(Path(f"/proc/{pid}/stat"))
    if process is None:
        raise RuntimeError(f"Server JVM {pid} exited during sampling")

    ldlib = 0
    found_ldlib = False
    for thread in Path(f"/proc/{pid}/task").iterdir():
        try:
            # Linux comm is truncated to TASK_COMM_LEN=16 (15 visible chars).
            name = (thread / "comm").read_text().strip().lower()
            if not name.startswith("ldlib async"):
                continue
            stat = read_jiffies(thread / "stat")
        except OSError:
            continue
        if stat is not None:
            found_ldlib = True
            ldlib += stat

    rss_kib = None
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                rss_kib = int(line.split()[1])
                break
    except (OSError, ValueError):
        pass
    return {
        "time": time.monotonic(),
        "process_seconds": process / ticks,
        "ldlib_seconds": ldlib / ticks,
        "ldlib_seen": found_ldlib,
        "rss_mib": round(rss_kib / 1024, 2) if rss_kib is not None else None,
    }


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        raise ValueError("No samples")
    sorted_values = sorted(values)
    rank = max(0, math.ceil(len(sorted_values) * fraction) - 1)
    return sorted_values[rank]


def sample_cpu(pid: int, duration_seconds: int) -> dict:
    samples = []
    previous = cpu_snapshot(pid)
    saw_ldlib = previous["ldlib_seen"]
    for _ in range(duration_seconds):
        time.sleep(1)
        current = cpu_snapshot(pid)
        seconds = current["time"] - previous["time"]
        if seconds <= 0:
            raise RuntimeError("Non-positive sample duration")
        saw_ldlib |= current["ldlib_seen"]
        total_delta = current["process_seconds"] - previous["process_seconds"]
        ldlib_delta = current["ldlib_seconds"] - previous["ldlib_seconds"]
        if total_delta < 0 or ldlib_delta < 0:
            raise RuntimeError("CPU counters decreased: process/thread restarted")
        samples.append({
            "server_cpu_pct_one_core": round(total_delta / seconds * 100, 3),
            "ldlib_async_cpu_pct_one_core": round(ldlib_delta / seconds * 100, 3),
            "rss_mib": current["rss_mib"],
        })
        previous = current
    if not saw_ldlib:
        raise RuntimeError("No LDLib async thread observed; benchmark would be misleading")
    totals = [s["server_cpu_pct_one_core"] for s in samples]
    ldlib = [s["ldlib_async_cpu_pct_one_core"] for s in samples]
    return {
        "sample_count": len(samples),
        "server_cpu_mean_pct_one_core": round(statistics.mean(totals), 3),
        "server_cpu_p95_pct_one_core": round(percentile(totals, 0.95), 3),
        "ldlib_async_cpu_mean_pct_one_core": round(statistics.mean(ldlib), 3),
        "ldlib_async_cpu_p95_pct_one_core": round(percentile(ldlib, 0.95), 3),
        "rss_last_mib": previous["rss_mib"],
        "samples": samples,
    }


def send_command(launcher: subprocess.Popen, command: str) -> None:
    if launcher.poll() is not None:
        raise RuntimeError(f"Server exited with status {launcher.returncode}")
    assert launcher.stdin is not None
    launcher.stdin.write(command + "\n")
    launcher.stdin.flush()


def wait_log(path: Path, needle: str, launcher: subprocess.Popen, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if launcher.poll() is not None:
            raise RuntimeError(f"Server exited ({launcher.returncode}) while waiting for {needle}")
        if path.exists() and needle in path.read_text(errors="replace"):
            return
        time.sleep(1)
    raise TimeoutError(f"No {needle!r} in server log within {timeout}s; see logs artifact")


def run_variant(
    name: str, jar: Path, template: Path, output: Path,
    warmup: int, duration: int,
) -> dict:
    work = output / name
    shutil.copytree(template, work)
    (work / "mods" / jar.name).write_bytes(jar.read_bytes())
    (work / "server.properties").write_text(
        "level-seed=393931\n"
        "level-name=world\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "spawn-protection=0\n"
        "max-tick-time=180000\n"
        "motd=LDLib isolated benchmark\n"
    )
    console_path = work / "benchmark-console.log"
    entries = []
    with console_path.open("w", encoding="utf-8") as console:
        launcher = subprocess.Popen(
            ["bash", "./run.sh", "nogui"],
            cwd=work,
            stdin=subprocess.PIPE,
            stdout=console,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        try:
            server_log = work / "logs" / "latest.log"
            wait_log(server_log, "Done (", launcher, STARTUP_TIMEOUT_SECONDS)
            pid = java_pid(launcher)
            send_command(launcher, "forceload add -16 -16 15 15")
            # Forge schedules chunk loading asynchronously: give the fixed
            # benchmark region time to finish loading before the first fill.
            time.sleep(5)
            for count, half_width in PROFILES:
                coord_min, coord_max = -half_width, half_width - 1
                send_command(
                    launcher,
                    f"fill {coord_min} 80 {coord_min} {coord_max} 80 {coord_max} "
                    "gtceu:lv_macerator",
                )
                marker = f"LDLIB_BENCH_{name}_{count}_READY"
                send_command(
                    launcher,
                    f"execute if block 0 80 0 gtceu:lv_macerator run say {marker}",
                )
                wait_log(server_log, marker, launcher, READY_TIMEOUT_SECONDS)
                time.sleep(warmup)
                # Query Forge TPS for an audit trail, but don't parse an
                # undocumented/locale-dependent command output as MSPT.
                send_command(launcher, "forge tps")
                metrics = sample_cpu(pid, duration)
                entries.append({"machines": count, **metrics})
                print(
                    f"{name} / {count}: server {metrics['server_cpu_mean_pct_one_core']:.2f}% "
                    f"LDLib async {metrics['ldlib_async_cpu_mean_pct_one_core']:.2f}% "
                    "of one core", flush=True,
                )
        finally:
            if launcher.poll() is None:
                try:
                    send_command(launcher, "stop")
                    launcher.wait(timeout=45)
                except (OSError, subprocess.TimeoutExpired, RuntimeError):
                    launcher.kill()
                    launcher.wait(timeout=15)
            if launcher.stdin:
                launcher.stdin.close()

    log = (work / "logs" / "latest.log").read_text(errors="replace")
    return {
        "name": name,
        "jar": jar.name,
        "profiles": entries,
        "async_error_count": log.count("asyncThreadLogic error"),
        "concurrent_modification_count": log.count("ConcurrentModificationException"),
    }


def reduction(before: float, after: float) -> str:
    if before < 0.25:
        return "n/a (baseline too small)"
    return f"{(1 - after / before) * 100:+.1f}%"


def summarize(runs: dict, warmup: int, duration: int) -> str:
    old, new = runs["upstream"]["profiles"], runs["fork"]["profiles"]
    rows = [
        "# LDLib A/B benchmark — informational only",
        "",
        "Both variants were run on **one GitHub-hosted Linux runner** with a fresh "
        "fixed-seed Forge 1.20.1 / GTCEu 7.5.3 world per variant.",
        f"Per-machine-count phase: {warmup}s warmup, {duration}s sampling.",
        "CPU percentages are **percent of one logical core**, not whole-machine "
        "utilization. A negative reduction means the fork used *more* CPU.",
        "",
        "| Idle macerators | Upstream LDLib async CPU % | Fork LDLib async CPU % "
        "| Reduction | Upstream JVM CPU % | Fork JVM CPU % | Reduction |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for a, b in zip(old, new):
        if a["machines"] != b["machines"]:
            raise RuntimeError("Mismatched benchmark profiles")
        la, lb = a["ldlib_async_cpu_mean_pct_one_core"], b["ldlib_async_cpu_mean_pct_one_core"]
        ta, tb = a["server_cpu_mean_pct_one_core"], b["server_cpu_mean_pct_one_core"]
        rows.append(
            f"| {a['machines']} | {la:.2f} | {lb:.2f} | {reduction(la, lb)} "
            f"| {ta:.2f} | {tb:.2f} | {reduction(ta, tb)} |"
        )
    rows += [
        "",
        f"Upstream async errors: {runs['upstream']['async_error_count']}; "
        f"fork async errors: {runs['fork']['async_error_count']}.",
        "",
        "**Scope / caveats:** idle GTCEu macerators test real LDLib asynchronous "
        "managed-field scanning, not active recipes, client networking, packet "
        "throughput, real TFG modpack behavior or gameplay MSPT. A single paired "
        "run is sensitive to shared-runner noise, JIT/GC and run order. Repeat "
        "and verify with JFR in a realistic TFG world before claiming a stable "
        "speedup. The baseline uses our pristine upstream-tracking branch at "
        "a pinned SHA, not a newly fetched upstream release.",
        "",
    ]
    return "\n".join(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True, type=Path)
    parser.add_argument("--upstream-jar", required=True, type=Path)
    parser.add_argument("--fork-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--warmup", type=int, default=20)
    parser.add_argument("--duration", type=int, default=40)
    parser.add_argument("--order", choices=("upstream-first", "fork-first"), default="upstream-first")
    args = parser.parse_args()
    if args.warmup < 5 or args.duration < 10:
        parser.error("Too few samples/warmup for a useful measurement")
    args.output.mkdir(parents=True, exist_ok=True)
    order = ["upstream", "fork"] if args.order == "upstream-first" else ["fork", "upstream"]
    jars = {"upstream": args.upstream_jar, "fork": args.fork_jar}
    results = {}
    try:
        for name in order:
            results[name] = run_variant(
                name, jars[name].resolve(), args.template.resolve(),
                args.output.resolve(), args.warmup, args.duration,
            )
            (args.output / "measurements.json").write_text(
                json.dumps({"run_order": order, "variants": results}, indent=2) + "\n"
            )
        summary = summarize(results, args.warmup, args.duration)
        (args.output / "SUMMARY.md").write_text(summary + "\n")
        print(summary, flush=True)
    except Exception:
        # Keep console and completed variant data for diagnosis.
        (args.output / "incomplete.txt").write_text(
            "Benchmark did not finish; no A/B performance claim is possible.\n"
        )
        raise


if __name__ == "__main__":
    main()
