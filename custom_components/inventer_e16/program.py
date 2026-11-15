# SPDX-License-Identifier: MIT
"""Time program of a ventilation profile as a week - no Home Assistant imports.

A profile has up to 32 entries: a start time in seconds of the day, the weekdays it applies to
(bit 0 Monday ... bit 6 Sunday), a fan mode and a speed. How the controller runs them is inferred,
not verified on a controller: an entry switches to its mode and speed at its start time on each of
its days, and that lasts until the next entry switches - across midnight, and from Sunday to
Monday. Of two entries with the same start time on the same day, the one with the higher number
is taken (assumed).
"""
from datetime import datetime, time, timedelta

DAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
# Fan modes of the entries (the table of the UserOverride command) as the keys of set_mode
MODES = {1: "heat_recovery", 2: "ventilation", 3: "pause", 4: "pause_open"}
DAY = 86400
WEEK = 7 * DAY


def clock(seconds):
    """Seconds of the day as "HH:MM"; the end of the day is "24:00"."""
    return f"{seconds // 3600:02}:{seconds % 3600 // 60:02}"


def entry(index, item):
    """One used entry as in the response of get_time_program."""
    return {"entry": index, "time": clock(item["seconds"]),
            "days": [day for bit, day in enumerate(DAYS) if item["days"] >> bit & 1],
            "mode": MODES.get(item["mode"], item["mode"]), "speed": item["speed"]}


def _segment(start, end, state):
    mode, speed = state
    return {"start": clock(start), "end": clock(end), "mode": MODES.get(mode, mode), "speed": speed}


class Program:
    """The used entries of a profile's time program and the week they make."""

    def __init__(self, entries):
        """`entries`: the 32 entries as the client reads them, None for empty ones."""
        self.entries = [entry(index, item) for index, item in enumerate(entries) if item]
        switches = {}
        for item in entries:
            # A start time outside the day can't be placed (only from a faulty reply)
            if item and 0 <= item["seconds"] < DAY:
                for day in range(7):
                    if item["days"] >> day & 1:
                        switches[day * DAY + item["seconds"]] = (item["mode"], item["speed"])
        # (second of the week, (mode, speed)), one switch per second: the later entry replaced it
        self._switches = sorted(switches.items())
        self._days = [self._day(number) for number in range(7)]
        self.week = {day: [_segment(*segment) for segment in self._days[number]]
                     for number, day in enumerate(DAYS)}

    @property
    def has_program(self):
        return bool(self._switches)

    def _state(self, second):
        """Mode and speed at a second of the week: set by the last switch at or before it, before
        the first switch of the week by the last one (weekly wrap)."""
        state = self._switches[-1][1]
        for at, value in self._switches:
            if at > second:
                break
            state = value
        return state

    def _day(self, number):
        """Segments of a day as [start, end, (mode, speed)] in seconds of the day, from 0 to 24 h
        without gaps; neighbours with the same mode and speed are merged."""
        if not self._switches:
            return []
        start = number * DAY
        segments = [[0, DAY, self._state(start)]]
        for at, value in self._switches:
            if start < at < start + DAY and value != segments[-1][2]:
                segments[-1][1] = at - start
                segments.append([at - start, DAY, value])
        return segments

    def current(self, now):
        """The segment running at `now` (local time, timezone-aware), or None without a program."""
        second = now.hour * 3600 + now.minute * 60 + now.second
        for start, end, state in self._days[now.weekday()]:
            if start <= second < end:
                return _segment(start, end, state)
        return None

    def next(self, now):
        """The next change of mode or speed after `now`: {"at": ISO 8601, "mode", "speed"}, or None
        if the program never changes."""
        if not self._switches:
            return None
        second = now.weekday() * DAY + now.hour * 3600 + now.minute * 60 + now.second
        state = self._state(second)
        # The rest of this week, then next week up to now; seconds counted from this Monday 00:00
        ahead = [(at, value) for at, value in self._switches if at > second]
        ahead += [(at + WEEK, value) for at, value in self._switches if at <= second]
        for at, value in ahead:
            if value != state:
                days, offset = divmod(at, DAY)
                # Wall-clock time on that date, so that the offset is right across a DST change
                moment = datetime.combine(
                    now.date() + timedelta(days=days - now.weekday()),
                    time(offset // 3600, offset % 3600 // 60, offset % 60), tzinfo=now.tzinfo)
                mode, speed = value
                return {"at": moment.isoformat(), "mode": MODES.get(mode, mode), "speed": speed}
        return None
