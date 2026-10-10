"""A small reader for Valve's text KeyValues format (libraryfolders.vdf, appmanifest_*.acf).

Returns nested dicts; later duplicate keys win. Handles quoted and bare tokens, \\-escapes inside
quotes, // comments and [$PLATFORM] conditionals (ignored). Not a writer: the launcher never edits
Steam's files.
"""


class VdfError(ValueError):
    pass


def _tokens(text):
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
        elif c == "/" and text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
        elif c in "{}":
            yield c, None
            i += 1
        elif c == "[":  # conditional like [$WIN32]: skip
            j = text.find("]", i)
            i = n if j < 0 else j + 1
        elif c == '"':
            out = []
            i += 1
            while True:
                if i >= n:
                    raise VdfError("unterminated string")
                c = text[i]
                if c == "\\" and i + 1 < n:
                    nxt = text[i + 1]
                    out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"'}.get(nxt, "\\" + nxt))
                    i += 2
                elif c == '"':
                    i += 1
                    break
                else:
                    out.append(c)
                    i += 1
            yield "s", "".join(out)
        else:
            j = i
            while j < n and not text[j].isspace() and text[j] not in '{}"':
                j += 1
            yield "s", text[i:j]
            i = j


def loads(text):
    stack = [{}]
    key = None
    for kind, val in _tokens(text):
        if kind == "s":
            if key is None:
                key = val
            else:
                stack[-1][key] = val
                key = None
        elif kind == "{":
            if key is None:
                raise VdfError("'{' without a key")
            d = {}
            stack[-1][key] = d
            stack.append(d)
            key = None
        else:  # "}"
            if len(stack) == 1 or key is not None:
                raise VdfError("unbalanced '}'")
            stack.pop()
    if len(stack) != 1 or key is not None:
        raise VdfError("unexpected end of file")
    return stack[0]


def load(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return loads(f.read())


def get_ci(d, key, default=None):
    """Case-insensitive lookup (Steam isn't consistent: LibraryFolders vs libraryfolders)."""
    if not isinstance(d, dict):
        return default
    if key in d:
        return d[key]
    low = key.lower()
    for k, v in d.items():
        if k.lower() == low:
            return v
    return default


def library_paths(data):
    """Library folder paths from a parsed libraryfolders.vdf, new and old formats."""
    root = get_ci(data, "libraryfolders") or {}
    out = []
    for k, v in root.items():
        if not k.isdigit():
            continue
        if isinstance(v, dict):
            p = get_ci(v, "path")
            if p:
                out.append(p)
        elif isinstance(v, str):  # old format: "1" "/path"
            out.append(v)
    return out
