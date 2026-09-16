#!/usr/bin/env python3
"""Genera los anexos A (referencia clase por clase) y B (catálogo de pruebas)
de docs/DOCUMENTACION-CODIGO.md desde el código fuente, con el mismo formato
que la versión del 11-sep."""
import re
import sys
from pathlib import Path

SRC = Path("src/main/java/com/example/autransactional")
TEST = Path("src/test/java/com/example/autransactional")

# (título de sección, subcarpeta relativa a SRC)
SECTIONS = [
    ("Arranque", "."),
    ("Dominio — shared", "domain/shared"),
    ("Dominio — tenant", "domain/tenant"),
    ("Dominio — account", "domain/account"),
    ("Dominio — treasury", "domain/treasury"),
    ("Dominio — compliance", "domain/compliance"),
    ("Aplicación — auth", "application/auth"),
    ("Aplicación — tenant", "application/tenant"),
    ("Aplicación — account", "application/account"),
    ("Aplicación — treasury", "application/treasury"),
    ("Aplicación — compliance", "application/compliance"),
    ("Aplicación — platform", "application/platform"),
    ("Aplicación — notification", "application/notification"),
    ("Aplicación — audit", "application/audit"),
    ("Aplicación — reference", "application/reference"),
    ("Aplicación — shared", "application/shared"),
    ("Aplicación — webhook", "application/webhook"),
    ("Infraestructura — security", "infrastructure/security"),
    ("Infraestructura — kira", "infrastructure/kira"),
    ("Infraestructura — persistence", "infrastructure/persistence"),
    ("Infraestructura — audit", "infrastructure/audit"),
    ("Infraestructura — observability", "infrastructure/observability"),
    ("Infraestructura — bootstrap", "infrastructure/bootstrap"),
    ("Infraestructura — config", "infrastructure/config"),
    ("Infraestructura — reconciliation", "infrastructure/reconciliation"),
    ("Interfaces — rest", "interfaces/rest"),
    ("Interfaces — webhook", "interfaces/webhook"),
]

TYPE_WORD = {"class": "clase", "record": "record", "enum": "enum", "interface": "interfaz"}


def clean_javadoc(block):
    """/** ... */ → texto plano conservando saltos de línea."""
    if not block:
        return ""
    body = block.strip()
    body = re.sub(r"^/\*\*", "", body)
    body = re.sub(r"\*/$", "", body)
    lines = []
    for raw in body.split("\n"):
        line = raw.strip()
        line = re.sub(r"^\*\s?", "", line)
        line = re.sub(r"^@(param|return|throws|see)\b.*", "", line)
        line = re.sub(r"\{@(?:link|code|literal)\s+([^}]*)\}", r"\1", line)
        lines.append(line.rstrip())
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines).strip()


def one_line(text):
    return " ".join(text.split()) if text else ""


