"""Talk to an iGPSPORT light from the PC.

  python probe.py scan                   # list nearby BLE devices, flag iGPSPORT lights
  python probe.py info  <ADDRESS>        # GATT table + battery + modes + current mode
  python probe.py mode  <ADDRESS> <N> [--enable]   # set mode N (enable it first if needed)
  python probe.py watch <ADDRESS> [secs] # print every frame (press the light's button!)

The iGPSPORT phone app must be closed / phone Bluetooth off: the light accepts one connection.
"""
import argparse
import asyncio

from bleak import BleakClient, BleakScanner

import igps


def describe(update: dict) -> str:
    parts = []
    if "mode" in update:
        parts.append(f"mode={update['mode']} ({igps.label(update['mode'])})")
    if "battery" in update:
        parts.append(f"battery={update['battery']}%")
    if "remaining_minutes" in update:
        parts.append(f"remaining={update['remaining_minutes']} min")
    if "declared_modes" in update:
        modes = ", ".join(f"{m}:{igps.label(m)}{'' if on else ' (disabled)'}"
                          for m, on in update["declared_modes"].items())
        parts.append(f"declared modes=[{modes}]")
    return "; ".join(parts) or "(empty update)"


class Session:
    def __init__(self, client: BleakClient):
        self.client = client
        self.assembler = igps.FrameAssembler()
        self.state = {}

    def on_notify(self, _characteristic, data: bytearray):
        print(f"   <- {bytes(data).hex(' ').upper()}")
        for frame in self.assembler.push(bytes(data)):
            if frame[0] == igps.TYPE_ACK:
                print("      ACK")
                continue
            update = igps.parse_frame(frame)
            if update is None:
                print("      (frame not understood)")
            else:
                self.state.update(update)
                print(f"      {describe(update)}")

    async def send(self, frame: bytes, what: str):
        print(f"-> {what}: {frame.hex(' ').upper()}")
        for chunk in igps.chunks(frame):
            await self.client.write_gatt_char(igps.UART_WRITE, chunk, response=True)


async def cmd_scan(seconds: float):
    print(f"Scanning {seconds:.0f}s … (turn the light on)")
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    for device, adv in sorted(found.values(), key=lambda da: da[1].rssi, reverse=True):
        flag = "   <== iGPSPORT light?" if igps.looks_like_igps(adv.local_name, adv.service_uuids) else ""
        print(f"{device.address}  rssi={adv.rssi:4}  name={adv.local_name!r}  uuids={adv.service_uuids}{flag}")


async def cmd_info(address: str):
    async with BleakClient(address, timeout=20.0) as client:
        print(f"Connected to {address}\n--- GATT table ---")
        for service in client.services:
            print(f"[service] {service.uuid}  {service.description}")
            for ch in service.characteristics:
                print(f"    [char] {ch.uuid}  props={','.join(ch.properties)}")
        try:
            level = await client.read_gatt_char(igps.BATTERY_LEVEL)
            print(f"--- Standard battery service: {level[0]}% ---")
        except Exception as exc:  # noqa: BLE001 - diagnostic tool
            print(f"--- Standard battery service: not available ({exc}) ---")
        session = Session(client)
        await client.start_notify(igps.UART_NOTIFY, session.on_notify)
        for what, frame in [
            ("read declared modes", igps.read_supported_modes()),
            ("read current mode", igps.read_current_mode()),
            ("read battery", igps.read_battery()),
            ("read remaining time", igps.read_remaining_time()),
        ]:
            await session.send(frame, what)
            await asyncio.sleep(1.0)
        await asyncio.sleep(2.0)
        print(f"\nSUMMARY: {describe(session.state)}")


async def cmd_mode(address: str, mode: int, enable: bool):
    async with BleakClient(address, timeout=20.0) as client:
        session = Session(client)
        await client.start_notify(igps.UART_NOTIFY, session.on_notify)
        if enable:
            await session.send(igps.set_mode_enabled(mode, True), f"enable mode {mode}")
            await asyncio.sleep(1.0)
        await session.send(igps.set_mode(mode), f"set mode {mode} ({igps.label(mode)})")
        await asyncio.sleep(1.5)
        await session.send(igps.read_current_mode(), "read current mode")
        await asyncio.sleep(1.5)
        print(f"\nLight now reports: {describe(session.state)}")


async def cmd_watch(address: str, seconds: float):
    async with BleakClient(address, timeout=20.0) as client:
        session = Session(client)
        await client.start_notify(igps.UART_NOTIFY, session.on_notify)
        print(f"Watching {seconds:.0f}s — press the light's button a few times now.")
        await asyncio.sleep(seconds)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("scan")
    p.add_argument("seconds", nargs="?", type=float, default=10.0)
    p = sub.add_parser("info")
    p.add_argument("address")
    p = sub.add_parser("mode")
    p.add_argument("address")
    p.add_argument("mode", type=int)
    p.add_argument("--enable", action="store_true")
    p = sub.add_parser("watch")
    p.add_argument("address")
    p.add_argument("seconds", nargs="?", type=float, default=60.0)
    args = parser.parse_args()
    if args.cmd == "scan":
        asyncio.run(cmd_scan(args.seconds))
    elif args.cmd == "info":
        asyncio.run(cmd_info(args.address))
    elif args.cmd == "mode":
        asyncio.run(cmd_mode(args.address, args.mode, args.enable))
    else:
        asyncio.run(cmd_watch(args.address, args.seconds))


if __name__ == "__main__":
    main()
