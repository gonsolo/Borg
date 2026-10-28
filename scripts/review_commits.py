#!/usr/bin/env python3
"""Review commits one by one and add your Reviewed-by tag; merge and push only on your yes.

    scripts/review_commits.py [RANGE]        # default: origin/main..HEAD

For every commit in RANGE (oldest first) the script shows the message and the
full diff in your pager, then asks:

    a  accept     the commit gets 'Reviewed-by: <git user.name>'
    r  refuse     nothing is rewritten or pushed; the script exits non-zero
    v  open the commit in vim (read-only, :q to come back)
    e  accept, but reword the message in vim first (Reviewed-by is added after)
    d  show the diff again          s  show only 'git show --stat'
    q  quit       same as refuse, without a verdict on the commit

Before the first commit it runs the policy check and lists what will fail
besides the missing Reviewed-by; 'y' fixes them first on <branch>-reviewed:
commits without AI-Assisted get 'AI-Assisted: none', other messages open in
vim. Then the whole range is reviewed. A commit without an AI-Assisted trailer gets
a prompt on accept: add 'AI-Assisted: none', reword it in vim, or keep it.

Only when EVERY commit was accepted does it
  1. copy the branch to <branch>-reviewed (the original is never touched) and
     rewrite the range there, appending the Reviewed-by trailer where it is missing,
  2. check the result with scripts/check_ai_policy.py,
  3. warn if a submodule commit the range points at is not on the submodule's
     remote (a bump to an unpushed commit breaks everyone else),
  4. ask, one step at a time: fast-forward main to <branch>-reviewed, delete
     <branch> and <branch>-reviewed, push main (not offered after a warning in 3).

Submodules stay where they are: the script's switches and rebases do not move them.

The review is a human act: the script refuses to run without a terminal on
stdin, so it cannot be scripted or driven by an assistant.
"""
import argparse
import os
import re
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


def trailer_cmd(*trailers: str) -> str:
    """One-line shell command: add the trailers to HEAD's message unless present."""
    args = " ".join(f"--trailer '{t}'" for t in trailers)
    return ("git log -1 --format=%B | git interpret-trailers --if-exists doNothing "
            f"{args} | git commit -q --allow-empty --amend -F -")


def has_trailer(rev: str, key: str) -> bool:
    return bool(git("log", "-1", f"--format=%(trailers:key={key},valueonly)", rev).strip())


def view_in_vim(rev: str):
    patch = subprocess.run(["git", "show", "--patch-with-stat", rev], cwd=ROOT,
                           capture_output=True, text=True).stdout
    editor = os.environ.get("VISUAL") or os.environ.get("EDITOR") or "vim"
    flags = ["-R", "-c", "set ft=diff"] if Path(editor).name in ("vim", "nvim") else []
    subprocess.run([editor, *flags, "-"], input=patch, text=True, cwd=ROOT)


def rewrite_todo(todo_path: str, reword: list[str], exec_cmd: str, none_list: list[str], none_cmd: str):
    """GIT_SEQUENCE_EDITOR body: reword flagged picks, append the trailer exec."""
    out = []
    for line in Path(todo_path).read_text().splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "pick":
            if any(full.startswith(parts[1]) for full in reword):
                line = line.replace("pick", "reword", 1)
            out.append(line)
            out.append(f"exec {none_cmd if any(full.startswith(parts[1]) for full in none_list) else exec_cmd}")
        else:
            out.append(line)
    Path(todo_path).write_text("\n".join(out) + "\n")


