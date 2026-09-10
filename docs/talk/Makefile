PDFLATEX = pdflatex

.PHONY: all talk frames edit view clean

all: view

talk: talk.pdf

talk.pdf: talk.tex cube_frames
	-$(PDFLATEX) -interaction=nonstopmode talk.tex
	-$(PDFLATEX) -interaction=nonstopmode talk.tex

# cube_frames/ (0-indexed PNGs, committed) is what talk.tex actually embeds
# via animate.sty -- pdflatex can't animate a .gif directly. Regenerate from
# cube.gif if it's ever missing or the gif is replaced.
frames: cube_frames

cube_frames: cube.gif
	rm -rf cube_frames
	mkdir cube_frames
	convert -coalesce cube.gif cube_frames/cube-%d.png

edit:
	vim talk.tex

view: talk.pdf
	papers talk.pdf

clean:
	rm -f talk.pdf talk.log talk.aux talk.nav talk.out talk.snm talk.toc talk.vrb
