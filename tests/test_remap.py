"""The host-symbol remapper on tiny synthetic jars written here; no Bitwig bytes, no JVM."""

import json
import re
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from remap import classfile, inventory, table
from remap.__main__ import main, run
from remap.bytecode import tokens

OBJECT = "java/lang/Object"
RETURN, ALOAD_0, ILOAD_1, IRETURN, ICONST_1, ICONST_2, IADD = 0xB1, 0x2A, 0x1B, 0xAC, 0x04, 0x05, 0x60
OPCODES = {"getfield": 0xB4, "invokevirtual": 0xB6, "invokespecial": 0xB7, "invokestatic": 0xB8}
# tableswitch 0..1, lookupswitch {7}, wide iinc, wide iload, ifeq, goto_w: switches start at offset 0, so 3 pad bytes
SWITCHES = (
    bytes([0xAA, 0, 0, 0])
    + struct.pack(">iii", 20, 0, 1)
    + struct.pack(">ii", 20, 20)
    + bytes([0xAB, 0, 0, 0])
    + struct.pack(">iiii", 12, 1, 7, 12)
    + bytes([0xC4, 0x84, 0, 1, 0, 1, 0xC4, 0x15, 0, 1, 0x99, 0, 3, 0xC8, 0, 0, 0, 5])
)


class Pool:
    """Constant-pool writer; entries are interned so equal constants share one index."""

    def __init__(self) -> None:
        self.data = bytearray()
        self.index: dict[tuple, int] = {}
        self.next = 1

    def add(self, key: tuple, payload: bytes, slots: int = 1) -> int:
        if key not in self.index:
            self.index[key], self.next = self.next, self.next + slots
            self.data += payload
        return self.index[key]

    def utf8(self, text: str) -> int:
        raw = text.encode()
        return self.add(("u", text), bytes([1]) + struct.pack(">H", len(raw)) + raw)

    def cls(self, name: str) -> int:
        return self.add(("c", name), bytes([7]) + struct.pack(">H", self.utf8(name)))

    def string(self, text: str) -> int:
        return self.add(("s", text), bytes([8]) + struct.pack(">H", self.utf8(text)))

    def ref(self, tag: int, owner: str, name: str, desc: str) -> int:
        nat = self.add(("nt", name, desc), bytes([12]) + struct.pack(">HH", self.utf8(name), self.utf8(desc)))
        return self.add(("r", tag, owner, name, desc), bytes([tag]) + struct.pack(">HH", self.cls(owner), nat))


@dataclass
class Spec:
    name: str
    methods: list[tuple] = field(default_factory=list)  # (access, name, desc, instructions or None)
    fields: list[tuple] = field(default_factory=list)  # (access, name, desc)
    interfaces: list[str] = field(default_factory=list)
    access: int = 0x21


def assemble(code: list, pool: Pool, cls, member) -> bytes:
    out = bytearray()
    for ins in code:
        if isinstance(ins, int):
            out.append(ins)
        elif isinstance(ins, bytes):
            out += ins
        elif ins[0] == "ldc":
            out += bytes([0x12, pool.string(ins[1])])
        elif ins[0] == "ldc2":
            out += bytes([0x14]) + struct.pack(">H", pool.add(("J", ins[1]), bytes([5]) + struct.pack(">q", ins[1]), 2))
        elif ins[0] == "invokeinterface":
            _, owner, name, desc = ins
            out += bytes([0xB9]) + struct.pack(">H", pool.ref(11, cls(owner), member(owner, name), desc)) + b"\x01\x00"
        else:
            op, owner, name, desc = ins
            tag = 9 if op == "getfield" else 10
            desc = desc if owner == OBJECT else rename_desc(desc, cls)
            out += bytes([OPCODES[op]]) + struct.pack(">H", pool.ref(tag, cls(owner), member(owner, name), desc))
    return bytes(out)


def rename_desc(desc: str, cls) -> str:
    return re.sub(r"L([^;]+);", lambda m: "L" + cls(m.group(1)) + ";", desc)


