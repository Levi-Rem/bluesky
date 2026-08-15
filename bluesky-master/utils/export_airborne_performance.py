"""Export validated airborne-only performance curves from simulator_backup.

The exporter deliberately keeps MySQL out of the runtime path.  It invokes the
local mysql client, validates source rows, converts source units to SI and
writes one deterministic JSON document consumed by the training adapter.
"""

import argparse
import json
import os
import subprocess
from collections import defaultdict
from datetime import date
from pathlib import Path


SOURCE_TO_ENGINE_TYPE = {
    "A319": "A319",
    "A320": "A320",
    "A321": "A321",
    "A321NEO": "A21N",
    "A332": "A332",
    "A380": "A388",
    "B738": "B738",
    "B744": "B744",
    "B773ER": "B77W",
}
PHASE_NAMES = {1: "CLIMB", 2: "CRUISE", 3: "DESCENT"}
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[1]
    / "bluesky"
    / "plugins"
    / "training_adapter"
    / "data"
    / "airborne_performance.json"
)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql", help="mysql client executable")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password-env", default="BLUESKY_SOURCE_DB_PASSWORD")
    parser.add_argument("--database", default="simulator_backup")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser


def _query(args, sql):
    password = os.environ.get(args.password_env)
    if password is None:
        raise RuntimeError("未设置数据库密码环境变量: {}".format(args.password_env))
    command = [
        args.mysql,
        "--host={}".format(args.host),
        "--port={}".format(args.port),
        "--user={}".format(args.user),
        "--database={}".format(args.database),
        "--default-character-set=utf8mb4",
        "--batch",
        "--raw",
        "--skip-column-names",
        "--execute={}".format(sql),
    ]
    process_environment = os.environ.copy()
    process_environment["MYSQL_PWD"] = password
    result = subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=True,
        env=process_environment,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def _load_source(args):
    quoted_types = ",".join("'{}'".format(code) for code in SOURCE_TO_ENGINE_TYPE)
    aircraft_rows = _query(
        args,
        """
        SELECT p.plane_type_id,p.icao,p.plane_type_name,p.wake_flow_type,
               f.ceiling_max
        FROM ap_plane_type_info p
        JOIN ap_config_fly_info f ON f.plane_type_id=p.plane_type_id
        WHERE p.icao IN ({}) ORDER BY p.icao
        """.format(quoted_types),
    )
    curve_rows = _query(
        args,
        """
        SELECT p.icao,h.flight_status,h.height,h.indicated_airspeed,
               h.indicated_airspeed_min,h.indicated_airspeed_max,
               h.climb_rate,h.decline_rate
        FROM ap_plane_type_info p
        JOIN ap_fly_height_info h ON h.plane_type_id=p.plane_type_id
        JOIN ap_config_fly_info f ON f.plane_type_id=p.plane_type_id
        WHERE p.icao IN ({}) AND h.load_type=2 AND h.height<=f.ceiling_max
        ORDER BY p.icao,h.flight_status,h.height
        """.format(quoted_types),
    )
    return aircraft_rows, curve_rows


def _validate(aircraft_rows, curve_rows):
    source_types = {row[1] for row in aircraft_rows}
    missing = set(SOURCE_TO_ENGINE_TYPE) - source_types
    if missing:
        raise RuntimeError("性能库缺少机型: {}".format(", ".join(sorted(missing))))

    grouped = defaultdict(lambda: defaultdict(list))
    for row in curve_rows:
        source_type, status_text = row[0], row[1]
        status = int(status_text)
        if status not in PHASE_NAMES:
            raise RuntimeError("{} 含未知飞行阶段 {}".format(source_type, status))
        values = [float(value) for value in row[2:]]
        height, nominal, minimum, maximum, climb_rate, descent_rate = values
        if minimum <= 0 or maximum <= minimum:
            raise RuntimeError("{} 在 {} m 的速度包线无效".format(source_type, height))
        if status == 1 and climb_rate <= 0:
            raise RuntimeError("{} 在 {} m 的爬升率无效".format(source_type, height))
        if status == 3 and descent_rate <= 0:
            raise RuntimeError("{} 在 {} m 的下降率无效".format(source_type, height))
        grouped[source_type][status].append(values)

    for source_type in SOURCE_TO_ENGINE_TYPE:
        phases = grouped[source_type]
        if set(phases) != set(PHASE_NAMES):
            raise RuntimeError("{} 的爬升/巡航/下降曲线不完整".format(source_type))
        height_grids = [[row[0] for row in phases[status]] for status in PHASE_NAMES]
        if any(grid != height_grids[0] for grid in height_grids[1:]):
            raise RuntimeError("{} 的飞行阶段高度网格不一致".format(source_type))
        if len(height_grids[0]) != 44:
            raise RuntimeError("{} 期望 44 个高度层，实际 {}".format(
                source_type, len(height_grids[0])))
    return grouped


def build_document(database, aircraft_rows, curve_rows):
    grouped = _validate(aircraft_rows, curve_rows)
    metadata = {row[1]: row for row in aircraft_rows}
    aircraft = {}
    for source_type, engine_type in SOURCE_TO_ENGINE_TYPE.items():
        row = metadata[source_type]
        phases = {}
        for status, phase_name in PHASE_NAMES.items():
            points = []
            for values in grouped[source_type][status]:
                height, nominal, minimum, maximum, climb_rate, descent_rate = values
                normalized_nominal = min(max(nominal, minimum), maximum)
                points.append({
                    "altitudeMeters": height,
                    "nominalCasMetersPerSecond": normalized_nominal / 3.6,
                    "minimumCasMetersPerSecond": minimum / 3.6,
                    "maximumCasMetersPerSecond": maximum / 3.6,
                    "maximumVerticalRateMetersPerSecond": (
                        climb_rate if status == 1 else descent_rate if status == 3 else 0.0
                    ),
                })
            phases[phase_name] = points
        aircraft[engine_type] = {
            "sourceAircraftType": source_type,
            "name": row[2],
            "wakeCategory": int(row[3]),
            "ceilingMeters": float(row[4]),
            "loadType": "MEDIUM",
            "phases": phases,
        }
    return {
        "schemaVersion": 1,
        "source": {
            "database": database,
            "tables": ["ap_plane_type_info", "ap_config_fly_info", "ap_fly_height_info"],
            "exportDate": date.today().isoformat(),
            "sourceSpeedUnit": "km/h CAS",
            "sourceVerticalRateUnit": "m/s",
        },
        "selection": {
            "scope": "AIRBORNE_ONLY",
            "loadType": "MEDIUM",
            "maximumAltitudeRule": "height <= ap_config_fly_info.ceiling_max",
            "nominalCasRule": "clip source reference CAS to min/max envelope",
        },
        "aircraft": aircraft,
    }


def main(argv=None):
    args = build_parser().parse_args(argv)
    aircraft_rows, curve_rows = _load_source(args)
    document = build_document(args.database, aircraft_rows, curve_rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("exported {} aircraft to {}".format(len(document["aircraft"]), args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
