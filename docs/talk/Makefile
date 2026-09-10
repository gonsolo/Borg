PDFLATEX = pdflatex

.PHONY: all talk video present frames edit view clean

all: view

talk: talk.pdf

# The self-contained deck: talk.pdf's title slide carries a static poster
# frame (cube_frames/cube-0.png) via a fixed-position tikz overlay -- see the
# comment in talk.tex for why the rect is pinned to absolute coordinates.
talk.pdf: talk.tex cube_frames
	-$(PDFLATEX) -interaction=nonstopmode talk.tex
	-$(PDFLATEX) -interaction=nonstopmode talk.tex

# talk_video.pdf additionally carries a real playable video: pdflatex has no
# native way to embed one, so this is a post-processing step that injects a
# PDF 1.5+ Screen+Rendition annotation (real Poppler/PDF structure, no Flash)
# with cube.mp4 embedded directly, at the exact same rect as the poster image
# above. Needs a patched `papers` to actually autoplay it -- see patches/.
talk_video.pdf: talk.pdf cube.mp4
	python3 scripts/add_video_annotation.py talk.pdf talk_video.pdf cube.mp4 \
		327 89 407 166
video: talk_video.pdf

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
	rm -f talk.pdf talk_video.pdf talk.log talk.aux talk.nav talk.out talk.snm talk.toc talk.vrb
