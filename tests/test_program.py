# SPDX-License-Identifier: MIT
"""A profile's time program as a week, what runs now and the next change (program.py) - no Home
Assistant needed. The programs here are made up for the tests."""
from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import pytest

spec = importlib.util.spec_from_file_location(
    "program", Path(__file__).resolve().parents[1] / "custom_components/inventer_e16/program.py")
program = importlib.util.module_from_spec(spec)
spec.loader.exec_module(program)

WEEKDAYS, WEEKEND = 0b0011111, 0b1100000
HEAT_RECOVERY, VENTILATION, PAUSE_OPEN = 1, 2, 4
# A Monday in a time zone without DST
MONDAY = datetime(2026, 11, 16, tzinfo=timezone(timedelta(hours=1)))


def item(hours, minutes, days, mode, speed):
    return {"seconds": hours * 3600 + minutes * 60, "days": days, "mode": mode, "speed": speed,
            "type": 1}


def entries(*items):
    """32 entries as the client reads them: the given ones first, the others empty."""
    return [*items] + [None] * (32 - len(items))


SAMPLE = entries(
    item(6, 30, WEEKDAYS, HEAT_RECOVERY, 2),
    item(7, 30, WEEKDAYS, VENTILATION, 4),
    item(8, 15, WEEKDAYS, HEAT_RECOVERY, 2),
    item(17, 30, WEEKDAYS, HEAT_RECOVERY, 3),
    item(22, 0, WEEKDAYS, HEAT_RECOVERY, 1),
    item(8, 0, WEEKEND, HEAT_RECOVERY, 2),
    item(23, 30, WEEKEND, PAUSE_OPEN, 0),
    item(20, 0, WEEKDAYS, HEAT_RECOVERY, 3),  # changes nothing: merged with 17:30
)


def segment(start, end, mode, speed):
    return {"start": start, "end": end, "mode": mode, "speed": speed}


def at(days, hours, minutes=0):
    return MONDAY + timedelta(days=days, hours=hours, minutes=minutes)


def test_entries_as_in_the_service_response():
    sample = program.Program(SAMPLE)
    assert sample.has_program and len(sample.entries) == 8
    assert sample.entries[1] == {"entry": 1, "time": "07:30", "days": [
        "monday", "tuesday", "wednesday", "thursday", "friday"], "mode": "ventilation", "speed": 4}
    assert sample.entries[6]["days"] == ["saturday", "sunday"]


def test_a_day_starts_with_the_last_switch_before_it():
    week = program.Program(SAMPLE).week
    assert list(week) == list(program.DAYS)
    # Monday starts with what Sunday 23:30 set: the week wraps around
    assert week["monday"] == [
        segment("00:00", "06:30", "pause_open", 0),
        segment("06:30", "07:30", "heat_recovery", 2),
        segment("07:30", "08:15", "ventilation", 4),
        segment("08:15", "17:30", "heat_recovery", 2),
        segment("17:30", "22:00", "heat_recovery", 3),
        segment("22:00", "24:00", "heat_recovery", 1),
    ]
    assert week["tuesday"][0] == segment("00:00", "06:30", "heat_recovery", 1)
    assert week["saturday"] == [
        segment("00:00", "08:00", "heat_recovery", 1),
        segment("08:00", "23:30", "heat_recovery", 2),
        segment("23:30", "24:00", "pause_open", 0),
    ]
    assert week["sunday"][0] == segment("00:00", "08:00", "pause_open", 0)


def test_every_day_is_covered_without_gaps():
    for segments in program.Program(SAMPLE).week.values():
        assert segments[0]["start"] == "00:00" and segments[-1]["end"] == "24:00"
        assert all(a["end"] == b["start"] and (a["mode"], a["speed"]) != (b["mode"], b["speed"])
                   for a, b in zip(segments, segments[1:]))


def test_current_and_next_on_a_weekday():
    sample = program.Program(SAMPLE)
    now = at(2, 18)  # Wednesday 18:00
    assert sample.current(now) == segment("17:30", "22:00", "heat_recovery", 3)
    # 20:00 changes nothing, so the next change is 22:00
    assert sample.next(now) == {"at": at(2, 22).isoformat(), "mode": "heat_recovery", "speed": 1}


def test_next_change_across_midnight():
    sample = program.Program(SAMPLE)
    now = at(4, 23)  # Friday 23:00: Saturday starts with the same speed, the change is at 08:00
    assert sample.current(now) == segment("22:00", "24:00", "heat_recovery", 1)
    assert sample.next(now) == {"at": at(5, 8).isoformat(), "mode": "heat_recovery", "speed": 2}


def test_next_change_across_sunday_to_monday():
    sample = program.Program(SAMPLE)
    now = at(6, 23, 45)  # Sunday 23:45
    assert sample.current(now) == segment("23:30", "24:00", "pause_open", 0)
    assert sample.next(now) == {"at": at(7, 6, 30).isoformat(), "mode": "heat_recovery", "speed": 2}
    # At a switch time the new segment runs already
    assert sample.current(at(7, 6, 30)) == segment("06:30", "07:30", "heat_recovery", 2)


def test_a_program_that_never_changes():
    constant = program.Program(entries(item(0, 0, 127, HEAT_RECOVERY, 1)))
    assert all(day == [segment("00:00", "24:00", "heat_recovery", 1)] for day in constant.week.values())
    assert constant.current(at(3, 12)) == segment("00:00", "24:00", "heat_recovery", 1)
    assert constant.next(at(3, 12)) is None


def test_one_entry_on_one_day_runs_all_week():
    single = program.Program(entries(item(9, 0, 0b0000100, VENTILATION, 2)))  # Wednesday 09:00
    assert single.week["monday"] == [segment("00:00", "24:00", "ventilation", 2)]
    assert single.next(at(0, 12)) is None


def test_without_entries():
    empty = program.Program([None] * 32)
    assert not empty.has_program and empty.entries == []
    assert empty.week == {day: [] for day in program.DAYS}
    assert empty.current(at(0, 12)) is None and empty.next(at(0, 12)) is None


def test_the_later_entry_wins_at_the_same_time():
    both = program.Program(entries(item(0, 0, 127, HEAT_RECOVERY, 1), item(7, 0, 1, HEAT_RECOVERY, 2),
                                   item(7, 0, 1, VENTILATION, 4)))
    assert both.week["monday"][1] == segment("07:00", "24:00", "ventilation", 4)


def test_faulty_entries_stay_out_of_the_week():
    # A start time after the end of the day can't be placed; an unknown mode is passed on as it is
    faulty = program.Program(entries(item(25, 0, 127, HEAT_RECOVERY, 4), item(0, 0, 127, 9, 1)))
    assert [e["time"] for e in faulty.entries] == ["25:00", "00:00"]
    assert faulty.week["friday"] == [segment("00:00", "24:00", 9, 1)]


def test_next_change_across_a_dst_change():
    try:
        berlin = ZoneInfo("Europe/Berlin")
    except ZoneInfoNotFoundError:
        pytest.skip("no time zone database")
    sample = program.Program(entries(item(0, 0, 127, HEAT_RECOVERY, 1), item(3, 0, 64, VENTILATION, 2)))
    # Saturday before the clocks go forward; Sunday 03:00 is summer time already
    now = datetime(2026, 3, 28, 23, 45, tzinfo=berlin)
    assert sample.next(now)["at"] == "2026-03-29T03:00:00+02:00"
