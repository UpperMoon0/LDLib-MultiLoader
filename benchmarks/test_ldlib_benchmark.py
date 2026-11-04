"""Checks that benchmark math and /proc parsing don't silently misreport speedups."""
import unittest

from ldlib_benchmark import parse_stat, percentile, reduction, summarize


class CpuBenchTest(unittest.TestCase):
    def test_stat_with_parentheses_in_thread_name(self):
        fields = ["S"] + ["0"] * 10 + ["35", "7"] + ["0"] * 30
        self.assertEqual(parse_stat("123 (LDLib (Async) Thread) " + " ".join(fields)), 42)

    def test_bad_proc_data_rejected(self):
        with self.assertRaises(ValueError):
            parse_stat("123 no parenthesized name")

    def test_nearest_rank_p95(self):
        self.assertEqual(percentile(list(range(1, 21)), 0.95), 19)
        self.assertEqual(percentile([8.0], 0.95), 8.0)

    def test_relative_delta_and_low_baseline(self):
        self.assertEqual(reduction(10, 8), "+20.0%")
        self.assertEqual(reduction(10, 12), "-20.0%")
        self.assertEqual(reduction(0.1, 0), "n/a (baseline too small)")

    def test_summary_pairs_by_machine_count(self):
        a = {
            "profiles": [
                {
                    "machines": n,
                    "ldlib_async_cpu_mean_pct_one_core": 10,
                    "server_cpu_mean_pct_one_core": 50,
                }
                for n in [64, 256, 1024]
            ],
            "async_error_count": 0,
        }
        b = {
            "profiles": [
                {
                    "machines": n,
                    "ldlib_async_cpu_mean_pct_one_core": 8,
                    "server_cpu_mean_pct_one_core": 45,
                }
                for n in [64, 256, 1024]
            ],
            "async_error_count": 0,
        }
        markdown = summarize({"upstream": a, "fork": b}, 20, 40)
        self.assertIn("| 256 | 10.00 | 8.00 | +20.0%", markdown)
        b["profiles"][0]["machines"] = 128
        with self.assertRaises(RuntimeError):
            summarize({"upstream": a, "fork": b}, 20, 40)


if __name__ == "__main__":
    unittest.main()
