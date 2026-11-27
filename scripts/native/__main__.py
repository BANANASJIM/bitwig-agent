"""Run one isolated Bitwig on a test display, with evidence, through pixi tasks (`pixi run native-<command>`).

  doctor            check system tools, pixi, host jars and initialised homes
  init HOST         build the isolated home of HOST from your own installation (licence, prefs, fixture)
  up HOST [EXT]     install EXT into the isolated home, launch HOST silent in bwrap, start the evidence
  down              save, quit and close the evidence of the current run, then print its proof
  reset HOST [EXT]  down, then up
  status            read-only: lock, owner and Bitwig processes
  cli ARGS...       bitwig-agent ARGS against the current run, logged to its cli.log
  shot              screenshot the test Bitwig window into the current run
  proof [RUN]       summarise a run's evidence (default: the newest)

Machine paths come from native-hosts.local.toml (see native-hosts.example.toml) or BWA_NATIVE_CONFIG.
"""

import argparse
import sys

from . import doctor, harness
from .config import load


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="native", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("doctor", "down", "status", "shot"):
        commands.add_parser(name)
    commands.add_parser("init").add_argument("host")
    for name in ("up", "reset"):
        sub = commands.add_parser(name)
        sub.add_argument("host")
        sub.add_argument("extension", nargs="?", default="")
    commands.add_parser("cli").add_argument("args", nargs=argparse.REMAINDER)
    commands.add_parser("proof").add_argument("run", nargs="?", default="")
    args = parser.parse_args(argv)
    config = load()
    if args.command == "doctor":
        rows = doctor.checks(config)
        for ok, text in rows:
            print(f"{'ok  ' if ok else 'FAIL'}  {text}")
        return int(not all(ok for ok, _ in rows))
    if args.command == "cli":
        return harness.cli(args.args)
    if args.command == "proof":
        return int(not harness.proof(config, args.run))
    actions = {
        "init": lambda: harness.init(config, args.host),
        "up": lambda: harness.up(config, args.host, args.extension),
        "reset": lambda: harness.reset(config, args.host, args.extension),
        "down": lambda: harness.down(config),
        "status": lambda: harness.status(config),
        "shot": lambda: harness.shot(config),
    }
    actions[args.command]()
    return 0


if __name__ == "__main__":
    sys.exit(main())
