#!/usr/bin/env python3
"""Vanilla's floating kick (`allow-flight=false`) end to end on a dedicated server.

    python3 tools/server_floating_e2e.py --bin-dir target/fast

ServerGamePacketListenerImpl kicks a player who stays in the air, not falling, with nothing around
them, for more than 80 ticks (`multiplayer.disconnect.flying`, "{} was kicked for floating too long!").
horizon-bots stand in for the players: an `idle` bot climbs 24 blocks above spawn and hovers there
(a fly hack), a `stand` bot stays on the ground.

- survival, `allow-flight=false` (vanilla's default): the hovering bot is kicked within seconds, the
  console says why; the bot on the ground plays on;
- creative (players may fly), and survival with `allow-flight=true`: nobody is kicked.

Each server runs in a fresh folder with fresh HORIZON_HOME and XDG folders (lab.game_env), on a free
port. Build first:
    cargo build --profile fast -p horizon-client --bin horizon-client
    cargo build --profile fast -p horizon-bots
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402
from server_bench import DONE, Server  # noqa: E402

EXE = '.exe' if os.name == 'nt' else ''
KICK = 'multiplayer.disconnect.flying'
PROPERTIES = {
    'online-mode': 'false',
    'white-list': 'false',
    'level-type': 'minecraft\\:flat',
    'generate-structures': 'false',
    'force-gamemode': 'true',
    'view-distance': '3',
    'simulation-distance': '3',
    'spawn-protection': '0',
}
SECONDS = 12


class Failed(Exception):
    pass


def bots(bins, port, out, run):
    """A hovering bot and one on the ground, side by side for SECONDS: their report entries."""
    procs = {}
    for name, behaviour in (('Hover', 'idle'), ('Ground', 'stand')):
        report = out / f'{run}-{name}.json'
        procs[name] = (report, subprocess.Popen(
            [bins / f'horizon-bots{EXE}', '--server', f'127.0.0.1:{port}', '--bots', '1',
             '--behaviour', behaviour, '--seconds', str(SECONDS), '--view-distance', '3', '--name', name,
             '--report', report], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    entries = {}
    for name, (report, proc) in procs.items():
        proc.wait(timeout=240)
        entries[name] = json.loads(report.read_text())['per_bot'][0]
    return entries


def server(bins, folder, out, name, extra):
    folder.mkdir(parents=True)
    port = lab.free_port()
    props = dict(PROPERTIES, **extra, **{'server-port': str(port)})
    (folder / 'server.properties').write_text(''.join(f'{k}={v}\n' for k, v in props.items()))
    (folder / 'eula.txt').write_text('eula=true\n')
    command = [bins / f'horizon-client{EXE}', '--resources', lab.RESOURCES, '--dedicated', '--nogui']
    s = Server(command, folder, out / f'{name}.log')
    s.wait_for(DONE, 300)
    return s, port


def kicked(entry):
    return KICK in (entry['error'] or '')


def run(bins, scratch, out, name, extra):
    s, port = server(bins, scratch / name, out, name, extra)
    try:
        entries = bots(bins, port, out, name)
        lines = s.since(0)
    finally:
        s.stop()
    for bot, entry in entries.items():
        if entry['joined_after_ms'] <= 0:
            raise Failed(f'{name}: {bot} never joined: {entry["error"]}')
    return entries, lines


def check(bins, scratch, out):
    entries, lines = run(bins, scratch, out, 'survival', {'gamemode': 'survival'})
    if not kicked(entries['Hover']):
        raise Failed(f'survival: the hovering bot was not kicked: {entries["Hover"]["error"]}')
    if not any('Hover0 was kicked for floating too long!' in line for line in lines):
        raise Failed('survival: the console did not say why Hover0 was kicked')
    if entries['Ground']['error']:
        raise Failed(f'survival: the bot on the ground was disconnected: {entries["Ground"]["error"]}')
    print('survival, allow-flight=false: the hovering bot is kicked for floating, the one on the ground plays on')
    for name, extra in (('creative', {'gamemode': 'creative'}),
                        ('allowed', {'gamemode': 'survival', 'allow-flight': 'true'})):
        entries, _ = run(bins, scratch, out, name, extra)
        for bot, entry in entries.items():
            if entry['error']:
                raise Failed(f'{name}: {bot} was disconnected: {entry["error"]}')
    print('creative, and survival with allow-flight=true: nobody is kicked')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--bin-dir', default=str(lab.ROOT / 'target/fast'))
    parser.add_argument('--out', default=str(lab.LOCAL / 'server-floating-e2e'))
    args = parser.parse_args()
    bins, out = Path(args.bin_dir).resolve(), Path(args.out)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    os.environ.update(lab.game_env())
    with tempfile.TemporaryDirectory(prefix='server-floating-e2e-') as scratch:
        try:
            check(bins, Path(scratch), out)
        except (Failed, RuntimeError) as e:
            sys.exit(f'server_floating_e2e failed (logs in {out}): {e}')


if __name__ == '__main__':
    main()
