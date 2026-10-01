#!/bin/sh
# Archive the full AI session logs behind a commit into the PRIVATE provenance
# repo (default ~/work/Borg-provenance), titled with the public commit's hash.
# Run by the post-commit hook; never blocks a commit.   usage: [commit]
dest="${AI_PROVENANCE_REPO:-$HOME/work/Borg-provenance}"
[ -d "$dest/.git" ] || { echo "archive: no provenance repo at $dest" >&2; exit 0; }
sha=$(git rev-parse "${1:-HEAD}") || exit 0
subject=$(git log -1 --format=%s "$sha")
branch=$(git symbolic-ref --short -q HEAD || echo detached)
umask 077
exec 9>"$dest/.lock"; flock -w 120 9 || exit 0

since=$(git -C "$dest" log -1 --format=%ct 2>/dev/null || echo 0)
active=""
for dir in "$HOME"/.claude/projects/*Borg*/; do
  name=$(basename "$dir"); mkdir -p "$dest/sessions/$name"
  for f in "$dir"*.jsonl; do
    [ -f "$f" ] || continue
    cp -u "$f" "$dest/sessions/$name/"
    [ "$(stat -c %Y "$f")" -gt "$since" ] && active="$active $name/$(basename "$f")"
  done
done

mkdir -p "$dest/commits"
{
  echo "commit:  $sha"; echo "subject: $subject"; echo "branch:  $branch"
  echo "date:    $(date -Is)"; echo "sessions active since last archive:"
  for s in $active; do echo "  $s"; done
} > "$dest/commits/$sha.txt"

# Secret scan of what changed (warn only: the repo is private; scrub before sharing).
hits=$(git -C "$dest" status --porcelain sessions | awk '{print $2}' | while read -r p; do
  grep -lE 'BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}' "$dest/$p" 2>/dev/null
done | wc -l)
[ "$hits" -gt 0 ] && echo "secret-scan: $hits file(s) matched, scrub before sharing" >> "$dest/commits/$sha.txt"

git -C "$dest" add -A >/dev/null 2>&1
git -C "$dest" commit -q -m "Borg $(echo "$sha" | cut -c1-12) $subject" -m "Borg-Commit: $sha" >/dev/null 2>&1
# Repack often: transcripts are append-only, so deltas are tiny once packed.
git -C "$dest" gc --auto --quiet >/dev/null 2>&1
exit 0
