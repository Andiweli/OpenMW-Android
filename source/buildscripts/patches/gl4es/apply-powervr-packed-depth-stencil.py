#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-powervr-packed-depth-stencil.py <gl4es-source-dir>")

root = Path(sys.argv[1])
framebuffers = root / "src" / "gl" / "framebuffers.c"

MARKER = "OPENMW_ANDROID_GL4ES_POWERVR_PACKED_DEPTH_STENCIL"

if not framebuffers.is_file():
    raise SystemExit(f"missing expected gl4es source file: {framebuffers}")

text = framebuffers.read_text(encoding="utf-8").replace("\r\n", "\n")

if MARKER not in text:
    anchor = '''    // in that case, create first a STENCIL one then a DEPTH one....
    if ((internalformat == GL_DEPTH24_STENCIL8 && (hardext.depthstencil==0 || ((hardext.vendor&VEND_IMGTEC)==VEND_IMGTEC)))) {
'''
    replacement = '''    // in that case, create first a STENCIL one then a DEPTH one....
    // OPENMW_ANDROID_GL4ES_POWERVR_PACKED_DEPTH_STENCIL
    // Only split when packed depth/stencil is really unavailable. The former
    // forced split on every Imagination GPU dates from PowerVR SGX. Current
    // PowerVR Rogue/DXT drivers support packed depth/stencil (core in ES3) and
    // reject separate depth and stencil images as GL_FRAMEBUFFER_UNSUPPORTED,
    // which made OpenMW's first-person framebuffer (hands and weapons) and the
    // water reflection/refraction targets incomplete.
    if ((internalformat == GL_DEPTH24_STENCIL8 && hardext.depthstencil==0 && hardext.glsl300es==0)) {
'''
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(
            f"framebuffers.c/packed depth-stencil anchor: expected exactly one match, found {count}"
        )
    text = text.replace(anchor, replacement, 1)
    framebuffers.write_bytes(text.encode("utf-8"))
    print("Applied gl4es PowerVR packed depth/stencil renderbuffer fix.")
else:
    print("gl4es PowerVR packed depth/stencil renderbuffer fix is already applied.")

verify = framebuffers.read_text(encoding="utf-8")
if MARKER not in verify or "VEND_IMGTEC)==VEND_IMGTEC)))) {" in verify:
    raise SystemExit("gl4es PowerVR packed depth/stencil verification failed")
