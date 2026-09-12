PDFLATEX = pdflatex

.PHONY: all talk video present frames edit view clean

all: view

talk: talk.pdf

# The self-contained deck: talk.pdf's title slide carries a static poster
# frame (cube_frames/cube-0.png) via a fixed-position tikz overlay -- see the
# comment in talk.tex for why the rect is pinned to absolute coordinates.
talk.pdf: talk.tex cube_frames borg_hw_diagram.pdf
	-$(PDFLATEX) -interaction=nonstopmode talk.tex
	-$(PDFLATEX) -interaction=nonstopmode talk.tex

# Borg-GPU-only hardware diagram for the "Borg GPU -- Hardware Modules"
# slide: `--groups borg` restricts scripts/gen_hw_diagram.py to just that one
# cluster (no Hutt/SoC/Software/Memory/Link, no cross-cluster edges to them
# -- see build_graph's `groups` param) since a slide needs one clean
# rectangle, not the full architecture diagram. The inter-tile link fabric
# (hardware/borg/{src,test/src}/link/) is its own "link" cluster in the full
# site diagram but deliberately left off this slide. Regenerate whenever
# hardware/borg/ changes; no dependency tracked here since that's a wide,
# frequently-touched tree -- `make talk` always rebuilds it.
borg_hw_diagram.pdf: ../../scripts/gen_hw_diagram.py
	cd ../.. && python3 scripts/gen_hw_diagram.py \
		--groups borg --format svg --output docs/talk/borg_hw_diagram
	inkscape --export-type=pdf --export-filename=borg_hw_diagram.pdf borg_hw_diagram.svg

# talk_video.pdf additionally carries a real playable video: pdflatex has no
# native way to embed one, so this is a post-processing step that injects a
# PDF 1.5+ Screen+Rendition annotation (real Poppler/PDF structure, no Flash)
# with cube.mp4 embedded directly, at the exact same rect as the poster image
# above. Needs a patched `papers` to actually autoplay it -- see patches/.
talk_video.pdf: talk.pdf cube.mp4 check-video-deps
	python3 scripts/add_video_annotation.py talk.pdf talk_video.pdf cube.mp4 \
		373.54 10 443.54 77.37
video: talk_video.pdf

# `pikepdf` (used by add_video_annotation.py above) is in the default
# devshell's pythonEnv now, but a stale/pre-flake-update shell or a `PATH`
# without nix's python3 on it at all would still hit a bare
# ModuleNotFoundError with no hint what to do -- catch it here instead.
check-video-deps:
	@python3 -c "import pikepdf" 2>/dev/null || { \
		echo "error: python3's pikepdf module is missing -- re-enter the nix" \
			 "devshell ('nix develop') to pick it up, or update your flake" \
			 "lock if it's still missing there." >&2; \
		exit 1; \
	}
.PHONY: check-video-deps

cube.mp4: cube.gif
	ffmpeg -y -i cube.gif -movflags +faststart -pix_fmt yuv420p \
		-vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" -c:v libx264 -crf 20 cube.mp4

# cube_frames/ (0-indexed PNGs, committed) supplies the title slide's static
# poster frame (cube_frames/cube-0.png). Regenerate from cube.gif if it's
# ever missing or the gif is replaced.
frames: cube_frames

cube_frames: cube.gif
	rm -rf cube_frames
	mkdir cube_frames
	convert -coalesce cube.gif cube_frames/cube-%d.png

edit:
	vim talk.tex

view: talk.pdf
	papers talk.pdf

# Present with the actual looping video, via the patched papers build --
# see patches/README.md to build it if $HOME/.local/opt/papers-video is missing.
present: talk_video.pdf
	./run-papers-video.sh talk_video.pdf

clean:
	rm -f talk.pdf talk_video.pdf talk.log talk.aux talk.nav talk.out talk.snm talk.toc talk.vrb \
		borg_hw_diagram.svg borg_hw_diagram.pdf
