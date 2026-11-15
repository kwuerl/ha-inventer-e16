# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Korbinian Würl
"""Services for HomeAssistantIntegrationTest: simulate a DHCP discovery and a re-authentication
request of inventer_e16, and remove its entries between test steps."""
from homeassistant.config_entries import SOURCE_DHCP
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.helpers.service_info.dhcp import DhcpServiceInfo

DOMAIN = "inventer_e16"


async def async_setup(hass: HomeAssistant, config) -> bool:
    async def start_dhcp(call: ServiceCall) -> None:
        await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": SOURCE_DHCP},
            data=DhcpServiceInfo(ip=call.data["ip"], hostname=call.data["hostname"], macaddress="6825dd000001"))

    async def start_reauth(call: ServiceCall) -> None:
        for entry in hass.config_entries.async_entries(DOMAIN):
            entry.async_start_reauth(hass)

    async def remove_entries(call: ServiceCall) -> None:
        for entry in hass.config_entries.async_entries(DOMAIN):
            await hass.config_entries.async_remove(entry.entry_id)

    hass.services.async_register("test_helpers", "start_dhcp", start_dhcp)
    hass.services.async_register("test_helpers", "start_reauth", start_reauth)
    hass.services.async_register("test_helpers", "remove_entries", remove_entries)
    return True
