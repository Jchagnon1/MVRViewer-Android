# Fichiers d'essai de SPEC-ma-gdtfshare.md §8.1 (recherche GDTF Share, archives .zip,
# routeur), COPIE LOCALE ANDROID en attendant la source canonique du dépôt web
# (tests/fixtures/ma/, make_fixtures.py + share_reference.py, MANIFEST.sha256) — à
# REMPLACER par les copies web dès qu'elles existent (même arborescence, mêmes noms).
#
# Construits à partir des copies conformes déjà présentes (real/, synthetic/, models/,
# sources/). Octets DÉTERMINISTES : horodatage zip fixe (1980-01-01), ordre fixe.
# Les sorties attendues (getList_expected.json, EXPECTED.json) sont celles écrites dans la
# spec (§1.5, §8.1, §8.2-T12), recopiées telles quelles.
#
#   cd app/src/test/resources/ma && python3 make_share_fixtures.py
import json
import os
import struct
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DATE = (1980, 1, 1, 0, 0, 0)


def p(*parts):
    return os.path.join(HERE, *parts)


def read(*parts):
    with open(p(*parts), "rb") as f:
        return f.read()


def write(rel, data):
    path = p(rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def zip_bytes(entries):
    """entries : liste (nom, octets | None pour un dossier), dans l'ordre."""
    import io
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=DATE)
            info.external_attr = (0o40755 << 16) | 0x10 if data is None else (0o100644 << 16)
            info.compress_type = zipfile.ZIP_STORED if data is None else zipfile.ZIP_DEFLATED
            z.writestr(info, b"" if data is None else data)
    return buf.getvalue()


def tree(folder):
    """(nom interne, octets) des fichiers d'un dossier d'essai, triés (EXPECTED.json compris)."""
    out = []
    for n in sorted(os.listdir(p(folder))):
        full = p(folder, n)
        if os.path.isfile(full):
            out.append((folder + "/" + n, read(folder, n)))
    return out


