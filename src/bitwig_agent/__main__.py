"""`python -m bitwig_agent` is the same command as `bitwig-agent`."""

import sys

from .frontends.cli import main

if __name__ == "__main__":
    sys.exit(main())
