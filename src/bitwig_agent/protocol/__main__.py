import argparse
from pathlib import Path

from bitwig_agent.protocol.bundle import write_bundle


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m bitwig_agent.protocol")
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="write the protocol bundle consumed by the extension build")
    export.add_argument("--out", type=Path, required=True)
    write_bundle(parser.parse_args().out)


if __name__ == "__main__":
    main()
