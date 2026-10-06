#!/usr/bin/env python3
"""Review commits one by one and add your Reviewed-by tag. It never pushes.

    scripts/review_commits.py [RANGE]        # default: origin/main..HEAD

For every commit in RANGE (oldest first) the script shows the message and the
full diff in your pager, then asks:

    a  accept     the commit gets 'Reviewed-by: <git user.name>'
    r  refuse     nothing is rewritten or pushed; the script exits non-zero
    v  open the commit in vim (read-only, :q to come back)
    e  accept, but reword the message in vim first (Reviewed-by is added after)
    d  show the diff again          s  show only 'git show --stat'
    q  quit       same as refuse, without a verdict on the commit

Only when EVERY commit was accepted does it
  1. rewrite the range, appending the Reviewed-by trailer where it is missing,
  2. check the result with scripts/check_ai_policy.py,
  3. warn if a submodule commit the range points at is not on the submodule's
     remote (a bump to an unpushed commit breaks everyone else),
  4. print the push command; pushing is left to you.

The review is a human act: the script refuses to run without a terminal on
stdin, so it cannot be scripted or driven by an assistant.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                           text=True, check=True).stdout.strip())


def git(*args, check=True, capture=True, cwd=ROOT):
    r = subprocess.run(["git", *args], cwd=cwd, text=True, capture_output=capture)
    if check and r.returncode:
        sys.exit(f"git {' '.join(args)} failed:\n{r.stderr}")
    return r.stdout.strip() if capture else ""


def ask(prompt: str, choices: str) -> str:
    while True:
        ans = input(f"{prompt} [{'/'.join(choices)}] ").strip().lower()
        if ans and ans[0] in choices:
            return ans[0]


def show(rev: str, stat_only: bool = False):
    args = ["git", "-c", "color.ui=always", "show", "--stat" if stat_only else "--patch-with-stat", rev]
    env = dict(os.environ, LESS=os.environ.get("LESS", "FRX"))
    subprocess.run(args, cwd=ROOT, env=env)


def trailer_cmd(reviewer: str) -> str:
    """One-line shell command: add Reviewed-by to HEAD's message unless present."""
    return ("git log -1 --format=%B | git interpret-trailers --if-exists doNothing "
            f"--trailer 'Reviewed-by: {reviewer}' | git commit -q --allow-empty --amend -F -")


def view_in_vim(rev: str):
    patch = subprocess.run(["git", "show", "--patch-with-stat", rev], cwd=ROOT,
                           capture_output=True, text=True).stdout
    editor = os.environ.get("VISUAL") or os.environ.get("EDITOR") or "vim"
    flags = ["-R", "-c", "set ft=diff"] if Path(editor).name in ("vim", "nvim") else []
    subprocess.run([editor, *flags, "-"], input=patch, text=True, cwd=ROOT)


def rewrite_todo(todo_path: str, reword: list[str], exec_cmd: str):
    """GIT_SEQUENCE_EDITOR body: reword flagged picks, append the trailer exec."""
    out = []
    for line in Path(todo_path).read_text().splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "pick":
            if any(full.startswith(parts[1]) for full in reword):
                line = line.replace("pick", "reword", 1)
            out.append(line)
            out.append(f"exec {exec_cmd}")
        else:
            out.append(line)
    Path(todo_path).write_text("\n".join(out) + "\n")


