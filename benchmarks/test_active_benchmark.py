"""Fail-closed checks for the active GTCEu benchmark's evidence gates."""
import csv
import tempfile
import unittest
from pathlib import Path

from ldlib_active_benchmark import analyze_stage, summarize, telemetry


class ActiveBenchTests(unittest.TestCase):
    @staticmethod
    def rows(count=64, seconds=20, producing=True, working=True):
        return [
            {
                "tick": i,
                "tick_event_ms": 14.5 + i % 4,
                "detected_machines": count,
                "working_machines": count // 2 if working else 0,
                "output_items": i // 3 if producing else 0,
            }
            for i in range(seconds * 20)
        ]

    def test_valid_real_recipe_progress_and_output(self):
        result = analyze_stage(self.rows(), 64, 20)
        self.assertEqual(result["sampled_server_ticks"], 400)
        self.assertGreater(result["completed_item_delta"], 0)
        self.assertGreater(result["server_tick_interval_p95_ms"], 0)

    def test_no_progress_is_not_a_benchmark(self):
        with self.assertRaisesRegex(RuntimeError, "active recipes"):
            analyze_stage(self.rows(working=False), 64, 20)

    def test_no_completed_output_is_not_a_benchmark(self):
        with self.assertRaisesRegex(RuntimeError, "completed GTCEu recipes"):
            analyze_stage(self.rows(producing=False), 64, 20)

    def test_missing_machines_is_not_a_benchmark(self):
        with self.assertRaisesRegex(RuntimeError, "disappeared"):
            analyze_stage(self.rows(count=63), 64, 20)

    def test_insufficient_ticks_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Too few"):
            analyze_stage(self.rows()[:10], 64, 20)

    def test_real_csv_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "telemetry.csv"
            with file.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=[
                    "tick", "tick_event_ms", "detected_machines",
                    "working_machines", "output_items"
                ])
                writer.writeheader()
                writer.writerows(self.rows()[:3])
            self.assertEqual(len(telemetry(file)), 3)

    def test_summary_labels_are_honest(self):
        def profile(count, cpu, p95):
            return {
                "machines": count,
                "server_cpu_mean_pct_one_core": cpu,
                "server_tick_interval_p95_ms": p95,
                "completed_item_delta": 50,
            }
        runs = {
            "upstream": {"profiles": [profile(64, 25, 19), profile(256, 50, 28)]},
            "fork": {"profiles": [profile(64, 20, 17), profile(256, 45, 23)]},
        }
        text = summarize(runs)
        self.assertIn("Forge server START to END", text)
        self.assertIn("No connected client", text)
        self.assertIn("64", text)


if __name__ == "__main__":
    unittest.main()