def class_bytes(spec: Spec, cls, member, extra_constants: bool = False) -> bytes:
    pool = Pool()
    this, sup = pool.cls(cls(spec.name)), pool.cls(OBJECT)
    interfaces = [pool.cls(cls(i)) for i in spec.interfaces]
    if extra_constants:  # tags the parser must step over
        pool.add(("f",), bytes([4]) + struct.pack(">f", 1.5))
        pool.add(("mt",), bytes([16]) + struct.pack(">H", pool.utf8("()V")))
        pool.add(("mh",), bytes([15, 6]) + struct.pack(">H", pool.ref(10, OBJECT, "hashCode", "()I")))
        pool.add(("dyn",), bytes([18]) + struct.pack(">HH", 0, pool.index[("nt", "hashCode", "()I")]))
        pool.add(("mod",), bytes([19]) + struct.pack(">H", pool.utf8("mod")))
    members = bytearray()
    for access, name, desc in spec.fields:
        members += struct.pack(
            ">HHHH", access, pool.utf8(member(spec.name, name)), pool.utf8(rename_desc(desc, cls)), 0
        )
    methods = bytearray()
    for access, name, desc, code in spec.methods:
        head = struct.pack(">HHH", access, pool.utf8(member(spec.name, name)), pool.utf8(rename_desc(desc, cls)))
        if code is None:
            methods += head + struct.pack(">H", 0)
            continue
        body = assemble(code, pool, cls, member)
        attribute = struct.pack(">HHI", 4, 4, len(body)) + body + struct.pack(">HH", 0, 0)
        methods += head + struct.pack(">HHI", 1, pool.utf8("Code"), len(attribute)) + attribute
    head = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 55, pool.next) + pool.data
    head += struct.pack(">HHHH", spec.access, this, sup, len(interfaces)) + b"".join(
        struct.pack(">H", i) for i in interfaces
    )
    return bytes(head + struct.pack(">H", len(spec.fields)) + members + struct.pack(">H", len(spec.methods)) + methods)


def write_jar(path: Path, specs: list[Spec], cls=lambda n: n, member=lambda o, n: n) -> Path:
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        for spec in specs:
            jar.writestr(cls(spec.name) + ".class", class_bytes(spec, cls, member, spec.name == "com/acme/Host"))
    return path


HOST = "com/acme/Host"
CLASSES = {"b": "q", "c": "r", "d": "s", "f": "t", "g": "v", "h": "w", "i": "x"}
MEMBERS = {
    (HOST, "aB"): "xQ",
    (HOST, "eF"): "wR",
    (HOST, "hh"): "kk",
    (HOST, "ii"): "jj",
    ("b", "cD"): "zz",
    ("b", "gH"): "yy",
    ("c", "iJ"): "vv",
    ("d", "iJ"): "vv",
    ("d", "kL"): "uu",
    ("f", "xA"): "pp",
    ("f", "yB"): "oo",
    ("f", "zC"): "nn",
    **{("g", n): n[::-1] for n in ("aa", "ab", "ac", "ad", "ae", "ah", "ai")},
}


def specs(new: bool) -> list[Spec]:
    out = [
        Spec(
            HOST,
            fields=[(0x2, "eF", "Lb;"), (0x2, "hh", "Lh;")],
            methods=[
                (
                    0x1,
                    "aB",
                    "()V",
                    [ALOAD_0, ("getfield", HOST, "eF", "Lb;"), ("invokevirtual", "b", "cD", "()V"), RETURN],
                ),
                (0x1, "run", "()V", [("ldc", "host"), ("ldc2", 7), ("invokeinterface", "c", "iJ", "(I)I"), RETURN]),
                (0x1, "ii", "(Li;)V", [RETURN]),
                (0x9, "jump", "(I)V", [SWITCHES, RETURN]),
            ],
        ),
        Spec(
            "b",
            methods=[
                (0x1, "<init>", "()V", [ALOAD_0, ("invokespecial", OBJECT, "<init>", "()V"), RETURN]),
                (0x1, "cD", "()V", [("ldc", "hello"), RETURN]),
                (0x1, "gH", "()V", [("ldc", "world"), RETURN]),
            ],
        ),
        Spec("c", access=0x601, methods=[(0x401, "iJ", "(I)I", None)]),
        Spec(
            "d",
            interfaces=["c"],
            methods=[
                (0x1, "iJ", "(I)I", [ILOAD_1, IRETURN]),
                (0x1, "kL", "(I)I", [ILOAD_1, ICONST_2 if new else ICONST_1, IADD, IRETURN]),
                *((0x1, n, d, [RETURN]) for n, d in (("p1", "(J)V"), ("p2", "(F)V"), ("p3", "(D)V"))),
            ],
        ),
        Spec(
            "f",
            methods=[(0x1, "xA", "()V", [RETURN]), (0x1, "yB", "()V", [RETURN]), (0x1, "zC", "(I)V", [("ldc", "f")])],
        ),
        Spec(
            "g",
            methods=[
                (0x1, n, d, [("ldc", "g")] if n == "aa" else [RETURN])
                for n, d in (
                    ("aa", "(I)V"),
                    ("ad", "()V"),
                    ("ae", "()V"),
                    ("ab", "(J)V"),
                    ("ah", "(Z)V"),
                    ("ac", "(F)V"),
                    ("ai", "(Z)V"),
                )
            ],
        ),
        Spec("h"),
        Spec("i"),
    ]
    return [*out, Spec("u", methods=[(0x1, "zz", "()V", [("ldc", "new only")])])] if new else [*out, Spec("e")]


