# SPDX-License-Identifier: MIT
"""One-way direction with set_mode against the simulated controller: the polarity of the listed
fan units is written before the command and put back once the command has ended, also after a
restart. Both kinds of controllers: one that switches the partner of a pair along (#33) and one
where every fan unit switches on its own (#38)."""
import struct

import pytest

from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import device_registry as dr, issue_registry as ir
from pytest_homeassistant.common import MockConfigEntry, async_fire_time_changed

from custom_components.inventer_e16 import client
from custom_components.inventer_e16.const import DOMAIN

from .test_zones import _call, _device, _entity, _last_command, _setup, sim

SUPPLY, EXTRACT = client.POLARITY_SUPPLY, client.POLARITY_EXTRACT
BOTH = pytest.mark.parametrize("pairs_follow", [True, False], ids=["partner_follows", "each_on_its_own"])


def pair(pairs_follow):
    """Two zones: fan units 1 (extract) and 2 (supply) in zone 1, fan unit 3 in zone 2."""
    controller = sim.FakeController(zones=2, pairs_follow=pairs_follow)
    controller.devices[2] = sim.device_row(2, 2, 2, "Fan 2", 5030009, polarity=SUPPLY, signal=-54)
    return controller


def polarities(controller, *addresses):
    rows = {row[0]: row for row in controller.devices}
    return tuple(rows[address][sim.POLARITY_OFFSET] for address in addresses or (1, 2))