def submodule_bumps(base: str, head: str) -> list[tuple[str, str]]:
    """(path, new sha) for every gitlink changed in base..head."""
    out = git("diff", "--raw", "--no-abbrev", f"{base}..{head}")
    bumps = []
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        fields = meta.lstrip(":").split()
        if fields[1] == "160000" or fields[0] == "160000":
            bumps.append((path, fields[3]))
    return bumps


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("range", nargs="?", default="origin/main..HEAD")
    ap.add_argument("--rewrite-todo", nargs=3, metavar=("TODO", "REWORD", "EXEC"), help=argparse.SUPPRESS)
    a = ap.parse_args()
    if a.rewrite_todo:
        todo, reword, exec_cmd = a.rewrite_todo
        rewrite_todo(todo, [r for r in reword.split(",") if r], exec_cmd)
        return

    if not sys.stdin.isatty():
        sys.exit("review_commits.py needs a terminal: a review is done by a human.")
    if git("status", "--porcelain", "--untracked-files=no"):
        sys.exit("working tree has uncommitted changes to tracked files; commit or stash first.")

    reviewer = git("config", "user.name")
    if not reviewer:
        sys.exit("git config user.name is not set")
    git("fetch", "origin", "main", check=False)
    base, _, head = a.range.partition("..")
    head = head or "HEAD"
    revs = git("rev-list", "--reverse", "--no-merges", f"{base}..{head}").split()
    if not revs:
        sys.exit(f"nothing to review in {a.range}")

    accepted = []
    reword = []
    for i, rev in enumerate(revs, 1):
        print(f"\n\033[1m=== commit {i}/{len(revs)}: {git('log', '-1', '--format=%h %s', rev)}\033[0m"
              f"  [{i - 1} done / {len(revs) - i + 1} left]")
        show(rev)
        while True:
            c = ask("accept, edit msg, vim, refuse, diff again, stat, quit?", "aevrdsq")
            if c == "d":
                show(rev)
            elif c == "s":
                show(rev, stat_only=True)
            elif c == "v":
                view_in_vim(rev)
            else:
                break
        if c in "ae":
            accepted.append(rev)
            if c == "e":
                reword.append(rev)
        else:
            print("Not accepted -- nothing was changed or pushed.")
            sys.exit(1)

    print(f"\nAll {len(accepted)} commit(s) accepted by {reviewer}.")
    if git("rev-parse", "--abbrev-ref", "HEAD") == "HEAD":
        sys.exit("detached HEAD: check out a branch first")

    # 1. append Reviewed-by where missing, rewriting the range.
    exec_cmd = trailer_cmd(reviewer)
    seq = f"{sys.executable} {Path(__file__).resolve()} --rewrite-todo"
    env = dict(os.environ)
    # git calls GIT_SEQUENCE_EDITOR as '<cmd> <todo file>'; bind the other args via a wrapper script.
    wrapper = Path(git("rev-parse", "--git-dir", cwd=ROOT)) / "review_seq_editor.sh"
    wrapper = (ROOT / wrapper) if not wrapper.is_absolute() else wrapper
    wrapper.write_text(f"#!/bin/sh\nexec {seq} \"$1\" '{','.join(reword)}' \"{exec_cmd}\"\n")
    wrapper.chmod(0o755)
    env["GIT_SEQUENCE_EDITOR"] = str(wrapper)
    env.setdefault("GIT_EDITOR", os.environ.get("VISUAL") or os.environ.get("EDITOR") or "vim")
    r = subprocess.run(["git", "rebase", "-i", base], cwd=ROOT, env=env)
    wrapper.unlink(missing_ok=True)
    if r.returncode:
        sys.exit("rebase failed; fix with 'git rebase --abort' or resolve, nothing was pushed.")

    # 2. policy check on the rewritten range.
    chk = subprocess.run([sys.executable, str(Path(__file__).with_name("check_ai_policy.py")), "--repo", str(ROOT),
                          "--range", f"{base}..HEAD"])
    if chk.returncode:
        sys.exit("policy check failed -- fix the commit messages, nothing was pushed.")

    # 3. warn about submodule bumps whose commit is not on the submodule's remote.
    for path, sha in submodule_bumps(base, "HEAD"):
        sub = ROOT / path
        if (sub / ".git").exists() and not git("branch", "-r", "--contains", sha, check=False, cwd=sub):
            print(f"\nWARNING: {path}: {sha[:11]} is not on the submodule's remote; push it first.")

    print(f"\nReviewed-by added to {base}..HEAD. Nothing was pushed. To publish:")
    if subprocess.run(["git", "merge-base", "--is-ancestor", "origin/main", "HEAD"], cwd=ROOT).returncode:
        print("  HEAD does not contain origin/main: rebase onto it first.")
    else:
        print("  git -c http.https://github.com/.extraheader= push origin HEAD:main")


if __name__ == "__main__":
    main()
