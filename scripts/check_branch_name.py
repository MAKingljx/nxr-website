#!/usr/bin/env python3
"""Validate the project's user-defined Git branch naming policy."""
import re
import sys


def valid_branch(name: str) -> bool:
    if re.search(r"codex|claude|chatgpt|copilot|gemini", name, re.IGNORECASE):
        return False
    return name == "main" or re.fullmatch(r"Phoenix/[a-z0-9]+(?:-[a-z0-9]+)*", name) is not None


if __name__ == "__main__":
    names = sys.argv[1:]
    if not names or any(not valid_branch(name) for name in names):
        sys.exit("Use main or Phoenix/<business-purpose>; AI tool names are prohibited.")
    print("Branch naming policy passed.")
