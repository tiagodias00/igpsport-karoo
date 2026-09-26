"""iGPSPORT VS-series light protocol: Nordic UART + 20-byte header + protobuf payload.

Mirror of the Kotlin :protocol module. Reverse-engineered by cparfait/Bike-Light-Control (MIT)
from the iGPSPORT Ride app and an HCI capture of a VS1800S.
"""
import re

UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
UART_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
UART_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
ADVERT_MARKER = "a238c112-8136-52a9-364b-d61ac015024e"
BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

HEADER_LEN = 20
TYPE_DATA, TYPE_ACK, TYPE_STATE = 1, 2, 3
SERVICE_LIGHT = 106
OP_WRITE, OP_READ = 1, 2
SUB_MODE_SUPPORTED, SUB_MODE_CURRENT, SUB_REMAINING_TIME, SUB_BATTERY, SUB_MODE_ENABLE = 1, 2, 5, 6, 7
SUB_CUSTOM_MODE = 3
F_CUSTOM_MODE_GET, F_CUSTOM_MODE_ARG, F_CUSTOM_MODE_MODIFY = 7, 8, 12
STEADY, FLASH, BREATH = 0, 1, 2  # BLE_LIGHT_CUSTOM_SUBTYPE

MODE_LABELS = {
    0: "OFF", 1: "HIGH", 2: "MID", 3: "LOW", 4: "FLASH HI", 5: "FLASH LO", 6: "PULSE",
    7: "HB HIGH", 8: "HB MID", 9: "HB LOW", 10: "LB HIGH", 11: "LB MID", 12: "LB LOW",
    16: "BOOST", 17: "SOS",
}
_MODEL_NAME = re.compile(r"^(VS|TL)\d", re.IGNORECASE)


