#!/usr/bin/env python3
"""Check commits against NLnet's generative-AI policy (v1.1, 2026-01-25).

Every commit on main must declare its AI use in a trailer:

    AI-Assisted: <model and version>   or   AI-Assisted: none
    (AI used only to find/diagnose a bug or to test, with the code written by a
     human: "AI-Assisted: none (debugging/testing only)" -- NLnet's policy
     targets code generation, not this)

When AI was used, two more trailers are required:

    AI-Prompts:  <the relevant prompts, quoted or closely paraphrased>
    Reviewed-by: <human who read, tested and takes responsibility for the diff>

and one is optional but encouraged when the change is AI-generated yet obvious
from an external reference (Vulkan spec, Mesa, an existing Borg module):

    AI-Basis:    <the reference that makes the change near-deterministic>

Full session logs stay private (scripts/archive_ai_sessions.sh) and are
available to NLnet on request. See docs/ai_policy.md.

Usage:
    check_ai_policy.py --msg-file FILE      # commit-msg hook
    check_ai_policy.py --range A..B         # CI / pre-push (skips merges)
    check_ai_policy.py --readme             # README documents the AI use
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def trailer(msg: str, key: str):
    m = re.search(rf"^{re.escape(key)}:[ \t]*(.*\S)[ \t]*$", msg, re.M | re.I)
    return m.group(1) if m else None


def check_message(msg: str) -> list[str]:
    errs = []
    ai = trailer(msg, "AI-Assisted")
    if ai is None:
        return ["missing 'AI-Assisted: <model and version>' or 'AI-Assisted: none'"]
    if ai.lower().startswith("none"):
        return errs
    if not re.search(r"\d", ai):
        errs.append("AI-Assisted must name the model AND its version (e.g. 'Claude Sonnet 5.5')")
    prompts = trailer(msg, "AI-Prompts")
    if not prompts or len(prompts) < 40:
        errs.append("AI-Assisted commits need an 'AI-Prompts:' line quoting or closely paraphrasing the relevant prompts (40+ chars)")
    if not trailer(msg, "Reviewed-by"):
        errs.append("AI-Assisted commits need a 'Reviewed-by: <human>' trailer")
    basis = trailer(msg, "AI-Basis")
    if basis is not None and len(basis) < 10:
        errs.append("AI-Basis must name the reference (spec section, Mesa file, Borg module)")
    return errs


def check_range(rng: str) -> int:
    revs = subprocess.run(["git", "rev-list", "--no-merges", rng], cwd=ROOT,
                          capture_output=True, text=True, check=True).stdout.split()
    bad = 0
    for rev in revs:
        msg = subprocess.run(["git", "log", "-1", "--format=%B", rev], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout
        for e in check_message(msg):
            print(f"{rev[:10]}: {e}")
            bad += 1
    return bad


def check_readme() -> int:
    text = (ROOT / "README.md").read_text()
    if not re.search(r"^#+\s*Generative AI", text, re.M | re.I):
        print("README.md has no 'Generative AI use' section")
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--msg-file")
    ap.add_argument("--range")
    ap.add_argument("--readme", action="store_true")
    a = ap.parse_args()
    bad = 0
    if a.msg_file:
        msg = Path(a.msg_file).read_text()
        msg = "\n".join(l for l in msg.splitlines() if not l.startswith("#"))
        for e in check_message(msg):
            print(f"commit rejected: {e}\n(see docs/ai_policy.md)")
            bad += 1
    if a.range:
        bad += check_range(a.range)
    if a.readme:
        bad += check_readme()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