def run_rebase(base: str, seq_args: str):
    """Interactive rebase of HEAD onto base with our sequence editor; True on success."""
    wrapper = Path(git("rev-parse", "--git-dir", cwd=ROOT)) / "review_seq_editor.sh"
    wrapper = (ROOT / wrapper) if not wrapper.is_absolute() else wrapper
    wrapper.write_text(f"#!/bin/sh\nexec {sys.executable} {Path(__file__).resolve()} {seq_args}\n")
    wrapper.chmod(0o755)
    env = dict(os.environ, GIT_SEQUENCE_EDITOR=str(wrapper))
    env.setdefault("GIT_EDITOR", os.environ.get("VISUAL") or os.environ.get("EDITOR") or "vim")
    r = subprocess.run(["git", "rebase", "-i", base], cwd=ROOT, env=env)
    wrapper.unlink(missing_ok=True)
    return r.returncode == 0


def policy_problems(rng: str) -> list[str]:
    chk = subprocess.run([sys.executable, str(Path(__file__).with_name("check_ai_policy.py")), "--repo", str(ROOT),
                          "--range", rng], capture_output=True, text=True)
    return [l for l in (chk.stdout + chk.stderr).splitlines() if l.strip() and "need a 'Reviewed-by" not in l]


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
    ap.add_argument("--rewrite-todo", nargs=5, metavar=("TODO", "REWORD", "EXEC", "NONE", "NONE_EXEC"), help=argparse.SUPPRESS)
    a = ap.parse_args()
    if a.rewrite_todo:
        todo, reword, exec_cmd, none, none_cmd = a.rewrite_todo
        rewrite_todo(todo, [r for r in reword.split(",") if r], exec_cmd, [r for r in none.split(",") if r], none_cmd)
        return

    os.environ.update(GIT_CONFIG_COUNT="2", GIT_CONFIG_KEY_0="submodule.recurse", GIT_CONFIG_VALUE_0="false",
                      GIT_CONFIG_KEY_1="submodule.mesa.ignore", GIT_CONFIG_VALUE_1="all")
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

    cur = git("rev-parse", "--abbrev-ref", "HEAD")
    if cur in ("HEAD", "main"):
        sys.exit("check out the feature branch to review (not main, not detached)")
    reviewed = f"{cur}-reviewed"
    if git("rev-parse", "--verify", "-q", f"refs/heads/{reviewed}", check=False):
        sys.exit(f"branch {reviewed} exists: merge or delete it first")

    created = False
    problems = policy_problems(f"{base}..{head}")
    if problems:
        print("The final policy check would fail on these (the script adds Reviewed-by only):")
        print("\n".join("  " + l for l in problems[:30]))
        def hash_of(l):
            h = l.split(":")[0]
            return h if re.fullmatch(r"[0-9a-f]{7,40}", h) else None
        ai = [hash_of(l) for l in problems if hash_of(l) and "'AI-Assisted" in l]
        other = [hash_of(l) for l in problems if hash_of(l) and "'AI-Assisted" not in l]
        c = ask(f"Fix now (y): {len(ai)} get 'AI-Assisted: none' added, {len(other)} open in vim; "
                "review anyway (n); quit (q)?", "ynq")
        if c == "q":
            sys.exit("nothing changed.")
        if c == "y":
            if head != "HEAD":
                sys.exit("fixing first needs the range to end at HEAD")
            git("switch", "-c", reviewed)
            created = True
            seq_args = (f"--rewrite-todo \"$1\" '{','.join(other)}' \"true\" '{','.join(ai)}' "
                        f"\"{trailer_cmd('AI-Assisted: none')}\"")
            if not run_rebase(base, seq_args):
                sys.exit(f"rebase failed; back: git rebase --abort; git switch {cur}; git branch -D {reviewed}")
            left = policy_problems(f"{base}..HEAD")
            if left:
                print("Still failing:\n" + "\n".join("  " + l for l in left[:30]))
                if ask("Review anyway?", "yn") == "n":
                    git("switch", cur)
                    git("branch", "-D", reviewed)
                    sys.exit("nothing changed.")
            revs = git("rev-list", "--reverse", "--no-merges", f"{base}..HEAD").split()

    accepted = []
    reword = []
    none = []
    for i, rev in enumerate(revs, 1):
        print(f"\n\033[1m=== commit {i}/{len(revs)}: {git('log', '-1', '--format=%h %s', rev)}\033[0m"
              f"  [{i - 1} done / {len(revs) - i + 1} left]")
        show(rev)
        while True:
            c = ask(f"[{i - 1} done / {len(revs) - i + 1} left] accept, edit msg, vim, refuse, diff again, stat, quit?", "aevrdsq")
            if c == "d":
                show(rev)
            elif c == "s":
                show(rev, stat_only=True)
            elif c == "v":
                view_in_vim(rev)
            else:
                break
        if c in "ae":
            if c == "a" and not has_trailer(rev, "AI-Assisted"):
                n = ask("no AI-Assisted trailer: add 'AI-Assisted: none' (n), reword in vim (e), keep as is (k)?", "nek")
                if n == "n":
                    none.append(rev)
                elif n == "e":
                    c = "e"
            accepted.append(rev)
            if c == "e":
                reword.append(rev)
        else:
            if created:
                git("switch", cur)
                git("branch", "-D", reviewed)
            print("Not accepted -- nothing was changed or pushed.")
            sys.exit(1)

    print(f"\nAll {len(accepted)} commit(s) accepted by {reviewer}.")
    if not created:
        git("switch", "-c", reviewed)

    # 1. append Reviewed-by where missing, rewriting the range.
    exec_cmd = trailer_cmd(f"Reviewed-by: {reviewer}")
    none_cmd = trailer_cmd("AI-Assisted: none", f"Reviewed-by: {reviewer}")
    seq_args = f"--rewrite-todo \"$1\" '{','.join(reword)}' \"{exec_cmd}\" '{','.join(none)}' \"{none_cmd}\""
    ok = run_rebase(base, seq_args)
    if not ok:
        sys.exit(f"rebase failed; fix with 'git rebase --abort' or resolve, nothing was pushed. Back: git rebase --abort; git switch {cur}; git branch -D {reviewed}")

    # 2. policy check on the rewritten range.
    chk = subprocess.run([sys.executable, str(Path(__file__).with_name("check_ai_policy.py")), "--repo", str(ROOT),
                          "--range", f"{base}..HEAD"])
    if chk.returncode:
        sys.exit(f"policy check failed -- fix the commit messages, nothing was pushed. Back: git switch {cur}; git branch -D {reviewed}")

    # 3. warn about submodule bumps whose commit is not on the submodule's remote.
    unpushed = False
    for path, sha in submodule_bumps(base, "HEAD"):
        sub = ROOT / path
        if (sub / ".git").exists() and not git("branch", "-r", "--contains", sha, check=False, cwd=sub):
            print(f"\nWARNING: {path}: {sha[:11]} is not on the submodule's remote; push it first.")
            unpushed = True

    # 4. merge, delete, push: each only on a yes.
    print(f"\nReviewed-by added to {base}..HEAD on {reviewed}; {cur} is unchanged.")
    if ask(f"Fast-forward main to {reviewed}?", "yn") == "n":
        sys.exit(f"Nothing merged or pushed. Later: git switch main && git merge --ff-only {reviewed}")
    git("switch", "main")
    if subprocess.run(["git", "merge", "--ff-only", reviewed], cwd=ROOT).returncode:
        sys.exit(f"main cannot fast-forward to {reviewed}; nothing pushed. Rebase {reviewed} on main first.")
    if ask(f"Delete {cur} and {reviewed}?", "yn") == "y":
        git("branch", "-D", cur, reviewed)
    if unpushed:
        sys.exit("Not pushed: a submodule commit is not on its remote (see the warning above).")
    if ask("Push main to origin?", "yn") == "y":
        subprocess.run(["git", "-c", "http.https://github.com/.extraheader=", "push", "origin", "main"], cwd=ROOT)
    else:
        print("Not pushed.")

if __name__ == "__main__":
    main()
