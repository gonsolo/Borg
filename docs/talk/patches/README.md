# Papers video-autoplay patch

Native GNOME Papers (the Evince successor: GTK4, poppler-glib, GPL-2.0,
https://gitlab.gnome.org/GNOME/papers) already has a real, working embedded-
video pipeline (`PpsMedia`/`GtkVideo`, decoding via GStreamer) for PDF Movie
and Screen+Rendition annotations -- it's just click-triggered, not autoplay,
and the F5 fullscreen "Present" mode (`PpsViewPresentation`) is a from-scratch
widget that never had any media support at all (it composites pre-rendered
page textures with slide-transition shaders, no child-widget concept).

`papers-video-autoplay.patch` adds autoplay+loop in the F5 presentation widget
(`libview/pps-view-presentation.c`), from scratch: a `GtkVideo` child
positioned over its annotation's rect (via the existing
`pps_view_presentation_get_page_area` helper), synced on every page change
including the initial page. An earlier revision of this patch also touched
the normal windowed view (`libview/pps-view.c`) the same way, but both being
active at once produced two independent media players/pipelines for the same
annotation running simultaneously (visible as two slightly-offset copies of
the video on screen) -- dropped since our actual use case is F5 only.

Two bugs worth knowing about if you extend this:
- Dropping the last ref on `PpsMedia` right after building the `GtkVideo`
  deletes the temp file GStreamer is about to open asynchronously (see
  `pps_media_from_poppler_rendition`'s "poppler-media-temp-file" qdata) --
  the ref must live as long as the player widget (`g_object_set_data_full`
  on it), not just the setup function.
- `gtk_video_set_loop()`/`gtk_video_set_autoplay()` only take effect for
  files loaded *after* the call (an explicit caveat in `gtk_video_set_loop`'s
  own docs) -- call them on an empty `gtk_video_new()` before
  `gtk_video_set_file()`, not after `gtk_video_new_for_file()`.

Built and verified against Papers 51.beta (commit
`593459ef82095f5202656d4453801c50f93bef55`, matching what Arch's `papers
50.2-1` package ships) using a PDF with a real PDF 1.5+ "Rendition Action"
Screen annotation carrying an embedded H.264 stream (produced by
`../scripts/add_video_annotation.py`, not by any LaTeX package -- pdflatex
has no native way to embed playable video, only a static image). Verified by
log: continuous CPU activity (GStreamer decode) across a 10s window far past
the clip's own ~3.87s length, and exactly one open temp-file handle
throughout -- confirms looping without a duplicate player.

## Reproducing the build

```sh
sudo pacman -S --needed qpdf python-pikepdf blueprint-compiler \
    gobject-introspection glib2-devel gi-docgen itstool

git clone https://gitlab.gnome.org/GNOME/papers.git
cd papers
git checkout 593459ef82095f5202656d4453801c50f93bef55
git apply /path/to/papers-video-autoplay.patch

# Build with the system (not nix devshell) toolchain -- a nix devshell whose
# default CC/CXX targets a different architecture (as this repo's does, for
# RISC-V firmware) silently produces a binary meson's own sanity check can't
# execute, or gcc/g++ mismatches against distro GTK4/poppler-glib headers.
env -i HOME="$HOME" PATH=/usr/bin:/bin CC=/usr/bin/gcc CXX=/usr/bin/g++ \
  meson setup build --prefix="$HOME/.local/opt/papers-video"
env -i HOME="$HOME" PATH=/usr/bin:/bin ninja -C build install
```

Then run via `../run-papers-video.sh`.

## Why not upstream this

The presentation-view half is a real feature Papers doesn't have and could
plausibly land upstream, but it's scoped narrowly for this one use case
(rotation 0 only, one media widget per page, no play/pause controls). Filed
as a local patch rather than a PR for now -- revisit after the talk if there's
interest.
