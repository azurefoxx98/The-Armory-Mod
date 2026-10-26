# Generated from The Armory's own checks, with their comments left out. Edit the original
# and generate the copies again, never this file.
# Edits single JSON values in place, keeping every other byte of the file.

import json
WS = ' \t\r\n'

class JsonSpanError(ValueError):
    pass

def parse(text):
    spans = {}
    pos = _skip(text, 0)
    value, pos = _value(text, pos, (), spans)
    pos = _skip(text, pos)
    if pos != len(text):
        raise JsonSpanError(f'extra text at offset {pos}')
    return (value, spans)

def _skip(t, i):
    n = len(t)
    while i < n:
        c = t[i]
        if c in WS or c == '\ufeff':
            i += 1
        elif t.startswith('//', i):
            j = t.find('\n', i)
            i = n if j < 0 else j + 1
        elif t.startswith('/*', i):
            j = t.find('*/', i + 2)
            if j < 0:
                raise JsonSpanError(f'unclosed comment at offset {i}')
            i = j + 2
        else:
            break
    return i

def _value(t, i, ptr, spans):
    if i >= len(t):
        raise JsonSpanError('unexpected end')
    c = t[i]
    start = i
    if c == '{':
        obj, members = ({}, [])
        i = _skip(t, i + 1)
        if t[i:i + 1] == '}':
            i += 1
        else:
            while True:
                if t[i:i + 1] != '"':
                    raise JsonSpanError(f'expected a key at offset {i}')
                key_start = i
                key, i = _string(t, i)
                i = _skip(t, i)
                if t[i:i + 1] != ':':
                    raise JsonSpanError(f"expected ':' at offset {i}")
                i = _skip(t, i + 1)
                members.append((key, key_start))
                obj[key], i = _value(t, i, ptr + (key,), spans)
                i = _skip(t, i)
                if t[i:i + 1] == ',':
                    i = _skip(t, i + 1)
                    continue
                if t[i:i + 1] == '}':
                    i += 1
                    break
                raise JsonSpanError(f"expected ',' or '}}' at offset {i}")
        spans[ptr] = (start, i)
        spans[ptr, '{members}'] = members
        return (obj, i)
    if c == '[':
        arr = []
        i = _skip(t, i + 1)
        if t[i:i + 1] == ']':
            i += 1
        else:
            while True:
                v, i = _value(t, i, ptr + (len(arr),), spans)
                arr.append(v)
                i = _skip(t, i)
                if t[i:i + 1] == ',':
                    i = _skip(t, i + 1)
                    continue
                if t[i:i + 1] == ']':
                    i += 1
                    break
                raise JsonSpanError(f"expected ',' or ']' at offset {i}")
        spans[ptr] = (start, i)
        return (arr, i)
    if c == '"':
        s, i = _string(t, i)
        spans[ptr] = (start, i)
        return (s, i)
    j = i
    while j < len(t) and t[j] not in WS + ',}]/':
        j += 1
    word = t[i:j]
    try:
        v = json.loads(word)
    except ValueError:
        raise JsonSpanError(f'bad literal {word!r} at offset {i}')
    spans[ptr] = (start, j)
    return (v, j)

def _string(t, i):
    j = i + 1
    n = len(t)
    while j < n:
        c = t[j]
        if c == '\\':
            j += 2
            continue
        if c == '"':
            return (json.loads(t[i:j + 1]), j + 1)
        j += 1
    raise JsonSpanError(f'unclosed string at offset {i}')

def splice(text, edits):
    out, last = ([], 0)
    for s, e, new in sorted(edits):
        if s < last:
            raise JsonSpanError('overlapping edits')
        out.append(text[last:s])
        out.append(new)
        last = e
    out.append(text[last:])
    return ''.join(out)

def set_string_member(text, spans, obj_ptr, key, value):
    enc = json.dumps(value, ensure_ascii=False)
    if obj_ptr + (key,) in spans:
        s, e = spans[obj_ptr + (key,)]
        return (s, e, enc)
    members = spans[obj_ptr, '{members}']
    o_start, _ = spans[obj_ptr]
    newline = '\r\n' if '\r\n' in text else '\n'
    if members:
        first = members[0][1]
        line_start = text.rfind('\n', 0, first) + 1
        indent = text[line_start:first]
        if indent.strip() == '':
            return (first, first, f'"{key}": {enc},{newline}{indent}')
        return (first, first, f'"{key}": {enc}, ')
    return (o_start + 1, o_start + 1, f' "{key}": {enc} ')
