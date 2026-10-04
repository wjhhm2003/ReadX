# AndroidX libraries ship their own consumer rules. Add app-specific rules here.

# PdfBox-Android's optional JPEG2000 decoder is not bundled. ReadX uses system
# PdfRenderer for all raster output and never asks PDFBox to decode JPX images.
-dontwarn com.gemalto.jp2.JP2Decoder
