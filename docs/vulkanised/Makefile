PDF = borg_vulkanised_2027.pdf

.PHONY: all show clean slides poster video art

all: show

$(PDF): abstract.tex vkcube_ulx3s.jpg
	latexmk -pdf -interaction=nonstopmode -outdir=build abstract.tex
	cp build/abstract.pdf $@

show: $(PDF)
	papers $(PDF) &

# The event's artwork (Khronos'), downloaded, never committed; without it the slides use plain colours.
ART = https://vulkan.org/images/4/9/2/0/e/4920e914e4948075e0e455a11708e14a580b340e-vulkanised-2027-header-25x1.jpg \
      https://vulkan.org/user/pages/09.events/vulkanised-2027/VulkanBkgd.png
art:
	mkdir -p build/art
	curl -sfL -o build/art/header.jpg $(word 1,$(ART))
	curl -sfL -o build/art/streaks.png $(word 2,$(ART))

slides: build/slides.pdf
poster: build/poster.pdf

# build/video.mp4 from the slides and the takes in video.list
video: slides
	./video.sh

build/%.pdf: %.tex
	latexmk -pdf -interaction=nonstopmode -outdir=build $<

clean:
	rm -rf build
