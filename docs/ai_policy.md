# Generative AI policy compliance

Borg follows NLnet's [Generative AI policy](https://nlnet.nl/foundation/policies/generativeAI)
(v1.1, 2026-01-25). This page is how we do it.

## Rules for contributors

1. **Every commit on `main` declares its AI use** in a trailer:
   `AI-Assisted: <model and version>` or `AI-Assisted: none`.
2. **If AI was used**, the commit also carries
   `AI-Prompts:` (the relevant prompts, quoted or closely paraphrased -- a topic
   summary is not enough; the prompts are what shows the result is not
   vibe-coded) and `Reviewed-by:` (the human who read, tested and takes
   responsibility for the diff). Purely AI-generated, unreviewed work is not
   eligible for grant payment.
   Three kinds of commit are distinguished:
   - **Written by a human:** `AI-Assisted: none`. If AI only helped find or
     diagnose a bug, or to test, and a human wrote the code, use
     `AI-Assisted: none (debugging/testing only)`; no prompts are required.
   - **AI-generated, closely reviewed, obvious from an external reference**
     (the Vulkan spec, Mesa, an existing Borg module): `AI-Assisted: <model>`,
     `Reviewed-by:`, and `AI-Basis:` naming the reference that makes the change
     near-deterministic (e.g. `Vulkan 1.3 section on VkBlendFactor; same
     pattern as BorgBlend`). This is a disclosure category, not an exemption:
     whether NLnet treats it differently is for NLnet to say.
   - **AI-driven design or debugging work:** `AI-Assisted`, a fuller
     `AI-Prompts` summary, `Reviewed-by`.
3. **Experiments live on `exp/*` branches** (or worktrees). They are local,
   never pushed (the pre-push hook refuses), and exempt from the trailers.
   Only a reviewed, squashed commit reaches `main`.
   `feat/*` branches are likewise exempt from the trailer check at commit time;
   `scripts/review_commits.py` adds `Reviewed-by` before anything goes to `main`.
4. **Full session logs are private but retained**, in the separate private repo
   `~/work/Borg-provenance`. A `post-commit` hook archives them on every commit
   (`scripts/archive_ai_sessions.sh`); each archive commit is titled with the
   public commit's hash (`Borg-Commit: <sha>`), so the log for any public commit
   is found with `git log --grep <sha>` there. They are handed to NLnet on
   request, scrubbed first. Commits do not carry session URLs: outsiders cannot
   open them.
5. **Generated code is checked for copied material** before it lands,
   especially anything resembling upstream sources (Mesa, firmware, vendor code).
6. Deterministic generation (Chisel to Verilog, PeakRDL, borgc) is not GenAI
   and needs no declaration.

## Enforcement

- `scripts/install_hooks.sh` (once per clone) enables the `commit-msg` and
  `pre-push` hooks (`git commit -t .gitmessage` gives a message template).
- `.github/workflows/ai-policy.yaml` runs the same `scripts/check_ai_policy.py`
  on every push to `main` and every pull request, plus the README check.
- The README must keep its "Generative AI use" section.

## Full session records

The relevant prompts live in the public commit message. In addition, scrubbed
session records are kept in a separate private repository, titled with the
public commit's hash, and are available to NLnet on request. They are a backup,
not the main evidence.
