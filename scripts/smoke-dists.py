#!/usr/bin/env python3
"""Install each built distribution into a fresh venv outside the checkout and smoke it.

python scripts/smoke-dists.py [--mcp VERSION] [--expected-version VERSION] [DIST ...]
Defaults to every wheel and sdist in dist/. Run through the pixi environment whose Python is under test.
"""

import argparse
import subprocess
import tempfile
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mcp", help="install this exact MCP SDK version instead of the newest allowed")
    parser.add_argument("--expected-version", help="require this installed release version")
    parser.add_argument("dists", nargs="*", type=Path)
    args = parser.parse_args()
    dists = [path.resolve() for path in args.dists] or sorted(
        [*(ROOT / "dist").glob("*.whl"), *(ROOT / "dist").glob("*.tar.gz")]
    )
    if not dists:
        raise SystemExit("No distributions to smoke; run the dist task first")
    smoke = [str(ROOT / "scripts/smoke-package.py")]
    if args.expected_version:
        smoke += ["--expected-version", args.expected_version]
    for dist in dists:
        with tempfile.TemporaryDirectory(prefix="package-consumer-") as consumer:
            builder = venv.EnvBuilder(with_pip=True)
            environment = Path(consumer) / "venv"
            builder.create(environment)
            python = builder.ensure_directories(environment).env_exe
            # No cache: an sdist must really build, not reuse a wheel.
            requirements = [str(dist), *([f"mcp=={args.mcp}"] if args.mcp else [])]
            subprocess.run([str(python), "-m", "pip", "install", "--no-cache-dir", *requirements], check=True)
            subprocess.run([str(python), "-I", *smoke], check=True)


if __name__ == "__main__":
    main()
