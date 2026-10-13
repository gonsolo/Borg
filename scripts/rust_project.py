#!/usr/bin/env python3
"""Write mesa/rust-project.json for rust-analyzer: meson's file with the std sources from the devshell."""
import json, os, subprocess, sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
build = sys.argv[1] if len(sys.argv) > 1 else "build-borg"
src = os.environ.get("RUST_LIB_SRC")
if not src:
    sys.exit("RUST_LIB_SRC is not set; run inside the nix devshell")
with open(f"{root}/mesa/{build}/rust-project.json") as f:
    project = json.load(f)
project["sysroot_src"] = src + "/"
out = f"{root}/mesa/rust-project.json"
if os.path.islink(out):
    os.remove(out)
with open(out, "w") as f:
    json.dump(project, f, indent=2)
exclude = subprocess.check_output(["git", "-C", f"{root}/mesa", "rev-parse", "--git-path", "info/exclude"], text=True).strip()
exclude = os.path.join(f"{root}/mesa", exclude)
if "/rust-project.json" not in open(exclude).read().split():
    with open(exclude, "a") as f:
        f.write("/rust-project.json\n")
