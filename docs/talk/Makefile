PDFLATEX = pdflatex

.PHONY: all talk edit view clean

all: talk

talk: talk.pdf

talk.pdf: talk.tex
	-$(PDFLATEX) -interaction=nonstopmode talk.tex
	-$(PDFLATEX) -interaction=nonstopmode talk.tex

edit:
	vim talk.tex

view: talk.pdf
	papers talk.pdf

clean:
	rm -f talk.pdf talk.log talk.aux talk.nav talk.out talk.snm talk.toc talk.vrb
