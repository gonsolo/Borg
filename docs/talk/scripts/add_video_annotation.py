#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Add a native PDF Screen+Rendition video annotation to page 1 of talk.pdf.

pdflatex/beamer have no way to embed a playable video; this is a
post-processing step run after the normal LaTeX build (see the Makefile).

The annotation follows PDF 1.5+ "8.5 Rendition Actions" (ISO 32000-1 12.5.6.18
+ 13.2.3.2): a /Screen annotation whose /A (action) is /S /Rendition with a
/R (rendition) of subtype /MR (media rendition), whose /C (media clip) is a
/MCD (media clip data) pointing at a /Filespec with the video bytes embedded
directly via /EF (embedded file) -- no player SWF, no Flash. This is exactly
the structure Poppler's PDFDoc/Annot code (and therefore poppler-glib's
POPPLER_ANNOT_SCREEN + poppler_annot_screen_get_action() +
poppler_media_is_embedded()) already parses -- see
papers/libdocument/backend/pdf/pps-poppler.c:pdf_document_media_get_media_mapping
and papers/libview/pps-view-presentation.c:pps_view_presentation_sync_media
(our patch) in the Papers document viewer fork built for this talk.

Usage: add_video_annotation.py <in.pdf> <out.pdf> <video.mp4> <x1> <y1> <x2> <y2>
  Rect is in PDF points, page-1 bottom-left origin, matching talk.tex's
  tikz overlay placement for the poster image underneath.
"""
import sys

import pikepdf
from pikepdf import Dictionary, Name, Array, String


def main():
    if len(sys.argv) != 8:
        print(__doc__)
        sys.exit(1)

    in_pdf, out_pdf, video_path, x1, y1, x2, y2 = sys.argv[1:8]
    x1, y1, x2, y2 = (float(v) for v in (x1, y1, x2, y2))

    pdf = pikepdf.open(in_pdf)
    page = pdf.pages[0]

    with open(video_path, "rb") as f:
        video_bytes = f.read()

    ef_stream = pikepdf.Stream(pdf, video_bytes)
    ef_stream[Name.Type] = Name.EmbeddedFile
    # Streams must be indirect objects in PDF; nested Dictionaries don't.
    ef_stream_ref = pdf.make_indirect(ef_stream)

    filespec = Dictionary(
        Type=Name.Filespec,
        F=String(video_path.split("/")[-1]),
        UF=String(video_path.split("/")[-1]),
        EF=Dictionary(F=ef_stream_ref),
    )

    media_clip = Dictionary(
        Type=Name.MediaClip,
        S=Name.MCD,  # media clip data
        D=filespec,
        CT=String("video/mp4"),
        P=Dictionary(TF=String("TEMPACCESS")),  # allow playback from a temp copy
    )

    rendition = Dictionary(
        Type=Name.Rendition,
        S=Name.MR,  # media rendition
        C=media_clip,
    )

    rendition_action = Dictionary(
        Type=Name.Action,
        S=Name.Rendition,
        OP=0,  # 0 = play
        R=rendition,
    )

    screen_annot = Dictionary(
        Type=Name.Annot,
        Subtype=Name.Screen,
        Rect=Array([x1, y1, x2, y2]),
        P=page.obj,
        A=rendition_action,
        Border=Array([0, 0, 0]),
    )

    annots = page.obj.get(Name.Annots, Array())
    annots.append(pdf.make_indirect(screen_annot))
    page.obj[Name.Annots] = annots

    pdf.save(out_pdf)
    print(f"wrote {out_pdf}: page 1 now carries a Screen+Rendition annotation "
          f"({len(video_bytes)} bytes embedded) at rect ({x1},{y1})-({x2},{y2})")


if __name__ == "__main__":
    main()
