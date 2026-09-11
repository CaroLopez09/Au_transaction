#!/usr/bin/env python3
"""Convierte la coleccion Bruno AuTransactional a Postman Collection v2.1."""
import json, os, re, sys, uuid, hashlib
from urllib.parse import urlsplit, parse_qsl

SRC = "/mnt/user-data/uploads/bruno/AuTransactional"
OUT = "/home/claude/out"

# ---------------------------------------------------------------- parser .bru
def split_blocks(text):
    """Devuelve [(nombre_bloque, contenido_interior), ...] respetando llaves anidadas."""
    blocks, i, n = [], 0, len(text)
    while i < n:
        m = re.compile(r'^([A-Za-z0-9:_\-]+)\s*\{', re.M).search(text, i)
        if not m:
            break
        name = m.group(1)
        depth, j = 1, m.end()
        while j < n and depth:
            c = text[j]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            j += 1
        blocks.append((name, text[m.end():j - 1]))
        i = j
    return blocks


def dedent(body):
    lines = body.split('\n')
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    ind = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    k = min(ind) if ind else 0
    return '\n'.join(l[k:] if len(l) >= k else l for l in lines)


def kv(body):
    out = []
    for line in dedent(body).split('\n'):
        if not line.strip() or line.strip().startswith('#'):
            continue
        if ':' not in line:
            continue
        k, v = line.split(':', 1)
        disabled = k.strip().startswith('~')
        out.append((k.strip().lstrip('~'), v.strip(), disabled))
    return out


def parse_bru(path):
    with open(path, encoding='utf-8') as f:
        return split_blocks(f.read())


# --------------------------------------------------- traduccion de scripts JS
def replace_call(src, fn, builder):
    """Sustituye llamadas fn(...) equilibrando parentesis. builder(args_str) -> str."""
    out, i = [], 0
    pat = re.compile(re.escape(fn) + r'\s*\(')
    while True:
        m = pat.search(src, i)
        if not m:
            out.append(src[i:])
            break
        out.append(src[i:m.start()])
        depth, j = 1, m.end()
        while j < len(src) and depth:
            if src[j] == '(':
                depth += 1
            elif src[j] == ')':
                depth -= 1
            j += 1
        out.append(builder(src[m.end():j - 1]))
        i = j
    return ''.join(out)


def split_args(a):
    """Divide 'x, y' de primer nivel."""
    parts, depth, cur, q = [], 0, '', None
    for ch in a:
        if q:
            cur += ch
            if ch == q and not cur.endswith('\\' + q):
                q = None
            continue
        if ch in '"\'`':
            q = ch; cur += ch; continue
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip()); cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def js(src, original_body_literal=None):
    if not src:
        return src
    s = src
    # fs / bru.cwd(): no existe en Postman -> se deja solo el aviso
    s = re.sub(
        r'\n\s*// Escribe la grabacion.*?\n\s*\}\n(\s*)\}',
        '\n    console.log("Postman: escribe muestras/recording.txt a mano con " + b.challengeNumber);\n\\1}',
        s, flags=re.S)
    s = replace_call(s, 'bru.setVar',
                     lambda a: 'pm.collectionVariables.set(%s)' % a)
    s = replace_call(s, 'bru.getVar',
                     lambda a: 'pm.collectionVariables.get(%s)' % a)
    s = replace_call(s, 'bru.getEnvVar',
                     lambda a: 'pm.environment.get(%s)' % a)
    s = replace_call(s, 'req.setHeader',
                     lambda a: 'pm.request.headers.upsert({ key: %s, value: %s })'
                               % tuple(split_args(a)[:2]))
    s = replace_call(s, 'req.setBody',
                     lambda a: 'pm.collectionVariables.set("rawBody", '
                               'typeof (%s) === "string" ? (%s) : JSON.stringify(%s))' % (a, a, a))
    s = s.replace('req.getBody()', 'JSON.parse(__originalBody)')
    s = s.replace('res.getStatus()', 'pm.response.code')
    s = s.replace('res.getBody()', 'pm.response.json()')
    s = re.sub(r'(?<![\w.])test\s*\(', 'pm.test(', s)
    s = re.sub(r'(?<![\w.])expect\s*\(', 'pm.expect(', s)
    if '__originalBody' in s and original_body_literal is not None:
        s = ('const __originalBody = pm.variables.replaceIn(%s);\n'
             % json.dumps(original_body_literal)) + s
    return s


