import igps

H = bytes.fromhex

READ_CURRENT = H("016A02FF02FFFF00065C01FFFFFFFFFFFFFFFF72086A10021802")
SET_12 = H("016A02FF01FFFF000A9C01FFFFFFFFFFFFFFFF11086A10011802" "6A02080C")
READ_SUPPORTED = H("016A01FF02FFFF0006BE01FFFFFFFFFFFFFFFF65086A10021801")
READ_BATTERY = H("016A06FF02FFFF00063D01FFFFFFFFFFFFFFFFB1086A10021806")
READ_LEFT = H("016A05FF02FFFF0006DF01FFFFFFFFFFFFFFFFA6086A10021805")
SET_1 = H("016A02FF01FFFF000A6101FFFFFFFFFFFFFFFF45086A10011802" "6A020801")
SET_OFF = H("016A02FF01FFFF0008C901FFFFFFFFFFFFFFFF1A086A10011802" "6A00")
ENABLE_4 = H("016A07FF01FFFF000CA101FFFFFFFFFFFFFFFF61086A10011807" "5A0408041001")
RESP_BATTERY_87 = H("016A06FF02FFFF000A5D01FFFFFFFFFFFFFFFF81086A10021806" "7A026857")
RESP_MODE_3 = H("016A02FF02FFFF000A9A01FFFFFFFFFFFFFFFF1E086A10021802" "6A020803")
RESP_LEFT_200 = H("016A05FF02FFFF000BEE01FFFFFFFFFFFFFFFFE8086A10021805" "720308C801")
RESP_SUPPORTED = H(
    "016A01FF02FFFF001C4001FFFFFFFFFFFFFFFFD2086A10021801"
    "320408011801" "320408031801" "32020804" "320408111801"
)
STATE_MODE_2 = H("036A02FF02FFFF02FFFF01FFFFFFFFFFFFFFFF4D")
STATE_LEFT_300 = H("036A05FF02FFFF00FFFF012C010000FFFFFFFFBE")
ACK = H("026A02FF01FFFF00000001FFFFFFFFFFFFFFFF05")


def test_crc8_maxim_check_value():
    assert igps.crc8_maxim(b"123456789") == 0xA1


def test_request_builders_match_capture():
    assert igps.read_current_mode() == READ_CURRENT
    assert igps.set_mode(12) == SET_12


def test_other_builders():
    assert igps.read_supported_modes() == READ_SUPPORTED
    assert igps.read_battery() == READ_BATTERY
    assert igps.read_remaining_time() == READ_LEFT
    assert igps.set_mode(1) == SET_1
    assert igps.set_mode(0) == SET_OFF
    assert igps.set_mode_enabled(4, True) == ENABLE_4


def test_parse_responses():
    assert igps.parse_frame(RESP_BATTERY_87) == {"battery": 87}
    assert igps.parse_frame(RESP_MODE_3) == {"mode": 3}
    assert igps.parse_frame(RESP_LEFT_200) == {"remaining_minutes": 200}
    assert igps.parse_frame(RESP_SUPPORTED) == {
        "declared_modes": {1: True, 3: True, 4: False, 17: True}
    }


def test_parse_state_frames():
    assert igps.parse_frame(STATE_MODE_2) == {"mode": 2}
    assert igps.parse_frame(STATE_LEFT_300) == {"remaining_minutes": 300}


def test_ack_and_corrupt_frames_are_ignored():
    assert igps.parse_frame(ACK) is None
    corrupt = bytearray(RESP_MODE_3)
    corrupt[-1] ^= 0x01
    assert igps.parse_frame(bytes(corrupt)) is None


def test_chunks():
    parts = igps.chunks(SET_12)
    assert [len(p) for p in parts] == [20, 10]
    assert b"".join(parts) == SET_12