HARNESS = """LOG\tTYPE\tcom.acme.Host
LOG\tMETHOD\tcom.acme.Host\taB\tvoid\tfalse\t
LOG\tDECL\tcom.acme.Host\taB\tcom.acme.Host
LOG\tMETHOD\tb\tcD\tvoid\tfalse\t
LOG\tMETHOD\td\tiJ\tint\tfalse\tint
LOG\tMETHOD\td\tkL\tint\tfalse\tint
LOG\tDECL\td\tkL\tc
LOG\tCTOR\tb\t
LOG\tCONST\tcom.acme.Host\teF\tb
LOG\tMETHOD\tf\txA\tvoid\tfalse\t
LOG\tMETHOD\tgone\tzZ\tvoid\tfalse\t
LOG\tMETHOD\tb\tnope\tvoid\tfalse\t
LOG\tMETHOD\tcom.acme.Host\tii\tvoid\tfalse\ti
GATE\topen
"""
EXTRA = """# kind\towner\tname\treturn\tstatic\tparams\twhy
TYPE\te\t\t\t\t\tdeleted in the new jar
GETMETHODS\tc\t\t\t\t\tinterface scan
OVERRIDE\tc\tiJ\tint\tfalse\tint\tcompile-time override

DECLARED\tb\tgH\tvoid\tfalse\t\tgetDeclaredMethod
""" + "\n".join(
    f"METHOD\tg\t{n}\tvoid\tfalse\t{p}\torder"
    for n, p in (("ad", ""), ("ae", ""), ("ah", "boolean"), ("ai", "boolean"))
)


@pytest.fixture
def jars(tmp_path):
    write_jar(tmp_path / "old.jar", specs(False))
    write_jar(
        tmp_path / "new.jar",
        specs(True),
        lambda n: CLASSES.get(n, n),
        lambda owner, name: MEMBERS.get((owner, name), name),
    )
    (tmp_path / "run.txt").write_text(HARNESS, encoding="utf-8")
    (tmp_path / "extra.tsv").write_text(EXTRA, encoding="utf-8")
    return tmp_path


def test_maps_each_member_in_its_own_class(jars):
    result = run(jars / "old.jar", jars / "new.jar", jars / "run.txt", jars / "extra.tsv")
    s = result["symbols"]
    assert {k: (v["new"], v["status"]) for k, v in s.items() if v["kind"] == "class"} == {
        "com.acme.Host": ("com.acme.Host", "exact"),
        "b": ("q", "exact"),
        "c": ("r", "exact"),
        "d": ("s", "exact"),
        "f": ("t", "exact"),
        "i": ("x", "confident"),
        "g": ("v", "exact"),
        "e": (None, "unmapped"),
    }
    assert (s["com.acme.Host.aB()V"]["new"], s["com.acme.Host.aB()V"]["status"]) == ("com.acme.Host.xQ()V", "exact")
    assert s["com.acme.Host.aB()V"]["body"] == "renamed-only"
    assert s["b.cD()V"]["new"] == "q.zz()V"
    assert s["b.cD()V"]["evidence"].startswith("bytecode-ref votes=1")
    assert s["b.gH()V"]["new"] == "q.yy()V"
    assert s["b.<init>()V"]["body"] == "identical"
    assert s["com.acme.Host.eF:Lb;"]["new"] == "com.acme.Host.wR:Lq;"
    assert (s["d.iJ(I)I"]["new"], s["d.kL(I)I"]["body"]) == ("s.vv(I)I", "changed")
    assert (s["c.iJ(I)I"]["new"], s["c.iJ(I)I"]["kind"], s["c.iJ(I)I"]["body"]) == ("r.vv(I)I", "override", "no-body")
    assert s["g.ad()V"]["new"] == "v.da()V"
    assert s["g.ae()V"]["evidence"].startswith("declaration-order gap")
    assert s["g.ah(Z)V"]["new"] == "v.ha(Z)V"
    assert s["g.ai(Z)V"]["new"] == "v.ia(Z)V"
    assert s["f.xA()V"]["status"] == "uncertain"
    assert sorted(s["f.xA()V"]["candidates"]) == ["oo()V", "pp()V"]
    assert s["gone.zZ()V"]["evidence"] == "owner not in old jar"
    assert s["b.nope()V"]["evidence"] == "member not found in old jar"
    assert "DECL MISMATCH" in s["d.kL(I)I"]["evidence"]
    assert any(check.startswith("getMethods(c)") for check in result["checks"])


