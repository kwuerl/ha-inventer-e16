# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Korbinian Würl
"""Replaces the integration's __init__.py in the test instance: the real config flow stays, the
setup only registers the controller device as the real one does, without polling the stand-in
controller."""
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr

from .const import CONF_DEVICE_ID, DOMAIN


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id, identifiers={(DOMAIN, entry.data[CONF_DEVICE_ID])},
        name=entry.title, manufacturer="inVENTer", model="Easy Connect e16")
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    return True
