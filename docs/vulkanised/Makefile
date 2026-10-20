PDF = borg_vulkanised_2027.pdf

.PHONY: all show clean

all: $(PDF)

$(PDF): abstract.tex vkcube_ulx3s.jpg
	latexmk -pdf -interaction=nonstopmode -outdir=build abstract.tex
	cp build/abstract.pdf $@

show: $(PDF)
	papers $(PDF) &

clean:
	rm -rf build