def lines(s):
    return s.split('\n') if s else []


# ------------------------------------------------------------------ conversion
METHODS = ('get', 'post', 'put', 'patch', 'delete', 'head', 'options')
setvars = set()


def build_url(raw):
    sp = urlsplit(raw.replace('{{baseUrl}}', 'http://__BASEURL__'))
    host_is_var = '__BASEURL__' in sp.netloc
    path = [p for p in sp.path.split('/') if p != '']
    u = {'raw': raw}
    if host_is_var:
        u['host'] = ['{{baseUrl}}']
    else:
        u['protocol'] = sp.scheme
        u['host'] = sp.netloc.split('.')
    if path:
        u['path'] = path
    if sp.query:
        u['query'] = [{'key': k, 'value': v} for k, v in parse_qsl(sp.query, keep_blank_values=True)]
    if host_is_var:
        u['raw'] = raw
    return u


def convert_request(path, folder_name):
    blocks = dict()
    order = []
    for name, body in parse_bru(path):
        blocks.setdefault(name, body)
        order.append(name)

    meta = {k: v for k, v, _ in kv(blocks.get('meta', ''))}
    name = meta.get('name', os.path.splitext(os.path.basename(path))[0])
    seq = int(meta.get('seq', '999'))

    method = next((m for m in METHODS if m in blocks), 'get')
    mblock = {k: v for k, v, _ in kv(blocks[method])}
    url_raw = mblock.get('url', '')
    body_kind = mblock.get('body', 'none')
    auth_kind = mblock.get('auth', 'none')

    req = {'method': method.upper(), 'header': [], 'url': build_url(url_raw)}

    # auth
    if auth_kind == 'bearer' and 'auth:bearer' in blocks:
        tok = {k: v for k, v, _ in kv(blocks['auth:bearer'])}.get('token', '')
        req['auth'] = {'type': 'bearer',
                       'bearer': [{'key': 'token', 'value': tok, 'type': 'string'}]}
    elif auth_kind == 'none':
        req['auth'] = {'type': 'noauth'}

    # headers explicitos
    for k, v, dis in kv(blocks.get('headers', '')):
        h = {'key': k, 'value': v}
        if dis:
            h['disabled'] = True
        req['header'].append(h)

    pre_raw = blocks.get('script:pre-request')
    original_body_literal = None

    # body
    if body_kind == 'json':
        raw = dedent(blocks.get('body:json', ''))
        original_body_literal = raw
        if pre_raw and 'req.setBody' in pre_raw:
            raw = '{{rawBody}}'
        req['body'] = {'mode': 'raw', 'raw': raw,
                       'options': {'raw': {'language': 'json'}}}
        if not any(h['key'].lower() == 'content-type' for h in req['header']):
            req['header'].append({'key': 'Content-Type', 'value': 'application/json'})
    elif body_kind == 'text':
        raw = dedent(blocks.get('body:text', ''))
        original_body_literal = raw
        if pre_raw and 'req.setBody' in pre_raw:
            raw = '{{rawBody}}'
        req['body'] = {'mode': 'raw', 'raw': raw,
                       'options': {'raw': {'language': 'text'}}}
    elif body_kind == 'multipartForm':
        fd = []
        for k, v, dis in kv(blocks.get('body:multipart-form', '')):
            m = re.match(r'@file\((.*)\)', v)
            if m:
                fd.append({'key': k, 'type': 'file', 'src': m.group(1)})
            else:
                fd.append({'key': k, 'type': 'text', 'value': v})
        req['body'] = {'mode': 'formdata', 'formdata': fd}

    # docs -> description
    doc = dedent(blocks['docs']) if 'docs' in blocks else ''
    if body_kind == 'multipartForm':
        nota = ('> **Postman:** vuelve a seleccionar los ficheros en la pestana Body > form-data '
                '(estan en `docs/bruno/AuTransactional/muestras/`). Postman no acepta rutas de '
                'fichero importadas desde un JSON.')
        doc = (doc + '\n\n' + nota) if doc else nota
    if doc:
        req['description'] = doc

    item = {'name': name, 'request': req, 'response': []}

    # scripts
    events = []
    if pre_raw:
        events.append({'listen': 'prerequest',
                       'script': {'type': 'text/javascript',
                                  'exec': lines(js(dedent(pre_raw), original_body_literal))}})
    post_parts = []
    if 'script:post-response' in blocks:
        post_parts.append(js(dedent(blocks['script:post-response'])))
        for m in re.finditer(r'bru\.setVar\(\s*"([^"]+)"', blocks['script:post-response']):
            setvars.add(m.group(1))
    if 'tests' in blocks:
        post_parts.append(js(dedent(blocks['tests'])))
    if post_parts:
        events.append({'listen': 'test',
                       'script': {'type': 'text/javascript',
                                  'exec': lines('\n\n'.join(post_parts))}})
    if events:
        item['event'] = events

    return seq, item