def test_assembler_reassembles_fragments():
    asm = igps.FrameAssembler()
    assert asm.push(RESP_BATTERY_87[:20]) == []
    assert asm.push(RESP_BATTERY_87[20:]) == [RESP_BATTERY_87]


def test_assembler_splits_concatenated_and_skips_garbage():
    asm = igps.FrameAssembler()
    assert asm.push(b"\x00" + ACK + STATE_MODE_2) == [ACK, STATE_MODE_2]


def test_looks_like_igps():
    assert igps.looks_like_igps("VS1200S", [])
    assert igps.looks_like_igps(None, [igps.ADVERT_MARKER.upper()])
    assert not igps.looks_like_igps("VS1800S_U", [igps.ADVERT_MARKER])
    assert not igps.looks_like_igps("Garmin HRM", [])


# Custom modes (docs/custom-modes-research.md §8). Derived vectors, computed with igps.message().
READ_CUSTOM_64 = H("016A03FF02FFFF000A2B01FFFFFFFFFFFFFFFF79086A10021803" "3A020840")
SELECT_STEADY_64 = H("016A03FF01FFFF000AA801FFFFFFFFFFFFFFFFF9086A10011803" "62020840")
SELECT_FLASH_64 = H("016A03FF01FFFF000CB601FFFFFFFFFFFFFFFFC9086A10011803" "620408401001")
STEADY_MAIN_40 = H("016A03FF01FFFF00102401FFFFFFFFFFFFFFFF36086A10011803" "620808401A0408021028")
STEADY_HIGHBEAM_40 = H("016A03FF01FFFF000E2001FFFFFFFFFFFFFFFFF2086A10011803" "620608401A021028")
FLASH_CYCLE_2 = H("016A03FF01FFFF00109501FFFFFFFFFFFFFFFF7E086A10011803" "62080840100122020802")
FLASH_RATIO_30 = H("016A03FF01FFFF0010B701FFFFFFFFFFFFFFFF63086A10011803" "6208084010012A02081E")
# Illustrative reply (NOT captured): selected steady; steady main 30 %; flash main 100 %, 2 s, 30 %.
REPLY_CUSTOM_64 = H(
    "016A03FF02FFFF00244201FFFFFFFFFFFFFFFFC1086A10021803"
    "421C0840"                                  # field 8 {1: 64}
    "1A0612040802101E"                          # steady: light 2 = 30 %
    "1A1008011204080210641A020802" "22" "02081E"  # flash: light 2 = 100 %, cycle 2, ratio 30
)


def test_custom_mode_builders():
    assert igps.read_custom_mode(64) == READ_CUSTOM_64
    assert igps.modify_custom_mode(64, igps.STEADY) == SELECT_STEADY_64
    assert igps.modify_custom_mode(64, igps.FLASH) == SELECT_FLASH_64
    assert igps.modify_custom_mode(64, igps.STEADY, light=(2, 40)) == STEADY_MAIN_40
    assert igps.modify_custom_mode(64, igps.STEADY, light=(0, 40)) == STEADY_HIGHBEAM_40
    assert igps.modify_custom_mode(64, igps.FLASH, cycle=2) == FLASH_CYCLE_2
    assert igps.modify_custom_mode(64, igps.FLASH, ratio=30) == FLASH_RATIO_30


def test_modify_custom_mode_takes_one_change():
    import pytest
    with pytest.raises(ValueError):
        igps.modify_custom_mode(64, igps.FLASH, cycle=2, ratio=30)


def test_parse_custom_mode_reply():
    assert igps.parse_frame(REPLY_CUSTOM_64) == {"custom_mode": {
        "mode": 64, "selected": 0, "patterns": [
            {"subtype": 0, "lights": [(2, 30)], "cycle": None, "ratio": None},
            {"subtype": 1, "lights": [(2, 100)], "cycle": 2, "ratio": 30},
        ]}}


def test_ack_status():
    assert igps.ack_status(ACK) == (2, 0)
    assert igps.ack_status(RESP_MODE_3) is None