def polarity_writes(controller):
    """(address, polarity) of every polarity write, in order."""
    return [(struct.unpack_from("<I", payload, 4)[0] // 65536, payload[8])
            for ptype, operation, payload in controller.requests
            if ptype == sim.DEVICE_FIELD and operation == sim.UPDATE]


def fan_unit(hass, entry, address):
    return _device(hass, entry, f"_{address}").id


async def one_way(hass, entry, **data):
    await _call(hass, DOMAIN, "set_mode", _entity(hass, "fan", "fan"), mode="ventilation", speed=2,
                minutes=30, **data)


def pending(hass):
    return hass.states.get(_entity(hass, "sensor", "last_override")).attributes[
        "polarity_restore_pending"]


async def poll(hass, entry, seconds, controller):
    """The controller's clock moves on, then the zones are read."""
    controller.advance(seconds)
    await entry.runtime_data.zone.async_refresh()
    await hass.async_block_till_done(wait_background_tasks=True)


async def test_the_direction_is_checked_before_anything_is_written(hass, monkeypatch):
    controller = pair(True)
    controller.devices.append(sim.device_row(5, 4, 3, "Indoor", 5030008, signal=-53, battery=64.0))
    entry = await _setup(hass, monkeypatch, controller)
    one, three, sensor = (fan_unit(hass, entry, address) for address in (1, 3, 5))
    # A fan unit with the same address on another controller
    other = MockConfigEntry(domain=DOMAIN, unique_id="KLMNO-PQRST")
    other.add_to_hass(hass)
    elsewhere = dr.async_get(hass).async_get_or_create(
        config_entry_id=other.entry_id, identifiers={(DOMAIN, "KLMNO-PQRST_1")}).id
    updates = len([r for r in controller.requests if r[1] == sim.UPDATE])
    for data, key in (
        ({"mode": "heat_recovery", "supply": [one]}, "direction_needs_ventilation"),
        ({"mode": "pause", "extract": [one]}, "direction_needs_ventilation"),
        ({"mode": "ventilation", "supply": [three]}, "fan_in_other_zone"),
        ({"mode": "ventilation", "supply": [one], "extract": [one]}, "fan_in_both_lists"),
        ({"mode": "ventilation", "extract": [sensor]}, "not_a_fan_unit"),
        ({"mode": "ventilation", "extract": [_device(hass, entry, "_zone1").id]}, "not_a_fan_unit"),
        ({"mode": "ventilation", "supply": [_device(hass, entry, "").id]}, "not_a_fan_unit"),
        ({"mode": "ventilation", "supply": [elsewhere]}, "not_a_fan_unit"),
        ({"mode": "ventilation", "supply": ["no-such-device"]}, "not_a_fan_unit"),
    ):
        with pytest.raises(ServiceValidationError) as error:
            await _call(hass, DOMAIN, "set_mode", _entity(hass, "fan", "fan"), **data)
        assert error.value.translation_key == key, data
    assert len([r for r in controller.requests if r[1] == sim.UPDATE]) == updates
    # Empty lists are no direction: any mode
    await _call(hass, DOMAIN, "set_mode", _entity(hass, "fan", "fan"), mode="heat_recovery",
                supply=[], extract=[])
    assert polarity_writes(controller) == []


@BOTH
async def test_only_fans_that_differ_are_written_before_the_command(hass, monkeypatch, pairs_follow):
    controller = pair(pairs_follow)
    entry = await _setup(hass, monkeypatch, controller)
    one, two = fan_unit(hass, entry, 1), fan_unit(hass, entry, 2)
    # Already as asked: nothing written, nothing to put back
    await one_way(hass, entry, supply=[two], extract=[one])
    assert polarity_writes(controller) == [] and pending(hass) is False
    # The other way round: every listed fan unit explicitly, unless it switched along
    await one_way(hass, entry, supply=one, extract=two)
    assert polarity_writes(controller) == ([(1, SUPPLY)] if pairs_follow else [(1, SUPPLY), (2, EXTRACT)])
    assert polarities(controller) == (SUPPLY, EXTRACT)
    updates = [ptype for ptype, operation, _ in controller.requests if operation == sim.UPDATE]
    assert updates[-2:] == [sim.DEVICE_FIELD, sim.USER_OVERRIDE]
    assert _last_command(controller) == (client.CMD_ZONE_SPEED_MODE, 2, client.FAN_VENTILATION, 1, 1800)
    assert hass.states.get(_entity(hass, "select", "1_polarity")).state == "supply"
    assert pending(hass) is True
    assert hass.states.get(_entity(hass, "sensor", "zone2_last_override")).attributes[
        "polarity_restore_pending"] is False


@BOTH
async def test_the_polarity_goes_back_when_the_timer_runs_out(hass, monkeypatch, pairs_follow):
    controller = pair(pairs_follow)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    writes = len(polarity_writes(controller))
    await poll(hass, entry, 1700, controller)
    assert polarities(controller) == (SUPPLY, EXTRACT) and pending(hass) is True
    await poll(hass, entry, 101, controller)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False
    assert len(polarity_writes(controller)) - writes == (1 if pairs_follow else 2)
    assert hass.states.get(_entity(hass, "select", "2_polarity")).state == "supply"
    # Done: the next polls write nothing
    await poll(hass, entry, 60, controller)
    assert len(polarity_writes(controller)) - writes == (1 if pairs_follow else 2)


async def test_the_polarity_goes_back_after_a_cancel(hass, monkeypatch):
    controller = pair(False)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    await _call(hass, "fan", "turn_on", _entity(hass, "fan", "fan"))  # back to the profile
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False


async def test_the_polarity_goes_back_when_another_command_replaces_it(hass, monkeypatch):
    controller = pair(True)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    await _call(hass, DOMAIN, "set_mode", _entity(hass, "fan", "fan"), mode="heat_recovery", speed=3)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False


async def test_a_second_one_way_command_keeps_the_polarity_from_before_the_first(hass, monkeypatch):
    controller = pair(False)  # each on its own: both can blow the same way
    entry = await _setup(hass, monkeypatch, controller)
    one, two = fan_unit(hass, entry, 1), fan_unit(hass, entry, 2)
    await one_way(hass, entry, supply=[one, two])
    assert polarities(controller) == (SUPPLY, SUPPLY)
    await one_way(hass, entry, extract=[one, two])
    assert polarities(controller) == (EXTRACT, EXTRACT)
    assert entry.runtime_data.polarity.pending[1]["previous"] == {1: EXTRACT, 2: SUPPLY}
    await poll(hass, entry, 1801, controller)
    assert polarities(controller) == (EXTRACT, SUPPLY)


async def test_without_restore_the_polarity_stays(hass, monkeypatch):
    controller = pair(True)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], restore_polarity=False)
    assert pending(hass) is False
    await poll(hass, entry, 1801, controller)
    assert polarities(controller) == (SUPPLY, EXTRACT)


async def test_a_partner_that_switched_along_goes_back_too(hass, monkeypatch):
    controller = pair(True)
    entry = await _setup(hass, monkeypatch, controller)
    # Only fan unit 1 is listed; this controller switches fan unit 2 along
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)])
    assert polarities(controller) == (SUPPLY, EXTRACT)
    assert entry.runtime_data.polarity.pending[1]["previous"] == {1: EXTRACT, 2: SUPPLY}
    await poll(hass, entry, 1801, controller)
    assert polarities(controller) == (EXTRACT, SUPPLY)
    assert polarity_writes(controller) == [(1, SUPPLY), (1, EXTRACT)]