def crc8_maxim(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8C if crc & 1 else crc >> 1
    return crc


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        low = value & 0x7F
        value >>= 7
        if value:
            out.append(low | 0x80)
        else:
            out.append(low)
            return bytes(out)


def varint_field(field: int, value: int) -> bytes:
    return varint(field << 3) + varint(value)


def message_field(field: int, inner: bytes) -> bytes:
    return varint((field << 3) | 2) + varint(len(inner)) + inner


def parse_proto(data: bytes):
    """One message level -> {field: [int | bytes, ...]}; None if malformed or unsupported wire type."""
    fields, pos = {}, 0

    def read_varint():
        nonlocal pos
        shift, acc = 0, 0
        while pos < len(data) and shift < 64:
            b = data[pos]
            pos += 1
            acc |= (b & 0x7F) << shift
            if not b & 0x80:
                return acc
            shift += 7
        return None

    while pos < len(data):
        key = read_varint()
        if key is None:
            return None
        field, wire = key >> 3, key & 7
        if wire == 0:
            value = read_varint()
            if value is None:
                return None
        elif wire == 2:
            length = read_varint()
            if length is None or pos + length > len(data):
                return None
            value = data[pos:pos + length]
            pos += length
        else:
            return None
        fields.setdefault(field, []).append(value)
    return fields


def message(sub: int, op: int, body: bytes = b"") -> bytes:
    payload = varint_field(1, SERVICE_LIGHT) + varint_field(2, op) + varint_field(3, sub) + body
    header = bytearray([TYPE_DATA, SERVICE_LIGHT, sub, 0xFF, op, 0xFF, 0xFF, 0x00,
                        len(payload), crc8_maxim(payload), 0x01] + [0xFF] * 8)
    header.append(crc8_maxim(header))
    return bytes(header) + payload


def read_supported_modes() -> bytes:
    return message(SUB_MODE_SUPPORTED, OP_READ)


def read_current_mode() -> bytes:
    return message(SUB_MODE_CURRENT, OP_READ)


def read_remaining_time() -> bytes:
    return message(SUB_REMAINING_TIME, OP_READ)


def read_battery() -> bytes:
    return message(SUB_BATTERY, OP_READ)


def set_mode(mode: int) -> bytes:
    inner = b"" if mode == 0 else varint_field(1, mode)  # 0 is the protobuf default -> empty
    return message(SUB_MODE_CURRENT, OP_WRITE, message_field(13, inner))


def set_mode_enabled(mode: int, enabled: bool) -> bytes:
    inner = varint_field(1, mode) + (varint_field(2, 1) if enabled else b"")
    return message(SUB_MODE_ENABLE, OP_WRITE, message_field(11, inner))


def _optional(field: int, value: int) -> bytes:
    """proto3: a zero value is left out of the frame."""
    return varint_field(field, value) if value else b""


def read_custom_mode(mode: int) -> bytes:
    return message(SUB_CUSTOM_MODE, OP_READ, message_field(F_CUSTOM_MODE_GET, varint_field(1, mode)))


def modify_custom_mode(mode: int, subtype: int, light=None, cycle=None, ratio=None) -> bytes:
    """One change to custom mode `mode`, playing `subtype`. light=(light_num, pct). At most one change."""
    if sum(x is not None for x in (light, cycle, ratio)) > 1:
        raise ValueError("one change per write")
    inner = varint_field(1, mode) + _optional(2, subtype)
    if light is not None:
        inner += message_field(3, _optional(1, light[0]) + _optional(2, light[1]))
    if cycle is not None:
        inner += message_field(4, _optional(1, cycle))
    if ratio is not None:
        inner += message_field(5, _optional(1, ratio))
    return message(SUB_CUSTOM_MODE, OP_WRITE, message_field(F_CUSTOM_MODE_MODIFY, inner))


def _inner_int(values):
    """First value of a one-int sub-message field ({1: n}); None if the field is absent."""
    if not values or not isinstance(values[0], bytes):
        return None
    return _first(parse_proto(values[0]) or {}, 1) or 0


def parse_custom_mode(arg: bytes):
    fields = parse_proto(arg)
    if not fields or _first(fields, 1) is None:
        return None
    patterns = []
    for raw in fields.get(3, []):
        cfg = parse_proto(raw) if isinstance(raw, bytes) else None
        if cfg is None:
            continue
        lights = []
        for entry in cfg.get(2, []):
            e = parse_proto(entry) or {}
            lights.append((_first(e, 1) or 0, _first(e, 2) or 0))
        patterns.append({"subtype": _first(cfg, 1) or 0, "lights": lights,
                         "cycle": _inner_int(cfg.get(3)), "ratio": _inner_int(cfg.get(4))})
    return {"mode": _first(fields, 1), "selected": _first(fields, 2) or 0, "patterns": patterns}


def ack_status(frame: bytes):
    """(sub-service, status) of an ACK frame; status 0 = success. None for any other frame."""
    if not header_valid(frame) or frame[0] != TYPE_ACK:
        return None
    return frame[2], frame[7]


def chunks(frame: bytes, size: int = 20) -> list:
    return [frame[i:i + size] for i in range(0, len(frame), size)]


def header_valid(data: bytes) -> bool:
    return len(data) >= HEADER_LEN and crc8_maxim(data[:HEADER_LEN - 1]) == data[HEADER_LEN - 1]


def expected_length(data: bytes) -> int:
    if len(data) >= HEADER_LEN and data[0] == TYPE_DATA:
        return HEADER_LEN + data[8]
    return HEADER_LEN


def _first(fields, n):
    values = fields.get(n)
    return values[0] if values else None


def parse_frame(frame: bytes):
    if not header_valid(frame):
        return None
    kind, sub = frame[0], frame[2]
    if kind == TYPE_STATE:
        if sub == SUB_MODE_CURRENT:
            return {"mode": frame[7]}
        if sub == SUB_REMAINING_TIME:
            return {"remaining_minutes": frame[11] | frame[12] << 8 | frame[13] << 16}
        return None
    if kind != TYPE_DATA:
        return None
    length = frame[8]
    if len(frame) < HEADER_LEN + length:
        return None
    payload = frame[HEADER_LEN:HEADER_LEN + length]
    if crc8_maxim(payload) != frame[9]:
        return None
    fields = parse_proto(payload)
    if fields is None:
        return None
    out = {}
    cur = _first(fields, 13)
    if isinstance(cur, bytes):
        inner = parse_proto(cur) or {}
        out["mode"] = _first(inner, 1) or 0
    bat = _first(fields, 15)
    if isinstance(bat, bytes):
        out["battery"] = _first(parse_proto(bat) or {}, 13)
    left = _first(fields, 14)
    if isinstance(left, bytes):
        out["remaining_minutes"] = _first(parse_proto(left) or {}, 1)
    if 6 in fields:
        declared = {}
        for entry in fields[6]:
            e = parse_proto(entry) if isinstance(entry, bytes) else None
            if e and _first(e, 1) is not None:
                declared[_first(e, 1)] = bool(_first(e, 3) or 0)
        out["declared_modes"] = declared
    arg = _first(fields, F_CUSTOM_MODE_ARG)
    if isinstance(arg, bytes):
        out["custom_mode"] = parse_custom_mode(arg)
    return out


class FrameAssembler:
    """Reassembles <=20-byte notification fragments into whole frames."""

    def __init__(self):
        self._buf = b""

    def reset(self):
        self._buf = b""

    def push(self, fragment: bytes) -> list:
        self._buf += fragment
        if len(self._buf) > 1024:
            self._buf = b""
            return []
        frames = []
        while len(self._buf) >= HEADER_LEN:
            if not header_valid(self._buf):
                self._buf = self._buf[1:]  # resync one byte at a time
                continue
            need = expected_length(self._buf)
            if len(self._buf) < need:
                break
            frames.append(self._buf[:need])
            self._buf = self._buf[need:]
        return frames


def label(mode: int) -> str:
    if mode in MODE_LABELS:
        return MODE_LABELS[mode]
    if 64 <= mode <= 75:
        return f"CUSTOM {mode - 63}"
    return f"MODE {mode}"


def looks_like_igps(name, uuids) -> bool:
    if name and name.upper().endswith("_U"):
        return False
    if any(u.lower() == ADVERT_MARKER for u in uuids or []):
        return True
    return bool(name) and (bool(_MODEL_NAME.match(name)) or "IGPSPORT" in name.upper())