def test_without_order_anchoring_order_only_members_stay_uncertain(jars):
    s = run(jars / "old.jar", jars / "new.jar", jars / "run.txt", jars / "extra.tsv", use_order=False)["symbols"]
    assert s["g.ad()V"]["status"] == "uncertain"
    assert s["g.ah(Z)V"]["status"] == "uncertain"
    assert s["com.acme.Host.aB()V"]["status"] == "exact"


def test_cli_writes_map_report_and_class_map(jars, capsys):
    notes = jars / "notes.md"
    notes.write_text("Reviewed by hand.\n", encoding="utf-8")
    args = [str(jars / n) for n in ("old.jar", "new.jar")]
    args += ["--harness", str(jars / "run.txt"), "--extra", str(jars / "extra.tsv"), "--notes", str(notes)]
    args += ["--map", str(jars / "map.json"), "--report", str(jars / "map.md"), "--classes", str(jars / "cls.json")]
    assert main(args) == 0
    symbols = json.loads((jars / "map.json").read_text(encoding="utf-8"))["symbols"]
    report = (jars / "map.md").read_text(encoding="utf-8")
    assert symbols["b.cD()V"]["new"] == "q.zz()V"
    assert json.loads((jars / "cls.json").read_text(encoding="utf-8"))["map"]["h"] == "w"
    assert "Reviewed by hand." in report
    assert "**uncertain** `f.xA()V`" in report
    assert "`d.kL(I)I` -> `s.uu(I)I` [changed]" in report
    assert str(jars) not in report
    assert "symbols:" in capsys.readouterr().err


def test_parser_reads_bytes_only_and_rejects_malformed_input(jars):
    with zipfile.ZipFile(jars / "old.jar") as jar:
        data = jar.read("com/acme/Host.class")
    host = classfile.parse(data)
    assert (host.name, host.super, host.strings) == (HOST, OBJECT, ["host"])
    jump = next(m for m in host.methods if m.name == "jump")
    assert jump.code is not None
    assert tokens(jump.code, host.cp, str, str, str)[:4] == [
        "tableswitch 0 1",
        "lookupswitch [7]",
        "c48400010001",
        "c4150001",
    ]
    with pytest.raises(ValueError, match="truncated"):
        classfile.parse(data[:-5])
    with pytest.raises(ValueError, match="tag 99"):
        classfile.parse(data[:10] + bytes([99]) + data[11:])


def test_inventory_descriptors():
    assert (
        inventory.method_desc("[Ljava.lang.String;", "int,java.lang.Object")
        == "(ILjava/lang/Object;)[Ljava/lang/String;"
    )
    assert inventory.field_desc("") == ""


NAMES: table.Table = {
    "classes": {"host": "com.acme.Host", "b": "b", "c": "c", "d": "d"},
    "members": {
        "host.run": {"owner": "host", "name": "aB", "desc": "()V"},
        "host.field": {"owner": "host", "name": "eF", "desc": "Lb;"},
        "b.world": {"owner": "b", "name": "gH", "desc": "()V"},
        "d.inherited": {"owner": "d", "name": "iJ", "desc": "(I)I"},
        "c.decided": None,
    },
}