def minimal_glb():
    """glTF 2.0 binaire VALIDE minimal : un triangle (3 positions float32)."""
    gltf = {
        "asset": {"version": "2.0"},
        "buffers": [{"byteLength": 36}],
        "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                       "min": [0, 0, 0], "max": [1, 1, 0]}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
        "nodes": [{"mesh": 0}],
        "scenes": [{"nodes": [0]}],
        "scene": 0,
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    binary = struct.pack("<9f", 0, 0, 0, 1, 0, 0, 0, 1, 0)
    total = 12 + 8 + len(js) + 8 + len(binary)
    return (b"glTF" + struct.pack("<II", 2, total)
            + struct.pack("<I", len(js)) + b"JSON" + js
            + struct.pack("<I", len(binary)) + b"BIN\x00" + binary)


# ---- share/ (§1.5, §8.1) ---------------------------------------------------------------

GETLIST_SAMPLE = """{"result":true,"timestamp":1790000000,"list":[
 {"rid":12345,"fixture":"MegaPointe","manufacturer":"Robe Lighting","revision":"V1.6 - DMX 39ch","creationDate":"1570494396","lastModified":"1690000000","uploader":"Manuf.","rating":"4.1","version":"1.1","creator":"robe_official","uuid":"AAAAAAAA-0001-4C7C-ACA2-AAAAAAAAAAAA","filesize":4809117,"modes":[{"0":{"name":"Mode 1 - 34ch","dmxfootprint":34}},{"1":{"name":"Mode 2 - 39ch","dmxfootprint":39}}]},
 {"rid":"12346","fixture":"MegaPointe","manufacturer":"Robe Lighting","revision":"  ","creationDate":1580000000,"lastModified":1580000000,"uploader":"User","rating":3,"version":1.0,"uuid":"AAAAAAAA-0001-4C7C-ACA2-AAAAAAAAAAAA","filesize":"5100000","modes":[{"name":"Mode 1","dmxfootprint":"34"}]},
 {"rid":777,"fixture":"Sharpy","manufacturer":"Clay Paky","revision":"2.0","creationDate":"","lastModified":null,"uploader":"","rating":null,"uuid":"BBBBBBBB-0002-4000-8000-BBBBBBBBBBBB","modes":{"0":{"name":"Standard","dmxfootprint":16},"1":{"name":"Vector","dmxfootprint":"20"},"2":{"name":"Broken","dmxfootprint":"x"},"3":{"name":"Shapes","dmxfootprint":24},"4":{"name":"Basic","dmxfootprint":12},"10":{"name":"Full","dmxfootprint":30}}},
 {"rid":778,"fixture":"Éclairage Test","manufacturer":"Éclairages Test","revision":"r1","creationDate":1600000000000,"lastModified":1600000000000,"uploader":"manufacturer","rating":"4.95","version":"1.2","uuid":"CCCCCCCC-0003-4000-8000-CCCCCCCCCCCC","filesize":70000000,"modes":[[{"name":"","dmxfootprint":1}]]},
 {"rid":0,"fixture":"Bad rid","manufacturer":"X","uuid":"DDDD"},
 {"rid":900,"fixture":"No uuid","manufacturer":"X"},
 {"rid":902,"fixture":"Empty uuid","manufacturer":"X","uuid":""},
 {"rid":901,"manufacturer":"Y","uuid":"EEEE"}
]}
"""

GETLIST_EXPECTED = """[{"rid":12345,"fixture":"MegaPointe","manufacturer":"Robe Lighting","uuid":"AAAAAAAA-0001-4C7C-ACA2-AAAAAAAAAAAA","rating":4.1,"revision":"V1.6 - DMX 39ch","creationDate":1570494396,"lastModified":1690000000,"uploader":"manufacturer","version":"1.1","filesize":4809117,"modes":[{"name":"Mode 1 - 34ch","footprint":34},{"name":"Mode 2 - 39ch","footprint":39}]},
 {"rid":12346,"fixture":"MegaPointe","manufacturer":"Robe Lighting","uuid":"AAAAAAAA-0001-4C7C-ACA2-AAAAAAAAAAAA","rating":3,"revision":"","creationDate":1580000000,"lastModified":1580000000,"uploader":"user","version":"1.0","filesize":5100000,"modes":[{"name":"Mode 1","footprint":34}]},
 {"rid":777,"fixture":"Sharpy","manufacturer":"Clay Paky","uuid":"BBBBBBBB-0002-4000-8000-BBBBBBBBBBBB","rating":null,"revision":"2.0","creationDate":null,"lastModified":null,"uploader":"","version":"","filesize":null,"modes":[{"name":"Standard","footprint":16},{"name":"Vector","footprint":20},{"name":"Shapes","footprint":24},{"name":"Basic","footprint":12},{"name":"Full","footprint":30}]},
 {"rid":778,"fixture":"Éclairage Test","manufacturer":"Éclairages Test","uuid":"CCCCCCCC-0003-4000-8000-CCCCCCCCCCCC","rating":4.95,"revision":"r1","creationDate":1600000000,"lastModified":1600000000,"uploader":"manufacturer","version":"1.2","filesize":70000000,"modes":[{"name":"","footprint":1}]},
 {"rid":901,"fixture":"?","manufacturer":"Y","uuid":"EEEE","rating":null,"revision":"","creationDate":null,"lastModified":null,"uploader":"","version":"","filesize":null,"modes":[]}]
"""

write("share/getList_sample.json", GETLIST_SAMPLE.encode("utf-8"))
write("share/getList_expected.json", GETLIST_EXPECTED.encode("utf-8"))

wash_desc = zipfile.ZipFile(p("synthetic", "gdtf_test_wash.gdtf")).read("description.xml")
wash3d = zip_bytes([("description.xml", wash_desc), ("models/gltf/Body.glb", minimal_glb())])
write("share/gdtf_test_wash_3d.gdtf", wash3d)

# ---- zips/ (§8.1) -------------------------------------------------------------------------

deeper = zip_bytes([("ma2_break2.xml", read("synthetic", "ma2_break2.xml"))])
inner = zip_bytes([("ma2_dimmer.xml", read("synthetic", "ma2_dimmer.xml")), ("deeper.zip", deeper)])
ma_profiles = zip_bytes(
    [("real/", None)] + tree("real")
    + [("synthetic/", None)] + tree("synthetic")
    + [("__MACOSX/real/._x.xml", b"\x00\x05\x16\x07 AppleDouble"),
       ("synthetic/.DS_Store", b"\x00\x00\x00\x01Bud1"),
       ("nested/", None), ("nested/inner.zip", inner)]
)
write("zips/ma_profiles.zip", ma_profiles)
write("zips/no_profiles.zip", zip_bytes([("readme.txt", b"Pas de profil ici.\n"),
                                         ("photo.jpg", b"\xff\xd8\xff\xe0" + bytes(64) + b"\xff\xd9")]))
write("zips/corrupt.zip", b"PK\x03\x04" + bytes(range(200)))
write("zips/huge_entry.zip", zip_bytes([("huge.xml", b"0" * (65 * 1048576))]))

ZIPS_EXPECTED = {
    "ma_profiles.zip": {"added": 9, "duplicates": 1, "ignored": 3, "errors": 7, "protected": 1, "progressTotal": 17,
                        "duplicate": "ma_profiles.zip › nested/inner.zip › ma2_dimmer.xml",
                        "errorNames": {
                            "unreadable": ["ma_profiles.zip › synthetic/corrupt.xml", "ma_profiles.zip › synthetic/corrupt.xmlp"],
                            "unknown": ["ma_profiles.zip › synthetic/gdtf_no_description.gdtf",
                                        "ma_profiles.zip › synthetic/gma3_macro.xml",
                                        "ma_profiles.zip › synthetic/other_root.xml"],
                            "empty": ["ma_profiles.zip › synthetic/ma2_only_virtual.xml"],
                            "protected": ["ma_profiles.zip › synthetic/protected.pxml"]}},
    "ma_profiles.zip#2": {"added": 0, "duplicates": 10, "ignored": 3, "errors": 7, "protected": 1},
    "no_profiles.zip": {"added": 0, "duplicates": 0, "ignored": 2, "errors": 1, "protected": 0,
                        "lines": ["no_profiles.zip — Aucun profil MA ni GDTF dans cette archive."]},
    "corrupt.zip": {"added": 0, "duplicates": 0, "ignored": 0, "errors": 1, "protected": 0,
                    "lines": ["corrupt.zip — Archive .zip illisible ou corrompue."]},
    "huge_entry.zip": {"added": 0, "duplicates": 0, "ignored": 0, "errors": 1, "protected": 0,
                       "lines": ["huge_entry.zip › huge.xml — Fichier trop volumineux (65 Mo, maximum 64 Mo)."]},
}
write("zips/EXPECTED.json", (json.dumps(ZIPS_EXPECTED, ensure_ascii=False, indent=1) + "\n").encode("utf-8"))

# ---- routing/ (§8.2-T12) -------------------------------------------------------------------

scene_xml = b'<?xml version="1.0" encoding="UTF-8"?>\n<GeneralSceneDescription verMajor="1" verMinor="5"/>\n'
write("routing/scene.mvr", zip_bytes([("GeneralSceneDescription.xml", scene_xml), ("box.3ds", b"MM\x00\x00")]))
write("routing/gdtf_test_wash.zip", read("synthetic", "gdtf_test_wash.gdtf"))
write("routing/gdtf_3d.zip", wash3d)
write("routing/warehouse.zip", zip_bytes([("model.dae", read("models", "box_dae_zup_inch.dae")),
                                          ("textures/a.png", b"\x89PNG\r\n\x1a\n" + bytes(16))]))
write("routing/mixed.zip", zip_bytes([("a.xml", read("synthetic", "ma2_dimmer.xml")),
                                      ("b.dae", read("models", "box_dae_zup_inch.dae"))]))
write("routing/scene_with_xml.zip", zip_bytes([("GeneralSceneDescription.xml", scene_xml),
                                               ("profiles/a.xml", read("synthetic", "ma2_dimmer.xml"))]))
ROUTING = [
    ["routing/scene.mvr", "scene.mvr", "mvr"],
    ["zips/ma_profiles.zip", "ma_profiles.zip", "maProfiles"],
    ["synthetic/gdtf_test_wash.gdtf", "gdtf_test_wash.gdtf", "gdtf"],
    ["routing/gdtf_test_wash.zip", "gdtf_test_wash.zip", "gdtf"],
    ["routing/gdtf_3d.zip", "gdtf_3d.zip", "gdtf"],
    ["synthetic/MVR_Viewer_Tests@LED_Bar_3_RGB_PT@Testeur_MVR.xmlp", "MVR_Viewer_Tests@LED_Bar_3_RGB_PT@Testeur_MVR.xmlp", "maProfiles"],
    ["synthetic/protected.pxml", "protected.pxml", "maProfiles"],
    ["synthetic/MVR_Viewer_Tests@Test_Spot_4_Pixels@Testeur_MVR.xml", "MVR_Viewer_Tests@Test_Spot_4_Pixels@Testeur_MVR.xml", "maProfiles"],
    ["synthetic/other_root.xml", "other_root.xml", "mvr"],
    ["models/box_obj_yup_meters.obj", "box_obj_yup_meters.obj", "model"],
    ["routing/warehouse.zip", "warehouse.zip", "model"],
    ["routing/mixed.zip", "mixed.zip", "model"],
    ["zips/no_profiles.zip", "no_profiles.zip", "mvr"],
    ["routing/scene_with_xml.zip", "scene_with_xml.zip", "mvr"],
    ["zips/ma_profiles.zip", None, "maProfiles"],
    ["synthetic/gdtf_test_wash.gdtf", None, "gdtf"],
]
write("routing/EXPECTED.json", (json.dumps(ROUTING, ensure_ascii=False, indent=1) + "\n").encode("utf-8"))
print("ok")
