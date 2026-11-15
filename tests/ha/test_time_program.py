# SPDX-License-Identifier: MIT
"""Time program sensor of each zone against the simulated controller: the program of the active
profile as attributes, read in the background (one connection per profile), and what runs now and
next, following the clock."""
from datetime import datetime, timedelta

from homeassistant.util import dt as dt_util
from pytest_homeassistant.common import async_fire_time_changed

from custom_components.inventer_e16 import client
from custom_components.inventer_e16.const import DOMAIN
from custom_components.inventer_e16.diagnostics import async_get_config_entry_diagnostics
from custom_components.inventer_e16.program import DAYS

from .test_zones import _call, _device, _device_of, _entity, _setup, sim

WEEKDAYS, WEEKEND = 0b0011111, 0b1100000
HEAT_RECOVERY, VENTILATION, PAUSE_OPEN = client.FAN_HEAT_RECOVERY, client.FAN_VENTILATION, client.FAN_STOP
LIVING_ROOM, KITCHEN = 4, 5
# Made up for the tests: weekdays with a one-way slot in the morning, weekends with a pause at night
PROGRAM = (
    (6, 15, WEEKDAYS, HEAT_RECOVERY, 3),
    (7, 45, WEEKDAYS, VENTILATION, 4),
    (8, 30, WEEKDAYS, HEAT_RECOVERY, 3),
    (21, 30, WEEKDAYS, HEAT_RECOVERY, 1),
    (9, 0, WEEKEND, HEAT_RECOVERY, 2),
    (23, 30, WEEKEND, PAUSE_OPEN, 0),
)


def local(*args):
    return datetime(*args, tzinfo=dt_util.get_default_time_zone())


def store(controller, profile, entries, first=0):
    for index, (hours, minutes, days, mode, speed) in enumerate(entries, first):
        controller.programs[(profile - 1, index)] = client.program_record(
            hours * 3600 + minutes * 60, days, mode, speed)


def count_reads(monkeypatch, fail=None):
    """Profiles read through the client; `fail` (a list) makes the reads fail while it is not empty."""
    reads, read = [], client.E16Client.get_time_program

    def counted(self, profile):
        reads.append(profile)
        if fail:
            raise TimeoutError("no reply")
        return read(self, profile)

    monkeypatch.setattr(client.E16Client, "get_time_program", counted)
    return reads


def attributes(hass, unique_id):
    return hass.states.get(_entity(hass, "sensor", unique_id)).attributes


def segment(start, end, mode, speed):
    return {"start": start, "end": end, "mode": mode, "speed": speed}