def test_a_name_table_maps_to_the_next_jar_by_owner_name_and_descriptor(jars):
    names = jars / "names_old.json"
    names.write_text(table.render(NAMES), encoding="utf-8")
    args = [str(jars / "old.jar"), str(jars / "new.jar"), "--from-table", str(names), "--out", str(jars / "new.json")]
    assert main([*args, "--report", str(jars / "names.md")]) == 0
    assert json.loads((jars / "new.json").read_text(encoding="utf-8")) == {
        "classes": {"b": "q", "c": "r", "d": "s", "host": "com.acme.Host"},
        "members": {
            "b.world": {"owner": "b", "name": "yy", "desc": "()V"},
            "c.decided": None,
            "d.inherited": {"owner": "d", "name": "vv", "desc": "(I)I"},
            "host.field": {"owner": "host", "name": "wR", "desc": "Lq;"},
            "host.run": {"owner": "host", "name": "xQ", "desc": "()V"},
        },
    }
    assert "## Totals by status" in (jars / "names.md").read_text(encoding="utf-8")
    back = jars / "back.json"
    assert (
        main([str(jars / "new.jar"), str(jars / "old.jar"), "--from-table", str(jars / "new.json"), "--out", str(back)])
        == 0
    )
    assert json.loads(back.read_text(encoding="utf-8")) == json.loads(names.read_text(encoding="utf-8")), (
        "mapping back restores the source table"
    )


def test_a_key_not_mapped_exact_or_confident_fails_the_table(jars, capsys):
    names = jars / "names_old.json"
    uncertain: table.Table = {"classes": {"f": "f"}, "members": {"f.x": {"owner": "f", "name": "xA", "desc": "()V"}}}
    names.write_text(table.render(uncertain), encoding="utf-8")
    out = jars / "new.json"
    assert main([str(jars / "old.jar"), str(jars / "new.jar"), "--from-table", str(names), "--out", str(out)]) == 1
    assert "member f.x: uncertain" in capsys.readouterr().err
    assert not out.exists()
    for incomplete, message in (("--from-table", "--from-table requires --out"), ("--harness", "--harness requires")):
        with pytest.raises(SystemExit):
            main([str(jars / "old.jar"), str(jars / "new.jar"), incomplete, str(names)])
        assert message in capsys.readouterr().err


def test_verify_resolves_every_member_and_records_the_hashes(jars, capsys):
    names, record = jars / "names_old.json", jars / "verified.json"
    names.write_text(table.render(NAMES), encoding="utf-8")
    assert table.main(["verify", str(names), str(jars / "old.jar"), "--record", str(record)]) == 0
    assert json.loads(record.read_text(encoding="utf-8"))["names_old"] == {
        "jarSha256": classfile.sha256(jars / "old.jar"),
        "tableSha256": table.file_sha256(names),
    }
    wrong = json.loads(names.read_text(encoding="utf-8"))
    wrong["members"]["host.run"]["desc"] = "(I)V"
    wrong["classes"]["gone"] = "e.Gone"
    names.write_text(table.render(wrong), encoding="utf-8")
    assert table.main(["verify", str(names), str(jars / "old.jar")]) == 1
    err = capsys.readouterr().err
    assert "member host.run: aB(I)V does not resolve on its owner" in err
    assert "class gone: e.Gone is not in the jar" in err
    with pytest.raises(SystemExit, match=r"host\.run does not resolve"):
        table.symbols(wrong, classfile.read_jar(jars / "old.jar"))


def test_table_shape_and_lookup_kinds(tmp_path):
    bad: table.Table = {
        "classes": {"k": "k"},
        "members": {
            "k.fields": {"owner": "k", "name": "x"},
            "k.owner": {"owner": "nope", "name": "x", "desc": "()V"},
            "k.desc": {"owner": "k", "name": "x", "desc": "(Lk)V"},
            "k.fine": {"owner": "k", "name": "x", "desc": "([[Ljava/lang/String;J)[I"},
        },
    }
    assert table.shape_errors(bad) == [
        "member k.fields: fields ['name', 'owner'], expected owner, name, desc",
        "member k.owner: unknown owner 'nope' or empty name",
        "member k.desc: malformed descriptor '(Lk)V'",
    ]
    jar = classfile.read_jar(write_jar(tmp_path / "k.jar", [Spec("k", methods=[(0x2, "hid", "()V", [RETURN])])]))
    names: table.Table = {
        "classes": {"k": "k", "gone": "gone"},
        "members": {
            "k.hid": {"owner": "k", "name": "hid", "desc": "()V"},
            "gone.x": {"owner": "gone", "name": "x", "desc": "()V"},
        },
    }
    hierarchy = table.Hierarchy(jar)
    hidden = table.symbol(names, "k.hid", hierarchy)
    assert hidden is not None
    assert hidden.kind == "DECLARED"
    assert table.symbol(names, "gone.x", hierarchy) is None
