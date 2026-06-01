LATEXMK = latexmk
PDFLATEX = pdflatex

.PHONY: all edit poster abstract view clean

all: poster abstract

poster: poster.pdf

../poster_diagram.svg: ../../scripts/gen_poster_diagram.py
	cd ../.. && python3 scripts/gen_poster_diagram.py --output docs/poster_diagram

hw_diagram.pdf: ../poster_diagram.svg
	inkscape --export-type=pdf --export-filename=hw_diagram.pdf ../poster_diagram.svg

poster.pdf: poster.tex hw_diagram.pdf
	-$(PDFLATEX) -interaction=nonstopmode poster.tex

abstract:
	-$(LATEXMK) -pdf -f -interaction=nonstopmode abstract.tex

edit:
	vim poster.tex

view: poster.pdf
	papers poster.pdf

compress: poster.pdf
	gs -sDEVICE=pdfwrite -dCompatibilityLevel=1.4 -dPDFSETTINGS=/printer -dNOPAUSE -dQUIET -dBATCH -sOutputFile=poster_compressed.pdf poster.pdf

viewabstract:
	@test -f abstract.pdf || $(MAKE) abstract
	papers abstract.pdf

clean:
	$(LATEXMK) -CA
	rm -f poster.pdf poster.log poster.aux hw_diagram.pdf
	rm -f *.bbl *.blg *.run.xml