async def test_program_of_the_active_profile(hass, monkeypatch, freezer):
    freezer.move_to(local(2026, 11, 18, 10, 30))  # a Wednesday
    controller = sim.FakeController(zones=2)
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    entry = await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    state = hass.states.get(_entity(hass, "sensor", "time_program"))
    assert state.state == "living_room" and state.attributes["icon"] == "mdi:calendar-clock"
    assert "kitchen" in state.attributes["options"] and "custom_9" in state.attributes["options"]
    program = state.attributes
    assert program["has_program"] is True
    # The entries as get_time_program returns them
    response = await hass.services.async_call(
        DOMAIN, "get_time_program", {"entity_id": _entity(hass, "fan", "fan")}, blocking=True,
        return_response=True)
    assert program["entries"] == response[_entity(hass, "fan", "fan")]["entries"]
    assert program["entries"][1] == {"entry": 1, "time": "07:45", "days": [
        "monday", "tuesday", "wednesday", "thursday", "friday"], "mode": "ventilation", "speed": 4}
    # Monday starts with what Sunday 23:30 set
    assert program["week"]["monday"] == [
        segment("00:00", "06:15", "pause_open", 0), segment("06:15", "07:45", "heat_recovery", 3),
        segment("07:45", "08:30", "ventilation", 4), segment("08:30", "21:30", "heat_recovery", 3),
        segment("21:30", "24:00", "heat_recovery", 1)]
    assert program["week"]["saturday"] == [
        segment("00:00", "09:00", "heat_recovery", 1), segment("09:00", "23:30", "heat_recovery", 2),
        segment("23:30", "24:00", "pause_open", 0)]
    assert program["current"] == segment("08:30", "21:30", "heat_recovery", 3)
    assert program["next"] == {"at": local(2026, 11, 18, 21, 30).isoformat(),
                               "mode": "heat_recovery", "speed": 1}
    assert program["read_at"] == local(2026, 11, 18, 10, 30).isoformat()
    # Zone 2 runs the default profile, which has no time program; each sensor sits on its zone
    assert hass.states.get(_entity(hass, "sensor", "zone2_time_program")).state == "default"
    zone2 = attributes(hass, "zone2_time_program")
    assert (zone2["has_program"], zone2["entries"], zone2["current"], zone2["next"],
            zone2["read_at"]) == (False, [], None, None, None)
    assert zone2["week"] == {day: [] for day in DAYS}
    assert _device_of(hass, "sensor", "zone2_time_program") == _device(hass, entry, "_zone2").id
    assert _device_of(hass, "sensor", "time_program") == _device(hass, entry, "_zone1").id
    diagnostics = await async_get_config_entry_diagnostics(hass, entry)
    assert diagnostics["time_programs"][LIVING_ROOM]["entries"] == program["entries"]


async def test_single_zone_sensor_on_the_controller(hass, monkeypatch):
    controller = sim.FakeController()
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    entry = await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert _device_of(hass, "sensor", "time_program") == entry.runtime_data.main_device_id
    assert attributes(hass, "time_program")["has_program"] is True


async def test_a_profile_shared_by_zones_is_read_once(hass, monkeypatch):
    controller = sim.FakeController(zones=2, pairs_follow=False)
    controller.zones[1].profile = controller.zones[2].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    reads = count_reads(monkeypatch)
    await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM]
    assert attributes(hass, "time_program")["week"] == attributes(hass, "zone2_time_program")["week"]
    assert attributes(hass, "zone2_time_program")["has_program"] is True


async def test_a_profile_change_between_polls_reads_its_program_once(hass, monkeypatch):
    controller = sim.FakeController(zones=2)
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    store(controller, KITCHEN, [(0, 0, 127, HEAT_RECOVERY, 1), (12, 0, 127, VENTILATION, 2)])
    reads = count_reads(monkeypatch)
    entry = await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM]
    # Changed in the app: noticed with the next poll, read once, in the background
    controller.zones[2].profile = KITCHEN
    await entry.runtime_data.zone.async_refresh()
    await entry.runtime_data.zone.async_refresh()
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM, KITCHEN]
    assert hass.states.get(_entity(hass, "sensor", "zone2_time_program")).state == "kitchen"
    assert [e["time"] for e in attributes(hass, "zone2_time_program")["entries"]] == ["00:00", "12:00"]
    # Changed with the profile select: the same
    await _call(hass, "select", "select_option", _entity(hass, "select", "profile"), option="kitchen")
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM, KITCHEN, KITCHEN]
    assert attributes(hass, "time_program")["entries"] == attributes(hass, "zone2_time_program")["entries"]


async def test_before_the_first_read_the_program_is_unknown(hass, monkeypatch):
    controller = sim.FakeController()
    controller.zones[1].profile = LIVING_ROOM
    fail = [True]
    count_reads(monkeypatch, fail)
    await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert hass.states.get(_entity(hass, "sensor", "time_program")).state == "living_room"
    program = attributes(hass, "time_program")
    assert all(program[key] is None for key in
               ("has_program", "entries", "week", "current", "next", "read_at"))


