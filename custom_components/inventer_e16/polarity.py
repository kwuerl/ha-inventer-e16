# SPDX-License-Identifier: MIT
"""One-way direction for set_mode: which fan units blow in and which out, and their polarity put
back afterwards.

In ventilation mode a fan unit blows in the direction of its polarity, supply = in (inferred, not
verified on a controller yet). set_mode can list the fan units of the zone that blow in (`supply`)
and out (`extract`): their polarity is written where it differs, then the command is sent. The
previous polarity of every fan unit that changed, a partner that the controller switched along
included (#33), is kept in Home Assistant's storage and written back once the command no longer
runs: timer at 0, cancelled, or replaced by another command. That is checked at every update of
the zones, the first poll after a start included, so a changed polarity doesn't outlast an outage
of Home Assistant longer than needed. Otherwise the profile's own one-way ventilation would blow
the wrong way.
"""
import asyncio
from datetime import timedelta
from functools import partial
import logging

from homeassistant.const import CONF_HOST
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import device_registry as dr, issue_registry as ir
from homeassistant.helpers.event import async_call_later
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .client import (FAN_DEVICE_TYPES, MAX_ZONES, POLARITY_EXTRACT, POLARITY_SUPPLY, E16AuthError,
                     E16Client, command_record)
from .const import CONF_DEVICE_ID, CONF_PSK, DOMAIN
from .sensor import override_active

LOGGER = logging.getLogger(__package__)
STORAGE_VERSION = 1
POLARITIES = (POLARITY_SUPPLY, POLARITY_EXTRACT)
# Seconds to the next attempt after the first, second, ... failed restore; then every 30 minutes
RETRY_DELAYS = (60, 300, 900, 1800)
# A repair issue after this many failed attempts in a row
REPAIR_AFTER = 3
# A command counts as ended this long after its duration, whatever the zone reports
END_MARGIN = timedelta(minutes=1)


def storage_key(entry):
    return f"{DOMAIN}.{entry.entry_id}.polarity"


def issue_id(entry, zone_id):
    return f"polarity_restore_{entry.entry_id}_{zone_id}"


def _address(device, entry):
    """Radio address of a device of this controller (identifier <device id>_<address>), or None."""
    if device is None:
        return None
    prefix = f"{entry.data[CONF_DEVICE_ID]}_"
    for domain, key in device.identifiers:
        if domain == DOMAIN and key.startswith(prefix) and key[len(prefix):].isdigit():
            return int(key[len(prefix):])
    return None


def fan_targets(hass, entry, zone_id, supply, extract):
    """{address: polarity} for the fan units in `supply` and `extract` (device ids). A device that
    is in both lists, isn't a fan unit of this controller or not in the zone is refused."""
    registry = dr.async_get(hass)

    def refuse(key, device_id, **placeholders):
        device = registry.async_get(device_id)
        name = (device.name_by_user or device.name) if device else device_id
        raise ServiceValidationError(translation_domain=DOMAIN, translation_key=key,
                                     translation_placeholders={"device": name, **placeholders})

    if both := sorted(set(supply) & set(extract)):
        refuse("fan_in_both_lists", both[0])
    rows = {row["address"]: row for row in entry.runtime_data.info.data["devices"]}
    targets = {}
    for device_ids, polarity in ((supply, POLARITY_SUPPLY), (extract, POLARITY_EXTRACT)):
        for device_id in device_ids:
            row = rows.get(_address(registry.async_get(device_id), entry))
            if row is None or row["type"] not in FAN_DEVICE_TYPES:
                refuse("not_a_fan_unit", device_id)
            if row["zone"] != zone_id:
                refuse("fan_in_other_zone", device_id, fan_zone=str(row["zone"]), zone=str(zone_id))
            targets[row["address"]] = polarity
    return targets


