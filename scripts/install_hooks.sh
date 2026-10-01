#!/bin/sh
# One-time per clone: enable the AI-policy hooks and the commit template.
cd "$(git rev-parse --show-toplevel)" || exit 1
git config core.hooksPath .githooks
# No commit.template: git errors out in any worktree/branch that lacks .gitmessage.
# Copy the trailers from .gitmessage by hand, or use `git commit -t .gitmessage`.
echo "hooks enabled (core.hooksPath=.githooks)"
