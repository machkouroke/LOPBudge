#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

def get_status(case):
    if case.find("failure") is not None:
        return "failed"
    if case.find("error") is not None:
        return "broken"
    if case.find("skipped") is not None:
        return "skipped"
    return "passed"

def get_message(case):
    failure = case.find("failure")
    if failure is not None:
        return failure.text
    error = case.find("error")
    if error is not None:
        return error.text
    return None

def main():
    parser = argparse.ArgumentParser(description="Convert Maestro JUnit and results to Allure Results")
    parser.add_argument("--junit", type=Path, required=True, help="Path to merged JUnit XML")
    parser.add_argument("--results", type=Path, required=True, help="Path to merged Maestro results directory (contains 'merged/' folder)")
    parser.add_argument("--output", type=Path, default=Path("allure-results"), help="Output directory for Allure results")
    args = parser.parse_args()

    if not args.junit.exists():
        print(f"Error: JUnit file {args.junit} not found")
        return

    args.output.mkdir(parents=True, exist_ok=True)

    tree = ET.parse(args.junit)
    root = tree.getroot()

    # In Maestro JUnit, the root is usually <testsuite>
    testcases = root.findall("testcase")

    start_time = int(datetime.now().timestamp() * 1000)

    for case in testcases:
        name = case.attrib.get("name")
        classname = case.attrib.get("classname", "Maestro")
        duration = float(case.attrib.get("time", 0)) * 1000
        status = get_status(case)
        message = get_message(case)

        # Allure result structure
        result_id = str(uuid.uuid4())
        allure_result = {
            "uuid": result_id,
            "historyId": f"{classname}.{name}",
            "fullName": f"{classname}.{name}",
            "labels": [
                {"name": "suite", "value": classname},
                {"name": "testClass", "value": classname},
                {"name": "testMethod", "value": name},
                {"name": "package", "value": classname}
            ],
            "name": name,
            "status": status,
            "start": start_time,
            "stop": start_time + int(duration),
            "attachments": []
        }

        if message:
            allure_result["statusDetails"] = {"message": message}

        # Look for attachments in maestro-results-merged/merged/<name>
        # Note: Maestro might replace special characters with underscores in directory names
        # We try the exact name first, then a normalized version
        flow_results_dir = args.results / "merged" / name
        if not flow_results_dir.exists():
            normalized_name = "".join([c if c.isalnum() else "_" for c in name])
            # Maestro sometimes leaves multiple underscores as one
            import re
            normalized_name = re.sub(r"_+", "_", normalized_name).strip("_")
            flow_results_dir = args.results / "merged" / normalized_name

        # If still not found, try to find by prefix matching
        if not flow_results_dir.exists():
            merged_dir = args.results / "merged"
            if merged_dir.exists():
                potential_dirs = [d for d in merged_dir.iterdir() if d.is_dir() and (d.name.startswith(name[:10]) or name.startswith(d.name[:10]))]
                if potential_dirs:
                    # Pick the one with the highest similarity or just the first one if unique
                    flow_results_dir = potential_dirs[0]

        if flow_results_dir.exists():
            # Add screenshots
            screenshots_dir = flow_results_dir / "screenshots"
            if screenshots_dir.exists():
                for screenshot in sorted(screenshots_dir.glob("*.png")):
                    attachment_uuid = str(uuid.uuid4())
                    dest_name = f"{attachment_uuid}-attachment.png"
                    shutil.copy(screenshot, args.output / dest_name)
                    allure_result["attachments"].append({
                        "name": f"Screenshot: {screenshot.name}",
                        "source": dest_name,
                        "type": "image/png"
                    })

            # Add log
            logs_dir = flow_results_dir / "logs"
            if logs_dir.exists():
                maestro_log = logs_dir / "maestro.log"
                if maestro_log.exists():
                    attachment_uuid = str(uuid.uuid4())
                    dest_name = f"{attachment_uuid}-attachment.log"
                    shutil.copy(maestro_log, args.output / dest_name)
                    allure_result["attachments"].append({
                        "name": "Maestro Log",
                        "source": dest_name,
                        "type": "text/plain"
                    })

            # Add commands.json
            commands_json = flow_results_dir / "commands.json"
            if commands_json.exists():
                attachment_uuid = str(uuid.uuid4())
                dest_name = f"{attachment_uuid}-attachment.json"
                shutil.copy(commands_json, args.output / dest_name)
                allure_result["attachments"].append({
                    "name": "Maestro Commands",
                    "source": dest_name,
                    "type": "application/json"
                })

        # Write result file
        with open(args.output / f"{result_id}-result.json", "w") as f:
            json.dump(allure_result, f, indent=2)

    print(f"Processed {len(testcases)} testcases. Allure results written to {args.output}")

if __name__ == "__main__":
    main()