def main():
    with open(os.path.join(SRC, 'bruno.json'), encoding='utf-8') as f:
        bruno = json.load(f)
    coll_blocks = dict(parse_bru(os.path.join(SRC, 'collection.bru')))

    folders = []
    for entry in sorted(os.listdir(SRC)):
        d = os.path.join(SRC, entry)
        if not os.path.isdir(d) or entry in ('environments', 'muestras'):
            continue
        fmeta = {}
        fdocs = None
        fp = os.path.join(d, 'folder.bru')
        if os.path.exists(fp):
            fb = dict(parse_bru(fp))
            fmeta = {k: v for k, v, _ in kv(fb.get('meta', ''))}
            if 'docs' in fb:
                fdocs = dedent(fb['docs'])
        items = []
        for fn in sorted(os.listdir(d)):
            if not fn.endswith('.bru') or fn == 'folder.bru':
                continue
            items.append(convert_request(os.path.join(d, fn), entry))
        items.sort(key=lambda t: t[0])
        folder = {'name': fmeta.get('name', entry),
                  'item': [it for _, it in items]}
        if fdocs:
            folder['description'] = fdocs
        folders.append((int(fmeta.get('seq', '999')), folder))
    folders.sort(key=lambda t: t[0])

    env_vars = [(k, v) for k, v, _ in kv(dict(parse_bru(os.path.join(SRC, 'environments/local.bru')))['vars'])]

    coll_vars = [{'key': n, 'value': '', 'type': 'string'} for n in sorted(setvars)]
    coll_vars.append({'key': 'rawBody', 'value': '', 'type': 'string'})

    desc = dedent(coll_blocks.get('docs', ''))
    desc += ("\n\n---\n\n_Convertida desde la coleccion Bruno `docs/bruno/AuTransactional`._\n"
             "- Los `bru.setVar(...)` se guardan como **variables de coleccion** (`pm.collectionVariables`).\n"
             "- Los valores del entorno (`baseUrl`, `empresa`, `password`, `webhookSecret`...) viven en el "
             "environment **AuTransactional local**: seleccionalo antes de ejecutar.\n"
             "- En los webhooks el cuerpo lo construye y firma el pre-request script y se envia via `{{rawBody}}`.\n"
             "- Las peticiones multipart (`09 Verificacion de identidad`) requieren volver a seleccionar los "
             "archivos de `docs/bruno/AuTransactional/muestras/` en Postman: por seguridad Postman no acepta "
             "rutas de fichero importadas.")

    collection = {
        'info': {
            '_postman_id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'autransactional-bff')),
            'name': bruno.get('name', 'AuTransactional BFF'),
            'description': desc,
            'schema': 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
        },
        'auth': {'type': 'noauth'},
        'item': [f for _, f in folders],
        'variable': coll_vars,
    }

    environment = {
        'id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'autransactional-local-env')),
        'name': 'AuTransactional local',
        'values': [{'key': k, 'value': v, 'type': 'secret' if k in ('password', 'webhookSecret') else 'default',
                    'enabled': True} for k, v in env_vars],
        '_postman_variable_scope': 'environment',
        '_postman_exported_using': 'bru2postman',
    }

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'AuTransactional.postman_collection.json'), 'w', encoding='utf-8') as f:
        json.dump(collection, f, ensure_ascii=False, indent=2)
    with open(os.path.join(OUT, 'AuTransactional.postman_environment.json'), 'w', encoding='utf-8') as f:
        json.dump(environment, f, ensure_ascii=False, indent=2)

    total = sum(len(f['item']) for _, f in folders)
    print('carpetas: %d, peticiones: %d, variables de coleccion: %d'
          % (len(folders), total, len(coll_vars)))


if __name__ == '__main__':
    main()