async def test_now_and_next_follow_the_clock_without_asking_the_controller(hass, monkeypatch, freezer):
    freezer.move_to(local(2026, 11, 22, 23, 58))  # a Sunday
    controller = sim.FakeController()
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    # Polls once an hour: within the next minutes only the clock moves
    await _setup(hass, monkeypatch, controller, options={"scan_interval": 3600})
    await hass.async_block_till_done(wait_background_tasks=True)
    assert attributes(hass, "time_program")["current"] == segment("23:30", "24:00", "pause_open", 0)
    # Monday starts with the pause of Sunday night: the next change is at 06:15, across the week
    assert attributes(hass, "time_program")["next"] == {
        "at": local(2026, 11, 23, 6, 15).isoformat(), "mode": "heat_recovery", "speed": 3}
    connections = controller.connections
    freezer.move_to(local(2026, 11, 23, 0, 0, 0))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert attributes(hass, "time_program")["current"] == segment("00:00", "06:15", "pause_open", 0)
    assert controller.connections == connections
    freezer.move_to(local(2026, 11, 23, 6, 15, 0))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert attributes(hass, "time_program")["current"] == segment("06:15", "07:45", "heat_recovery", 3)
    assert attributes(hass, "time_program")["next"]["at"] == local(2026, 11, 23, 7, 45).isoformat()
    # Across midnight on a weekday: the evening level runs on until 06:15
    freezer.move_to(local(2026, 11, 23, 23, 59, 0))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert attributes(hass, "time_program")["next"] == {
        "at": local(2026, 11, 24, 6, 15).isoformat(), "mode": "heat_recovery", "speed": 3}


async def test_a_failed_read_keeps_the_last_program(hass, monkeypatch, freezer, caplog):
    freezer.move_to(local(2026, 11, 18, 10, 30))
    controller = sim.FakeController(zones=2)
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    fail = []
    reads = count_reads(monkeypatch, fail)
    await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    before = dict(attributes(hass, "time_program"))
    # The daily read fails: the program read before stays
    fail.append(True)
    controller.programs.clear()
    freezer.tick(timedelta(days=1))
    async_fire_time_changed(hass)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM, LIVING_ROOM]
    assert "retrying in 10 minutes" in caplog.text
    after = dict(attributes(hass, "time_program"))
    assert after["entries"] == before["entries"] and after["read_at"] == before["read_at"]
    # Ten minutes later it is read again
    fail.clear()
    freezer.tick(timedelta(minutes=10, seconds=1))
    async_fire_time_changed(hass)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM] * 3
    assert attributes(hass, "time_program")["has_program"] is False
    assert attributes(hass, "time_program")["read_at"] == local(2026, 11, 19, 10, 40, 1).isoformat()


async def test_an_entry_written_through_the_actions_shows_in_the_sensor(hass, monkeypatch):
    controller = sim.FakeController(zones=2)
    controller.zones[1].profile = LIVING_ROOM
    store(controller, LIVING_ROOM, PROGRAM)
    reads = count_reads(monkeypatch)
    await _setup(hass, monkeypatch, controller)
    await hass.async_block_till_done(wait_background_tasks=True)
    fan = _entity(hass, "fan", "fan")
    await _call(hass, DOMAIN, "set_time_program_entry", fan, profile="living_room", entry=10,
                time="13:15", days=["saturday"], mode="ventilation", speed=4)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM, LIVING_ROOM]
    assert attributes(hass, "time_program")["entries"][-1] == {
        "entry": 10, "time": "13:15", "days": ["saturday"], "mode": "ventilation", "speed": 4}
    assert segment("13:15", "23:30", "ventilation", 4) in attributes(hass, "time_program")["week"]["saturday"]
    await _call(hass, DOMAIN, "delete_time_program_entry", fan, profile="living_room", entry=10)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert len(attributes(hass, "time_program")["entries"]) == len(PROGRAM)
    # A program no zone runs is not read
    await _call(hass, DOMAIN, "set_time_program_entry", fan, profile="kitchen", entry=0,
                time="07:00", days=["monday"], mode="heat_recovery", speed=2)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert reads == [LIVING_ROOM] * 3