def strip_comments(source):
    """Quita comentarios para localizar miembros sin falsos positivos, conservando offsets."""
    out = list(source)
    i, n = 0, len(source)
    in_str = in_char = False
    while i < n:
        c = source[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
        elif c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        elif c == "/" and i + 1 < n and source[i + 1] == "/":
            while i < n and source[i] != "\n":
                out[i] = " "
                i += 1
            continue
        elif c == "/" and i + 1 < n and source[i + 1] == "*":
            j = source.find("*/", i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                out[k] = "\n" if source[k] == "\n" else " "
            i = j
            continue
        i += 1
    return "".join(out)


TYPE_RE = re.compile(
    r"(?P<mods>(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*"
    r"(?P<kind>class|record|enum|interface)\s+(?P<name>[A-Z][A-Za-z0-9_]*)",
)

MEMBER_RE = re.compile(
    r"^[ \t]*(?P<sig>(?:@[A-Za-z][\w.]*(?:\([^)]*\))?\s+)*"
    r"(?:public|protected)\s+[^;{=]*?)(?P<end>[;{])",
    re.MULTILINE | re.DOTALL,
)


def javadoc_before(source, index):
    head = source[:index]
    m = re.search(r"(/\*\*(?:(?!\*/).)*\*/)\s*(?:@[A-Za-z][\w.]*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*)*$",
                  head, re.DOTALL)
    return clean_javadoc(m.group(1)) if m else ""


def annotations_before(source, index):
    head = source[:index]
    m = re.search(r"((?:@[A-Za-z][\w.]*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*)+)$", head, re.DOTALL)
    if not m:
        return []
    found = re.findall(r"@[A-Za-z][\w.]*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?", m.group(1))
    return [one_line(a) for a in found]


def matching_brace(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def record_components(header):
    m = re.search(r"\(", header)
    if not m:
        return []
    depth = 0
    for i in range(m.start(), len(header)):
        if header[i] == "(":
            depth += 1
        elif header[i] == ")":
            depth -= 1
            if depth == 0:
                inner = header[m.start() + 1:i]
                break
    else:
        return []
    parts, depth, current = [], 0, ""
    for ch in inner:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    parts.append(current)
    out = []
    for part in parts:
        text = one_line(re.sub(r"@[A-Za-z][\w.]*(\([^()]*\))?", "", part))
        if text:
            out.append(text)
    return out


def parse_type(source, clean, start, name, kind):
    """Devuelve el bloque markdown de un tipo (y procesa sus tipos anidados)."""
    header_end = clean.find("{", start)
    header = clean[start:header_end]
    body_end = matching_brace(clean, header_end)
    body = clean[header_end + 1:body_end]
    offset = header_end + 1

    doc = javadoc_before(source, start)
    annos = [a for a in annotations_before(source, start) if not a.startswith("@Override")]

    title = f"#### `{name}` · {TYPE_WORD[kind]}"
    if annos:
        title += " · " + " ".join(f"`{a}`" for a in annos)
    out = [title, ""]
    if doc:
        out += [doc, ""]

    if kind == "enum":
        values = []
        first = re.split(r";", body, 1)[0]
        for token in re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)", first, re.MULTILINE):
            values.append(token)
        if values:
            out += ["Valores: " + ", ".join(f"`{v}`" for v in values) + ".", ""]

    if kind == "record":
        comps = record_components(header)
        if comps:
            out += ["| Componente |", "|---|"] + [f"| `{c}` |" for c in comps] + [""]

    fields, methods = [], []
    for m in MEMBER_RE.finditer(body):
        sig = one_line(m.group("sig"))
        sig = re.sub(r"@[A-Za-z][\w.]*(\([^()]*\))?\s*", "", sig).strip()
        if not sig:
            continue
        member_doc = one_line(javadoc_before(source, offset + m.start()))
        is_method = "(" in sig.split("=")[0]
        if m.group("end") == ";" and not is_method:
            value = sig
            if "static final" in sig or member_doc:
                fields.append((value, member_doc))
        elif is_method and not re.match(r"^(public|protected)\s+(static\s+)?(class|record|enum|interface)\b", sig):
            sig = re.sub(r"\s+", " ", sig).strip()
            methods.append((sig, member_doc))

    if fields:
        out += ["| Campo | Descripción |", "|---|---|"]
        out += [f"| `{f}` | {d} |" for f, d in fields]
        out += [""]
    if methods:
        out += ["| Método | Descripción |", "|---|---|"]
        seen = set()
        for sig, d in methods:
            if sig in seen:
                continue
            seen.add(sig)
            out += [f"| `{sig}` | {d} |"]
        out += [""]

    nested = []
    for m in TYPE_RE.finditer(body):
        abs_start = offset + m.start()
        if clean[abs_start - 1:abs_start].isalnum():
            continue
        nested.append(parse_type(source, clean, abs_start, m.group("name"), m.group("kind")))
    return "\n".join(out) + ("\n".join(nested) if nested else "")


def render_file(path):
    source = path.read_text(encoding="utf-8")
    clean = strip_comments(source)
    lines = len(source.rstrip("\n").split("\n"))
    rel = str(path.relative_to(SRC))
    blocks = [f"<sub>`{rel}` · {lines} líneas</sub>", ""]
    m = TYPE_RE.search(clean)
    if not m:
        return ""
    blocks.append(parse_type(source, clean, m.start(), m.group("name"), m.group("kind")))
    return "\n".join(blocks)


def annex_a():
    out = ["## Anexo A. Referencia clase por clase", "",
           "Generado automáticamente desde el código fuente el 16-sep-2026: **todos** los tipos de "
           "`src/main/java` (clases, records, enums e interfaces, incluidos los anidados), con su javadoc, "
           "anotaciones, campos constantes o documentados y todos los métodos no privados. Los métodos "
           "privados se omiten; su lógica se explica en las secciones 8 a 12.", ""]
    n = 0
    for title, folder in SECTIONS:
        base = SRC if folder == "." else SRC / folder
        files = sorted(p for p in base.glob("*.java") if p.name != "package-info.java")
        if folder == ".":
            files = [p for p in files if p.parent == SRC]
        if not files:
            continue
        n += 1
        out += [f"### A.{n} {title}", ""]
        for path in files:
            block = render_file(path)
            if block:
                out += [block]
    return "\n".join(out).rstrip() + "\n"


def annex_b():
    files = sorted(TEST.rglob("*.java"))
    total = 0
    blocks = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        names = re.findall(r"@Test\s*(?:@[A-Za-z][\w.]*(?:\([^()]*\))?\s*)*(?:public\s+|private\s+)?"
                           r"(?:static\s+)?void\s+([A-Za-z0-9_]+)\s*\(", text)
        if not names:
            continue
        total += len(names)
        rel = str(path.relative_to(TEST))
        blocks += [f"### `{rel}` — {len(names)}", ""]
        for name in names:
            words = re.sub(r"(?<!^)(?=[A-Z])|(?<=[a-zA-Z])(?=\d)", " ", name).lower()
            blocks += [f"- `{name}` — {words}"]
        blocks += [""]
    head = ["## Anexo B. Catálogo de pruebas", "",
            f"**{len(files)} clases de prueba, {total} métodos `@Test`.** Los nombres describen la regla "
            "de negocio que protegen.", ""]
    return "\n".join(head + blocks).rstrip() + "\n"


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "a"
    print(annex_a() if which == "a" else annex_b())
