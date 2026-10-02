# Génère les .gdtf d'essai « écrits en flux » (MaProfileReaderTest.storedEntriesWithDataDescriptor…).
#
# `zipfile` de Python, sur un flux NON positionnable, pose le bit 3 (descripteur de données)
# sur chaque entrée : les tailles ne sont connues que du répertoire central. En STORED,
# java.util.zip.ZipInputStream refuse ces entrées (« only DEFLATED entries can have EXT
# descriptor ») alors que l'oracle ma_reference.py, iOS et le web les lisent.
# Sortie attendue de l'oracle pour les trois : gdtf, « M » / « T », Mode1 = ["Dim"].
#
#   python3 make_zipdd.py      (les horodatages changent : les octets aussi, pas la lecture)
import io
import zipfile


class NonSeekable(io.RawIOBase):
    def __init__(self):
        self.b = bytearray()

    def writable(self):
        return True

    def write(self, d):
        self.b += d
        return len(d)

    def seekable(self):
        return False

    def tell(self):
        return len(self.b)


DESC = b'''<?xml version="1.0" encoding="UTF-8"?>
<GDTF DataVersion="1.2"><FixtureType Name="T" Manufacturer="M">
<Geometries><Geometry Name="Body"/></Geometries>
<DMXModes><DMXMode Name="Mode1" Geometry="Body"><DMXChannels>
<DMXChannel Geometry="Body" Offset="1"><LogicalChannel Attribute="Dim"/></DMXChannel>
</DMXChannels></DMXMode></DMXModes></FixtureType></GDTF>'''


def build(name, method, order):
    out = NonSeekable()
    with zipfile.ZipFile(out, 'w', method) as z:
        for n in order:
            z.writestr(n, DESC if n == 'description.xml' else b'x' * 10)
    with open(name, 'wb') as f:
        f.write(bytes(out.b))


build('stored_dd_first.gdtf', zipfile.ZIP_STORED, ['description.xml'])
build('stored_dd_after.gdtf', zipfile.ZIP_STORED, ['other.bin', 'description.xml'])
build('deflated_dd.gdtf', zipfile.ZIP_DEFLATED, ['description.xml'])
