from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


CASES_MODULE = load("instruction_cases_test", HERE / "instruction_cases.py")
RUNNER = load("instruction_runner_test", HERE / "instruction_acceptance_runner.py")


class InstructionAcceptanceCatalogTest(unittest.TestCase):
    def test_case_catalog_exactly_tracks_machine_readable_command_catalog(self):
        catalog = json.loads((HERE.parent / "docs/contracts/command-catalog-v2.json")
                             .read_text(encoding="utf-8"))
        expected = [item["type"] for item in catalog["commands"]]
        self.assertEqual(expected, list(CASES_MODULE.CASES))
        self.assertEqual(30, len(expected))
        self.assertGreaterEqual(sum(map(len, CASES_MODULE.CASES.values())), 180)

    def test_every_instruction_has_positive_and_negative_or_routed_cases(self):
        routed = {"ACID", "FRE", "DEL"}
        for command, cases in CASES_MODULE.CASES.items():
            self.assertTrue(cases, command)
            self.assertEqual(len(cases), len({case["id"] for case in cases}), command)
            if command in routed:
                self.assertTrue(any(case["kind"] != "INSTRUCTION" for case in cases), command)
                continue
            statuses = [status for case in cases for status in case.get("http", [])]
            self.assertTrue(any(200 <= status < 300 for status in statuses), command)
            self.assertTrue(any(status >= 400 for status in statuses), command)

    def test_vendor_aliases_and_non_instruction_routes_are_explicit(self):
        all_text = [str(case.get("text", "")) for cases in CASES_MODULE.CASES.values()
                    for case in cases]
        for alias in ("LVL ", "SPEED ", "CR ", "DR ", "SSRCODE ",
                      "HOLD L", "OFFSET R5", "FRE ", "DEL"):
            self.assertTrue(any(text.startswith(alias) for text in all_text), alias)

    def test_nested_subset_supports_tolerance_and_reports_mismatch(self):
        RUNNER.assert_subset({"value": 10.05}, {"value": {"approx": 10, "tolerance": 0.1}})
        with self.assertRaises(RUNNER.AcceptanceFailure):
            RUNNER.assert_subset({"value": 10.2}, {"value": {"approx": 10, "tolerance": 0.1}})

    def test_example_config_and_all_case_templates_are_well_formed(self):
        config = json.loads((HERE / "instruction-config.example.json").read_text(encoding="utf-8"))
        tokens = config["tokens"] | {
            "targetFrequency": config["tokens"]["targetFrequency"],
            "unknownFrequency": config["tokens"]["unknownFrequency"],
        }
        for command, cases in CASES_MODULE.CASES.items():
            for case in cases:
                rendered = RUNNER.render(case, tokens)
                self.assertEqual(case["id"], rendered["id"])

    def test_documented_total_includes_generated_common_guards(self):
        explicit = sum(len(cases) for cases in CASES_MODULE.CASES.values())
        instruction_commands = len(CASES_MODULE.CASES) - 3  # ACID/FRE/DEL are routed.
        generated = instruction_commands * 5 + len(RUNNER.QUEUE_COMMANDS)
        self.assertEqual(194, explicit)
        self.assertEqual(352, explicit + generated)


if __name__ == "__main__":
    unittest.main()