class PolarityRestore:
    """What goes back per zone, kept in a Store per config entry:
    {zone_id: {"previous": {address: polarity}, "target": {address: polarity},
               "record": (command, speed, mode, zone, duration) or None, "started": datetime}}.

    `record` is the command the polarity was changed for. None means that no such command runs
    (it could not be sent), so the polarity goes back at once."""

    def __init__(self, hass, entry, zones, info):
        self.hass, self.entry, self.zones, self.info = hass, entry, zones, info
        self._store = Store(hass, STORAGE_VERSION, storage_key(entry))
        self.pending = {}
        self._locks = {}
        self._failures = {}
        self._retry_at = {}
        self._retry_timers = {}

    async def async_load(self):
        stored = await self._store.async_load()
        zones = stored.get("zones") if isinstance(stored, dict) else None
        for zone_id, item in (zones if isinstance(zones, dict) else {}).items():
            try:
                previous = {int(a): int(p) for a, p in item["previous"].items()}
                record = tuple(int(value) for value in item["record"]) if item["record"] else None
                if not previous or set(previous.values()) - set(POLARITIES) or (
                        record is not None and len(record) != 5):
                    raise ValueError("nothing to put back")
                self.pending[int(zone_id)] = {
                    "previous": previous,
                    "target": {int(a): int(p) for a, p in (item.get("target") or {}).items()},
                    "record": record,
                    "started": dt_util.parse_datetime(item.get("started") or ""),
                }
            except (AttributeError, KeyError, TypeError, ValueError):
                LOGGER.warning("Stored polarity of zone %s can't be read: %s", zone_id, item)

    async def _async_save(self):
        await self._store.async_save({"zones": {
            str(zone_id): {
                "previous": {str(address): value for address, value in item["previous"].items()},
                "target": {str(address): value for address, value in item["target"].items()},
                "record": list(item["record"]) if item["record"] else None,
                "started": item["started"].isoformat() if item["started"] else None,
            } for zone_id, item in self.pending.items()}})

    def is_pending(self, zone_id):
        return zone_id in self.pending

    def as_dict(self):
        """For the diagnostics."""
        return {zone_id: {**item, "started": item["started"] and item["started"].isoformat()}
                for zone_id, item in self.pending.items()}

    def _lock(self, zone_id):
        return self._locks.setdefault(zone_id, asyncio.Lock())

    def _rows(self):
        return {row["address"]: row for row in (self.info.data or {}).get("devices", ())}

    def _polarity(self, address):
        return self._rows().get(address, {}).get("polarity")

    async def async_set_mode(self, zone_id, targets, restore, record):
        """Write the polarity of the fan units in `targets` ({address: polarity}) where it differs,
        then send the command `record`. With `restore` the previous polarity goes back once the
        command no longer runs. A later call for the zone replaces the command and the targets but
        keeps the polarity from before the first one."""
        try:
            async with self._lock(zone_id):
                await self._async_set_mode(zone_id, targets, restore, tuple(record))
        finally:
            self.async_check()

    async def _async_set_mode(self, zone_id, targets, restore, record):
        # Not from the rows of the last poll: the polarity may have changed in the app since
        await self.info.async_read_devices()
        before = {address: row["polarity"] for address, row in self._rows().items()}
        if unknown := [address for address in targets if before.get(address) is None]:
            raise HomeAssistantError(f"The polarity of fan unit {unknown[0]} is unknown")
        pending = self.pending.get(zone_id)
        previous = dict(pending["previous"]) if pending else {}
        if restore:
            for address, polarity in targets.items():
                if before[address] != polarity:
                    previous.setdefault(address, before[address])
        else:
            # Meant to stay: these fan units don't go back
            for address in targets:
                previous.pop(address, None)
        await self._async_arm(zone_id, previous, targets, record)
        try:
            await self._async_write(targets)
            if restore:
                # A fan unit that switched along with another one (#33) goes back as well
                for address, polarity in before.items():
                    if polarity is not None and self._polarity(address) != polarity:
                        previous.setdefault(address, polarity)
                if previous != (self.pending.get(zone_id) or {}).get("previous"):
                    await self._async_arm(zone_id, previous, targets, record)
            await self.zones.async_override(*record)
        except HomeAssistantError:
            if zone_id in self.pending:
                # Nothing of ours runs: the polarity goes back right away
                self.pending[zone_id]["record"] = None
                await self._async_save()
            raise

    async def _async_arm(self, zone_id, previous, targets, record):
        """Keep what goes back, and for which command, before anything is written."""
        if previous:
            self.pending[zone_id] = {"previous": dict(previous), "target": dict(targets),
                                     "record": record, "started": dt_util.utcnow()}
        else:
            self.pending.pop(zone_id, None)
            ir.async_delete_issue(self.hass, DOMAIN, issue_id(self.entry, zone_id))
        self._clear_retries(zone_id)
        await self._async_save()
        self.zones.async_update_listeners()

    async def _async_write(self, targets):
        """Write the fan units whose polarity differs, one after the other. Every write reads all
        rows back, so a fan unit that switched along with another one isn't written again, and in
        the end only what the rows say counts."""
        for address, polarity in list(targets.items()):
            if self._polarity(address) != polarity:
                await self.info.async_set_polarity(address, polarity)
        if wrong := [address for address, polarity in targets.items()
                     if self._polarity(address) != polarity]:
            raise HomeAssistantError(
                f"The controller keeps another polarity for fan unit {wrong[0]}")

    def _due(self, zone_id, now):
        """Whether the command the polarity was changed for no longer runs."""
        item = self.pending[zone_id]
        if (record := item["record"]) is None:
            return True
        if item["started"] and now > item["started"] + timedelta(seconds=record[4]) + END_MARGIN:
            return True
        if (zone := (self.zones.data or {}).get(zone_id)) is None:
            return True
        if (last := zone.get("last_override")) is None:
            # This controller doesn't report the command: the timer tells
            return not zone["timer"]
        return command_record(last) != record or not override_active(
            last, zone, self.zones.ended.get(zone_id))

    @callback
    def async_check(self):
        """From the zone update handling: the polarity goes back in the zones whose command has
        ended."""
        now = dt_util.utcnow()
        for zone_id in list(self.pending):
            if zone_id not in self.pending or self._lock(zone_id).locked() or (
                    zone_id in self._retry_at and now < self._retry_at[zone_id]):
                continue
            if self._due(zone_id, now):
                self.entry.async_create_background_task(
                    self.hass, self._async_restore(zone_id), f"inventer_e16 polarity {zone_id}")

    async def _async_restore(self, zone_id):
        async with self._lock(zone_id):
            if zone_id not in self.pending or not self._due(zone_id, dt_util.utcnow()):
                return
            previous = self.pending[zone_id]["previous"]
            try:
                await self.info.async_read_devices()
                rows = self._rows()
                for address in [a for a in previous
                                if rows.get(a, {}).get("type") not in FAN_DEVICE_TYPES]:
                    LOGGER.warning("Fan unit %s is gone, its polarity can't be put back", address)
                    del previous[address]
                await self._async_write(previous)
            except HomeAssistantError as error:
                self._failed(zone_id, error)
                return
            del self.pending[zone_id]
            self._clear_retries(zone_id)
            ir.async_delete_issue(self.hass, DOMAIN, issue_id(self.entry, zone_id))
            await self._async_save()
        LOGGER.info("Polarity of the fan units in zone %s put back", zone_id)
        self.zones.async_update_listeners()

    def _failed(self, zone_id, error):
        failures = self._failures[zone_id] = self._failures.get(zone_id, 0) + 1
        delay = RETRY_DELAYS[min(failures, len(RETRY_DELAYS)) - 1]
        LOGGER.warning("Putting back the polarity in zone %s failed (attempt %s), next attempt in "
                       "%s s: %s", zone_id, failures, delay, error)
        self._retry_at[zone_id] = dt_util.utcnow() + timedelta(seconds=delay)
        if unsub := self._retry_timers.pop(zone_id, None):
            unsub()
        self._retry_timers[zone_id] = async_call_later(
            self.hass, delay, partial(self._async_retry, zone_id))
        if failures >= REPAIR_AFTER:
            rows = self._rows()
            fans = ", ".join(rows.get(address, {}).get("name") or str(address)
                             for address in self.pending[zone_id]["previous"])
            ir.async_create_issue(
                self.hass, DOMAIN, issue_id(self.entry, zone_id), is_fixable=False,
                severity=ir.IssueSeverity.WARNING, translation_key="polarity_restore_failed",
                translation_placeholders={"zone": str(zone_id), "fans": fans})

    @callback
    def _async_retry(self, zone_id, _now):
        self._retry_at.pop(zone_id, None)
        self._retry_timers.pop(zone_id, None)
        self.async_check()

    def _clear_retries(self, zone_id):
        self._failures.pop(zone_id, None)
        self._retry_at.pop(zone_id, None)
        if unsub := self._retry_timers.pop(zone_id, None):
            unsub()

    async def async_forget(self, address):
        """The polarity of a fan unit was set by hand: it stays as it is now."""
        for zone_id in [z for z, item in self.pending.items() if address in item["previous"]]:
            async with self._lock(zone_id):
                if address not in (self.pending.get(zone_id) or {}).get("previous", {}):
                    continue
                del self.pending[zone_id]["previous"][address]
                if not self.pending[zone_id]["previous"]:
                    del self.pending[zone_id]
                    self._clear_retries(zone_id)
                    ir.async_delete_issue(self.hass, DOMAIN, issue_id(self.entry, zone_id))
                await self._async_save()
            self.zones.async_update_listeners()

    @callback
    def async_unload(self):
        for unsub in self._retry_timers.values():
            unsub()
        self._retry_timers.clear()


def _restore_now(client, previous):
    rows = {row["address"]: row for row in client.get_devices()}
    for address, polarity in previous.items():
        row = rows.get(address)
        if row and row["type"] in FAN_DEVICE_TYPES and row["polarity"] != polarity:
            rows = {row["address"]: row for row in client.set_polarity(address, polarity)}


async def async_remove(hass, entry):
    """The entry is removed: what is still pending goes back now (best effort), and its storage
    and repair issues go."""
    store = Store(hass, STORAGE_VERSION, storage_key(entry))
    stored = await store.async_load()
    try:
        previous = {int(address): int(polarity) for item in stored["zones"].values()
                    for address, polarity in item["previous"].items()}
    except (AttributeError, KeyError, TypeError, ValueError):
        previous = {}
    if previous:
        try:
            await hass.async_add_executor_job(
                _restore_now, E16Client(entry.data[CONF_HOST], entry.data[CONF_PSK]), previous)
        except (E16AuthError, OSError, ValueError) as error:
            LOGGER.warning("The polarity of fan units %s could not be put back: %s",
                           sorted(previous), error)
    await store.async_remove()
    for zone_id in range(1, MAX_ZONES + 1):
        ir.async_delete_issue(hass, DOMAIN, issue_id(entry, zone_id))
