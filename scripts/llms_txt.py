"""Publishes the documentation as Markdown inside the built site, for LLMs and agents.

    python scripts/llms_txt.py build/docs-md build/site

Run by scripts/build-docs.sh after the site and the Markdown bundle are built. It adds:

  <page>.md        every page's Markdown next to its HTML (guide/selectors.md beside
                   guide/selectors/), plus the generated Kotlin and Python references
  llms.txt         an index of the pages in navigation order (https://llmstxt.org)
  llms-full.txt    the guide and the hand-written references in one file
  tap-docs-md.zip  the whole Markdown bundle

Page titles and order come from the `nav` in mkdocs.yml.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]

# Generated API trees: linked from llms.txt, too large and repetitive for llms-full.txt.
NOT_IN_FULL = {"reference/kotlin.md"}
NOT_IN_FULL_DIRS = ("reference/python/",)


class _Loader(yaml.SafeLoader):
    """mkdocs.yml with its `!!python/name:` tags (Markdown extensions) read as nothing."""


_Loader.add_multi_constructor("tag:yaml.org,2002:python/", lambda loader, suffix, node: None)


def nav_pages(nav: list) -> list[tuple[str, str, str]]:
    """(tab, title, docs path) for every Markdown page in the nav, in order; nested groups
    (Guide > Finding elements) stay in their tab's section."""
    pages: list[tuple[str, str, str]] = []

    def walk(items: list, section: str) -> None:
        for item in items:
            if isinstance(item, str):
                pages.append((section, "", item))
                continue
            for title, value in item.items():
                if isinstance(value, list):
                    walk(value, section or title)  # the top-level tab names the section
                elif value.endswith(".md"):
                    pages.append((section or title, title, value))

    walk(nav, "")
    return pages


def first_heading(markdown: str) -> str:
    match = re.search(r"^# (.+)$", markdown, re.MULTILINE)
    return match.group(1).strip() if match else ""


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    md, site = Path(argv[1]), Path(argv[2])
    config = yaml.load((ROOT / "mkdocs.yml").read_text(encoding="utf-8"), Loader=_Loader)
    base = config["site_url"].rstrip("/") + "/"

    def source(path: str) -> Path:
        # The bundle keeps the home page as README.md so it renders on GitHub.
        return md / ("README.md" if path == "index.md" else path)

    for file in md.rglob("*.md"):
        relative = file.relative_to(md).as_posix()
        target = site / ("index.md" if relative == "README.md" else relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, target)

    pages = [(s, t, p) for s, t, p in nav_pages(config["nav"]) if source(p).is_file()]
    index = [
        f"# {config['site_name']}",
        "",
        f"> {' '.join(config['site_description'].split())}",
        "",
        "Every page below is Markdown; the HTML site is at the same path without `.md`. "
        f"[llms-full.txt]({base}llms-full.txt) holds the guide and the references in one file, and "
        f"[tap-docs-md.zip]({base}tap-docs-md.zip) the whole Markdown bundle, including the generated "
        "Kotlin and Python API references.",
    ]
    section = None
    for page_section, title, path in pages:
        heading = page_section if page_section != title else "Start"
        if heading != section:
            index += ["", f"## {heading}", ""]
            section = heading
        title = title or first_heading(source(path).read_text(encoding="utf-8")) or path
        index.append(f"- [{title}]({base}{path})")
    (site / "llms.txt").write_text("\n".join(index) + "\n", encoding="utf-8")

    full = [f"# {config['site_name']} documentation", "", f"Source: {base}", ""]
    for _, _, path in pages:
        if path in NOT_IN_FULL or path.startswith(NOT_IN_FULL_DIRS):
            continue
        full += ["", "---", "", f"<!-- {base}{path} -->", "", source(path).read_text(encoding="utf-8").strip(), ""]
    (site / "llms-full.txt").write_text("\n".join(full), encoding="utf-8")

    bundle = md.parent / "tap-docs-md.zip"
    if bundle.is_file():
        shutil.copyfile(bundle, site / bundle.name)
    print(f"llms.txt: {len(pages)} pages; llms-full.txt: {(site / 'llms-full.txt').stat().st_size // 1024} KiB")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
