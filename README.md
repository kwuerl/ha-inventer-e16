# inVENTer Easy Connect e16 for Home Assistant

Local control of inVENTer decentralised ventilation through the **Easy Connect e16** controller:
over your Wi-Fi, without the manufacturer's cloud.

> **Unofficial.** Not affiliated with, endorsed or supported by inVENTer GmbH. Use at your own
> risk, see [Disclaimer](#disclaimer).

## Features

- **Control:** a fan entity with speed 1–4 and the modes *heat recovery* and *ventilation*.
  Off pauses the ventilation until you switch it on again; on returns to the profile. Buttons
  *Boost 15 min*, *Pause 1 h* and *Resume profile*. For ventilation, the action `set_mode` can
  also set which fan units blow in and which out, and puts their polarity back afterwards (see
  [One-way direction](#one-way-direction)).
- **Profiles:** select the ventilation profile and set its comfort values (indoor and outdoor
  comfort temperature, humidity threshold, CO₂/VOC threshold if such a sensor is installed).
  Custom profiles can be selected once they have a time program.
- **Sensors:** speed, mode, playback, timer, indoor and outdoor temperature and humidity, CO₂/VOC,
  system status and last command. Calculated: dew points, absolute humidity, and *Ventilation
  dries*, which tells whether outdoor air would actually remove moisture (useful for cellars).
- **Zones:** on controllers with several zones, every zone becomes its own device with its own
  fan, sensors, profile and buttons (see [Several zones](#several-zones)).
- **Radio devices:** every fan and sensor becomes its own device with radio signal (not for
  devices on a cable), runtime, temperature, humidity, battery and problem flags. The polarity of
  a fan (whether it starts with supply or extract air) can be changed.
- **Maintenance:** filter change and service with due date, days left, interval and *confirm*
  buttons.
- **Firmware:** update entities for the Wi-Fi module, controller, fans and sensors. Installing
  updates stays with the official app.
- **Time programs:** a *Time program* sensor per zone shows the program of the active profile as
  a week, what runs now and the next change (see [Time program sensor](#time-program-sensor)).
  The actions read and edit the programs of every profile (see [Actions](#actions)).
- **Diagnostics:** fan power per speed level, reversal interval, and a diagnostics download with
  PSK, host and device ID redacted.

## Requirements

- Easy Connect e16 with Wi-Fi module, connected to your network with the official app.
- Home Assistant 2026.9 or newer.
- Once, to read the device key: a phone with a Bluetooth app, or a computer with Bluetooth LE and
  Python.

## Installation

### 1. Install the integration

[![Open in Marketplace](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=tobiask1977&repository=ha-inventer-e16&category=integration)

Home Assistant 2026.11 or newer: *Settings → Marketplace*, search for **inVENTer Easy Connect
e16** and install it. On older versions, download it from HACS and restart Home Assistant.

Manual installation: copy `custom_components/inventer_e16` to `/config/custom_components/` and
restart.

### 2. Read the device ID and PSK (once)

The controller encrypts the local connection with a key (PSK) that is different for every
device. The official app never shows it, but the controller hands it out over Bluetooth while it
is in pairing mode. **[docs/onboarding.md](docs/onboarding.md)** shows both ways step by step:
with a free Bluetooth app on your phone, or with the Python tool in this repository:

```bash
pip install -r tools/requirements.txt
# hold the mode button on the controller until the LED blinks blue, then:
python tools/e16_onboard.py read
```

Reading changes nothing on the controller, and the official app keeps working. Keep the PSK
private: anyone on your network who has it can control the ventilation.

### 3. Add the controller

Home Assistant usually discovers the controller by itself and fills in host and device ID, so you
only enter the PSK. Otherwise go to *Settings → Devices & services → Add integration →
inVENTer Easy Connect e16* and enter host, device ID and PSK.

Reserve the controller's IP address in your router. If it changes anyway, Home Assistant usually
picks up the new one by itself. For a new address or key (for example after a factory reset), use
**Reconfigure** on the integration. If the controller rejects the stored key, Home Assistant asks
for the new one.

**Options:** how often the status is polled (15–3600 s, default 30 s; it is read again right after
every command) and the firmware check (see [Privacy](#privacy)).

## Usage

Speed and mode changes are **temporary**, as in the app. The *Command duration* entity
(15 min–8 h) sets how long they last; after that the controller returns to its profile. The
*Timer* sensor shows the time left, and its attribute `ends_at` when the command ends. For
permanent changes, select another profile or edit its time program.

### Several zones

With several zones, every zone with fans or sensors assigned or with a name, zone 1 included,
becomes its own device below the controller. A named zone stays even without devices, so fans can
be moved to it, as in the app. Each zone has its own fan, buttons, profile, command duration and
sensors, and its commands go to that zone only. The fans of a zone always run together, so there
are no controls for single fans. The controller device, then named *inVENTer Easy Connect e16*,
keeps what applies to all zones: filter and service, firmware, Wi-Fi, alarms and the outdoor
values. Zone 1 always has the indoor values; zones 2–4 only with an indoor sensor of their own.

- With a single zone, the controller device carries the zone, and switching the fan off or on
  and the buttons send the app's commands for all zones, as before.
- When a second zone comes into use, zone 1's entities move to the new zone 1 device. They keep
  their entity IDs, and the zone 1 device takes over the controller's area. A name you gave the
  controller device stays with the controller.
- While a pause or boost for all zones is running (for example started in the app), switching a
  zone on or *Resume profile* ends it in all zones.
- Time programs belong to a profile, not to a zone: editing one changes every zone that uses
  that profile.
- Every fan unit has a *Zone* select (only with several zones), and the action
  `inventer_e16.set_device_zone` moves a fan unit or indoor sensor to another zone. The controller
  and the outdoor sensor can't be moved.
- A fan moved between zones in use, here or in the app, moves below its new zone without a
  reload. When a radio device is added or removed, a zone comes into use or goes out of use, or an
  indoor sensor moves, the integration reloads by itself. Zones and devices the controller no
  longer reports can be deleted on their device page.

### Time program sensor

Every zone has a *Time program* sensor. Its state is the zone's active profile (the same keys as
the profile select), its attributes are the time program of that profile:

| Attribute | Content |
|---|---|
| `has_program` | Whether the profile has a time program: `false` for the automatic, summer, winter and cellar profiles and for an empty program; `null` until it has been read once |
| `entries` | The used entries, as `inventer_e16.get_time_program` returns them |
| `week` | For each day (`monday` … `sunday`) the segments from `00:00` to `24:00` with `start`, `end`, `mode` and `speed`. A day starts with what the last entry before it set, Monday with Sunday's |
| `current` | The segment that runs now |
| `next` | The next change of mode or speed: `at` (ISO date and time), `mode`, `speed` |
| `read_at` | When the program was read |

The integration reads the program in the background: after a start, when a zone switches to
another profile, after a change through the actions, and once a day. Every profile gets a
connection of its own, and a profile that several zones use is read once. `current` and `next`
follow the clock without asking the controller. Speed and mode commands are not part of the
program: the sensor shows what the profile would run.

### Actions

| Action | What it does |
|---|---|
| `inventer_e16.set_mode` | Temporary mode, speed and duration in one call; with ventilation also which fan units blow in and out |
| `inventer_e16.get_time_program` | Returns the time program of a profile (default: the current one) |
| `inventer_e16.set_time_program_entry` | Writes one entry (0–31) of a profile's time program and reads it back |
| `inventer_e16.delete_time_program_entry` | Deletes one entry |
| `inventer_e16.get_devices` | Returns the radio devices (address, type, zone, signal, firmware, values) and the zones in use |
| `inventer_e16.set_device_zone` | Moves a fan unit or indoor sensor (address from `get_devices`) to another zone (1–4) and reads it back |

A pause that ends by itself after 30 minutes:

```yaml
action: inventer_e16.set_mode
target:
  entity_id: fan.my_inventer
data:
  mode: pause          # heat_recovery | ventilation | pause | pause_open
  minutes: 30
```

Read a time program. Each entry has `time`, `days`, `mode` and `speed`:

```yaml
action: inventer_e16.get_time_program
target:
  entity_id: fan.my_inventer
data:
  profile: bedroom     # bedroom … holiday, custom_1 … custom_9
response_variable: program
```

Change one entry. It applies whenever that profile is active:

```yaml
action: inventer_e16.set_time_program_entry
target:
  entity_id: fan.my_inventer
data:
  profile: custom_1
  entry: 0
  time: "06:30"
  days: [monday, tuesday, wednesday, thursday, friday]
  mode: heat_recovery
  speed: 2
```

There is no undo: read the program first and keep a copy. The automatic and cellar profiles have
no time program.

### One-way direction

In ventilation mode every fan unit blows in one direction, and which one is its polarity: *supply*
blows air in, *extract* blows it out. With `supply` and `extract`, `set_mode` sets them for the
command, for example for a room that should get fresh air while the other room of the zone lets
it out:

```yaml
action: inventer_e16.set_mode
target:
  entity_id: fan.my_inventer
data:
  mode: ventilation
  speed: 3
  minutes: 30
  supply:                  # device IDs of fan units of this zone
    - 0123456789abcdef0123456789abcdef
  extract:
    - fedcba9876543210fedcba9876543210
```

- Only with `mode: ventilation`, only fan units of the zone the fan entity belongs to, and no
  fan unit in both lists. Fan units that are not listed are not written, but a controller that
  switches the partner of a pair along may change them.
- The integration reads the polarity of the fan units first and writes it only where it differs,
  one fan unit after the other; then it sends the command. Some controllers switch the other fan
  of a pair along, others don't (#38): it always checks what the controller reports afterwards.
- When the command no longer runs (its time is up, the fan is switched on or off, or another
  command replaces it), the integration writes the previous polarity back, a partner that was
  switched along included. Otherwise the one-way ventilation of the profile's time program would
  blow the wrong way. Home Assistant keeps what has to go back across restarts and puts it back at
  the first poll after a start if the command has ended meanwhile. A failed attempt is retried
  after 1, 5, 15 and then every 30 minutes; after three failures a repair issue appears.
- `restore_polarity: false` keeps the new polarity. Setting a fan unit's polarity by hand while a
  restore is pending keeps that fan unit as you set it. A later `set_mode` with directions for the
  same zone replaces the command but still puts back the polarity from before the first one.
- The *Last command* sensor of the zone shows `polarity_restore_pending` while something still
  has to go back. Removing the integration puts it back right away.

## Privacy

Everything runs locally over TCP port 47820. The only exception is the optional firmware check.
When you switch it on in the options, the integration asks the manufacturer's firmware catalog
once a day which versions are current. It sends only the brand name, but the manufacturer's cloud
sees your public IP address and Home Assistant version. Without the check, the firmware entities
show the installed version only.

The check is off for installations set up with 0.3.14 or later. Older installations keep it on
until you switch it off.

## Limitations

- Support for several zones has been confirmed on installations with three and four zones;
  feedback in [#22](https://github.com/tobiask1977/ha-inventer-e16/issues/22) is welcome. Moving
  fans between zones has been confirmed on two of them.
- Custom profiles cannot be created or renamed (their time programs can be edited). Which ones
  have a time program is read once a day and after a change through the actions, so a program
  created in the app can take up to a day to show up. Reading takes up to a few seconds per
  profile; polls and commands wait for it. The same goes for the time program sensor: a program
  changed in the app shows with the daily read, or when a zone switches to that profile.
- How the controller runs a time program (an entry lasts until the next one, across midnight and
  from Sunday to Monday) is our reading of the entries, not confirmed on a controller.
- The polarity behaves differently between installations. On one controller (a pair with one fan
  on a cable), changing one fan switched the other fan of the pair along. On another (two zones with
  a pair each, #38), every fan switches on its own, so both fans of a pair can have the same
  polarity, e.g. for cross ventilation. The integration only writes the fan you change and then
  shows what the controller reports for all fans.
- That a fan unit blows air in with the polarity *supply* in ventilation mode, and the one-way
  direction of `set_mode` built on it, have not been confirmed on a controller yet.
- Firmware cannot be installed from Home Assistant.
- Developed on one installation with two iV14 Zero fans and an indoor and outdoor sensor. Other
  fans on the e16 should work, but this hasn't been confirmed.

Something doesn't work, or your setup is different? Open an issue and attach the diagnostics
download, see [CONTRIBUTING.md](CONTRIBUTING.md).

## How it works

[docs/protocol.md](docs/protocol.md) describes the packet formats and what has been verified on a
real controller.

## Development

```bash
pip install pytest
pytest tests --ignore=tests/ha          # protocol tests, any OS
pip install -r requirements_test.txt    # Python 3.14.2 or newer
pytest tests/ha                         # Home Assistant tests, Linux/macOS
```

`requirements_test.txt` pins `pytest-homeassistant`, Home Assistant core's test package, which
installs the Home Assistant version the tests run against. Upgrade it with every Home Assistant
release.

The protocol client (`custom_components/inventer_e16/client.py`) has no Home Assistant
dependency and can be used on its own.

## License

[MIT](LICENSE), Copyright (c) 2026 Tobias Krautkremer, Markus Hennig.

"inVENTer" and "Easy Connect" are trademarks of their owner and are used here only to say which
devices this software works with. This repository contains no code, images, logos or other
material from the manufacturer or its app. The icon in `custom_components/inventer_e16/brand/` is
an original design for this project.

## Disclaimer

This is an independent, unofficial project, provided **as is, without warranty of any kind**. It
was developed by analysing the device's communication for interoperability, and you use it **at
your own risk**:

- Commands change how your ventilation runs. Wrong settings over a longer time can affect indoor
  air quality and humidity (risk of condensation and mould). Check the result.
- Using unofficial software may affect warranty or support claims against the manufacturer.
- The manufacturer can change the firmware or its cloud services at any time, which may break this
  integration without notice.

To the extent permitted by law, the authors are not liable for damage to devices, buildings,
health or data, or for any other loss arising from the use of this software. This does not limit
liability for intent or gross negligence, or for injury to life, body or health.
