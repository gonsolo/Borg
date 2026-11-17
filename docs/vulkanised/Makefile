PDF = borg_vulkanised_2027.pdf

.PHONY: all show clean slides poster video

all: show

$(PDF): abstract.tex vkcube_ulx3s.jpg
	latexmk -pdf -interaction=nonstopmode -outdir=build abstract.tex
	cp build/abstract.pdf $@

show: $(PDF)
	papers $(PDF) &

slides: build/slides.pdf
poster: build/poster.pdf

# build/video.mp4 from the slides and the takes in video.list
video: slides
	./video.sh

build/%.pdf: %.tex
	latexmk -pdf -interaction=nonstopmode -outdir=build $<

clean:
	rm -rf build