async def test_the_polarity_goes_back_at_the_first_poll_after_a_restart(hass, monkeypatch,
                                                                        hass_storage):
    controller = pair(False)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    key = f"{DOMAIN}.{entry.entry_id}.polarity"
    stored = hass_storage[key]["data"]["zones"]["1"]
    assert (stored["previous"], stored["record"]) == ({"1": EXTRACT, "2": SUPPLY}, [5, 2, 2, 1, 1800])
    # A restart while the command runs: it stays as it is
    assert await hass.config_entries.async_unload(entry.entry_id)
    controller.advance(600)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (SUPPLY, EXTRACT) and pending(hass) is True
    # Home Assistant is away while the command ends: back at the first poll after the start
    assert await hass.config_entries.async_unload(entry.entry_id)
    controller.advance(1201)
    writes = len(polarity_writes(controller))
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False
    assert len(polarity_writes(controller)) == writes + 2
    assert hass_storage[key]["data"]["zones"] == {}


async def test_a_command_that_fails_puts_the_polarity_back_at_once(hass, monkeypatch):
    controller = pair(False)
    entry = await _setup(hass, monkeypatch, controller)

    def refused(self, *args, **kwargs):
        raise TimeoutError("no reply")

    monkeypatch.setattr(client.E16Client, "user_override", refused)
    with pytest.raises(HomeAssistantError):
        await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False


async def test_a_failed_restore_is_retried_and_raises_a_repair_issue(hass, monkeypatch, freezer):
    controller = pair(False)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    # The controller takes no polarity writes any more
    write = controller._write_device_field
    monkeypatch.setattr(controller, "_write_device_field", lambda *args: False)
    issue = f"polarity_restore_{entry.entry_id}_1"
    issues = ir.async_get(hass)
    await poll(hass, entry, 1801, controller)
    for delay in (60, 300):
        assert issues.async_get_issue(DOMAIN, issue) is None
        freezer.tick(delay - 1)
        async_fire_time_changed(hass)
        await hass.async_block_till_done(wait_background_tasks=True)
        attempts = len(polarity_writes(controller))
        freezer.tick(1)
        async_fire_time_changed(hass)
        await hass.async_block_till_done(wait_background_tasks=True)
        assert len(polarity_writes(controller)) == attempts + 1  # not before its time
    # The third attempt failed
    assert issues.async_get_issue(DOMAIN, issue).translation_key == "polarity_restore_failed"
    assert pending(hass) is True
    # Back to normal: the next attempt puts it back and the issue goes
    monkeypatch.setattr(controller, "_write_device_field", write)
    freezer.tick(900)
    async_fire_time_changed(hass)
    await hass.async_block_till_done(wait_background_tasks=True)
    assert polarities(controller) == (EXTRACT, SUPPLY) and pending(hass) is False
    assert issues.async_get_issue(DOMAIN, issue) is None


async def test_a_polarity_set_by_hand_is_not_put_back(hass, monkeypatch):
    controller = pair(False)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    await _call(hass, "select", "select_option", _entity(hass, "select", "2_polarity"), option="extract")
    assert entry.runtime_data.polarity.pending[1]["previous"] == {1: EXTRACT}
    await poll(hass, entry, 1801, controller)
    assert polarities(controller) == (EXTRACT, EXTRACT) and pending(hass) is False


async def test_removing_the_entry_puts_the_polarity_back(hass, monkeypatch, hass_storage):
    controller = pair(True)
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 1)], extract=[fan_unit(hass, entry, 2)])
    key = f"{DOMAIN}.{entry.entry_id}.polarity"
    assert key in hass_storage
    assert (await hass.config_entries.async_remove(entry.entry_id))["require_restart"] is False
    await hass.async_block_till_done()
    assert polarities(controller) == (EXTRACT, SUPPLY)
    assert key not in hass_storage


async def test_single_zone(hass, monkeypatch):
    controller = sim.FakeController()  # four fan units in zone 1, all extract
    entry = await _setup(hass, monkeypatch, controller)
    await one_way(hass, entry, supply=[fan_unit(hass, entry, 2), fan_unit(hass, entry, 3)])
    assert polarities(controller, 1, 2, 3, 4) == (EXTRACT, SUPPLY, SUPPLY, EXTRACT)
    assert _last_command(controller) == (client.CMD_ZONE_SPEED_MODE, 2, client.FAN_VENTILATION, 1, 1800)
    await poll(hass, entry, 1801, controller)
    assert polarities(controller, 1, 2, 3, 4) == (EXTRACT,) * 4
