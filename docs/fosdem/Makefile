.PHONY: all poster art clean

all: poster

poster: build/poster.pdf

# FOSDEM's logo, downloaded, never committed; without it the title has none.
art:
	mkdir -p build/art
	curl -sfL https://fosdem.org/2027/logo.svg | rsvg-convert -f pdf -o build/art/fosdem.pdf

build/%.pdf: %.tex
	latexmk -pdf -interaction=nonstopmode -outdir=build $<

clean:
	rm -rf build
