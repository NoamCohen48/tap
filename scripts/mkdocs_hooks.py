"""MkDocs hooks for the Tap site (mkdocs.yml `hooks:`).

Highlighting for ```console blocks. Pygments' shell-session lexer colours a command line and
prints everything else as plain output, so a tap-agent session reads as grey text. The lexer
below keeps that split and also marks what a reader scans for in the output: refs (@e12),
node classes, quoted text, key= attributes, the +/- of a --settle diff, window headers and
error codes. The Markdown keeps plain ```console fences, so the .md copies of the pages
(scripts/llms_txt.py) and GitHub render as before.

Redirects: on_post_build writes a forwarding page at each URL a page moved away from
(REDIRECTS), so old links keep working.
"""

import posixpath
from pathlib import Path

from pygments.lexer import RegexLexer, bygroups, default
from pygments.lexers import _lexer_cache, _load_lexers
from pygments.lexers.shell import BashSessionLexer
from pygments.token import Comment, Generic, Name, String, Text, Whitespace

_STRING = r'"(?:\\.|[^"\\\n])*"' + r"|'[^'\n]*'"


class TapConsoleLexer(RegexLexer):
    """A shell session whose output may be tap-agent snapshots, diffs and errors."""

    name = BashSessionLexer.name
    aliases = BashSessionLexer.aliases
    filenames = BashSessionLexer.filenames
    mimetypes = BashSessionLexer.mimetypes

    tokens = {
        "root": [
            (r"^\$ ", Generic.Prompt, "program"),
            (r"^(\+ )(?=@e\d)", Generic.Inserted, "node"),
            (r"^(- )(?=@e\d)", Generic.Deleted, "node"),
            (r"^(?=@e\d)", Text, "node"),
            (r"^# .*\n", Generic.Heading),
            (r"^(error:)( [A-Z_]+)?(.*\n)", bygroups(Generic.Error, Generic.Error, Generic.Output)),
            (r"^….*\n", Comment),
            (r".*\n", Generic.Output),
        ],
        # A command: the program, its subcommand, then arguments.
        "program": [
            (r"\S+", Name.Builtin, ("#pop", "subcommand")),
            default("#pop"),
        ],
        "subcommand": [
            (r"[ \t]+", Whitespace),
            (r"[a-z][\w-]*(?=[ \t]|\n)", Name.Function, ("#pop", "arguments")),
            default(("#pop", "arguments")),
        ],
        "arguments": [
            (r"\n", Whitespace, "#pop"),
            (r"[ \t]+", Whitespace),
            (_STRING, String),
            (r"--?[A-Za-z][\w-]*", Name.Constant),
            (r"@e\d+", Name.Label),
            (r"[\w~]+=", Name.Attribute),
            (r"\S+", Text),
        ],
        # One node of a snapshot: @ref  [Class]  "text"  key=value  flags.
        "node": [
            (r"\n", Generic.Output, "#pop"),
            (r"@e\d+", Name.Label),
            (r"\[[\w.$]+\]", Name.Class),
            (_STRING, String),
            (r"[a-z_]+=", Name.Attribute),
            (r"\([^)\n]*\)", Comment),
            (r'[^\s@\["(]+|[ \t]+|.', Generic.Output),
        ],
    }


# pymdownx.highlight looks lexers up by alias through get_lexer_by_name, which reads this
# cache before importing the built-in class, so ```console (and shell-session) now use ours.
# The shell module is loaded first: loading it later (for a ```bash block) would refill the
# cache with every lexer it defines, the built-in session lexer included.
_load_lexers(BashSessionLexer.__module__)
_lexer_cache[BashSessionLexer.name] = TapConsoleLexer


# Pages that moved, old URL → new URL (both relative to the site root). Each old URL gets a
# small page that forwards to the new one, keeping the #fragment.
REDIRECTS = {
    "guide/actions-and-waits/": "guide/elements/",
    "guide/configuration/": "sdk/",
    "guide/agents/": "agent/",
    "guide/studio/": "studio/",
    "reference/agent/": "agent/commands/",
}

_REDIRECT_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Moved</title>
<link rel="canonical" href="{target}">
<meta http-equiv="refresh" content="0; url={target}">
<script>location.replace("{target}" + location.hash)</script>
</head>
<body><p>This page moved to <a href="{target}">{target}</a>.</p></body>
</html>
"""


def on_post_build(config):
    site = Path(config["site_dir"])
    for old, new in REDIRECTS.items():
        page = site / old / "index.html"
        if page.exists():
            raise RuntimeError(f"redirect {old} would replace a real page")
        target = posixpath.relpath(new, old) + "/"
        page.parent.mkdir(parents=True, exist_ok=True)
        page.write_text(_REDIRECT_PAGE.format(target=target), encoding="utf-8")
