# trafilatura-java

A Java port of [trafilatura](https://github.com/adbar/trafilatura) 2.3.1, the Python
library and command-line tool by Adrien Barbaresi for extracting the main text,
comments and metadata from web pages.

Given an HTML page, it removes the boilerplate (navigation, ads, footers, share
buttons) and returns the article text as plain text, Markdown, XML, XML-TEI, JSON, CSV
or simplified HTML, optionally with metadata: title, author, date, site name,
categories, tags and license. It also covers the surrounding tooling: downloading
pages, finding feeds and sitemaps, and a small focused crawler.

The port started from a machine translation (Pyrite) and was then rewritten by hand to
compile and to behave like the Python original. Function and field names deliberately
stay the same as in Python (`bare_extraction`, `extract_metadata`, `max_tree_size`...),
so the Python documentation and source can be read side by side with this code.

## Requirements and setup

- JDK 21 or newer
- Maven 3.9+

```bash
mvn compile          # build the library
mvn test             # run the ported test suites
mvn package          # build target/trafilatura-java-0.1.0-SNAPSHOT.jar
```

Dependencies, all fetched by Maven: jsoup (HTML parsing), Jaxen (XPath), Jackson
(JSON), SLF4J (logging), juniversalchardet (charset detection), BouncyCastle (BLAKE2b
hashes), JUnit 5 (tests).

## Usage

### As a library

```java
import trafilatura.trafilatura.Core;
import trafilatura.trafilatura.Downloads;
import trafilatura.trafilatura.Metadata;
import trafilatura.trafilatura.Settings.Document;

String html = Downloads.fetch_url("https://example.org/article");

// plain text, default settings
String text = Core.extract(html);

// Python keyword arguments are passed through a small builder
String xml = Core.extract(html, Core.kw()
        .output_format("xml")
        .with_metadata(true)
        .include_links(true));

// structured result instead of a string
Document doc = Core.bare_extraction(html, Core.kw().favor_precision(true));
System.out.println(doc.title + " / " + doc.date + "\n" + doc.text);

// metadata only
Document meta = Metadata.extract_metadata(html);
```

`TrafilaturaInit` re-exports the main entry points, like the Python package's
`__init__.py`.

### Command line

```bash
CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" trafilatura.trafilatura.Cli --version
java -cp "$CP" trafilatura.trafilatura.Cli -u https://example.org/article --markdown
cat page.html | java -cp "$CP" trafilatura.trafilatura.Cli --json --with-metadata
```

The options are the same as the Python CLI; run with `--help` for the list.

## Project layout

```
trafilatura/src/main/java/trafilatura/
  trafilatura/   the port itself: one class per Python module
                 (core.py -> Core, main_extractor.py -> MainExtractor, ...)
  lxml/          a small lxml-compatible element tree (text/tail model, live iteration,
                 XPath 1.0 via Jaxen, tostring, HTML parsing via jsoup)
  compat/        Python semantics with no direct Java equivalent: truthiness, str methods,
                 re module, json, configparser, argparse, difflib, robotparser, tuples
  courlan/       stand-in for the Python courlan package (URL handling)
  htmldate/      minimal stand-in for htmldate (publication dates)
  justext/       stub for jusText (fallback extractor)
  docs/          Sphinx configuration data (Conf)
trafilatura/src/main/resources/trafilatura/   settings.cfg, TEI DTD
trafilatura/src/test/java/trafilatura/tests/  ported pytest suites (JUnit 5)
trafilatura/src/test/resources/               test pages, cached real-world pages
```

The root-level `tests/` and `docs/` folders are older, untranslated converter output.
They are not part of the build and can be deleted.

## Configuration

Defaults come from `settings.cfg`, the same file as the Python version. Use your own
file with `Settings.use_config("my.cfg")`, then `Core.kw().config(...)`, or with
`--config-file` on the command line. Values not set in your file keep their defaults.

## Differences from the Python version

- **Dependencies replaced by stand-ins.** courlan is ported in simplified form: no
  tracker removal, no punycode, no public-suffix list, and no URL language heuristics.
  htmldate only reads meta tags, JSON-LD, `<time>` elements and the URL. jusText is a
  stub, so its fallback never fires. Expect differences in dates, domains and some
  borderline extractions.
- **HTML parsing.** HTML is parsed with jsoup (HTML5 rules) instead of libxml2, so tree
  shapes can differ on malformed markup.
- **Optional Python extras are absent.** Language detection (py3langid), pycurl,
  brotli, zstd and SOCKS proxies are not available, as if they weren't installed in
  Python.
- **XPath attribute unions.** In `@id|@class`, Jaxen orders attributes by name, while
  lxml uses their order in the source.

## General rules for working on the port

1. **Follow the Python source.** Keep Python names, comments and control flow, so a
   diff against the upstream module stays readable. When upstream changes, port the
   change in the same place.
2. **Keyword arguments become overloads or a `Kwargs` builder.** Functions with a few
   defaults get telescoping overloads. Keyword-heavy ones (`extract`, `Extractor`) get
   a `kw()` builder whose setters use the Python parameter names.
3. **Use the compat helpers for Python semantics.** For example: `Py.truthy`,
   `Py.strip` (Python whitespace includes U+00A0), `Py.isdigit` (false for `""`),
   `Py.str(null)` (prints `"None"`), and `Re.match` (anchored like `re.match`).
4. **Regexes need Python semantics.** Add `UNICODE_CHARACTER_CLASS` where the pattern
   uses `\w`, `\b` or `\d`; use `$1` in replacements; use `{0,n}` instead of `{,n}`.
5. **Watch for null keys.** `List.of`/`Set.of`/`Map.of` throw on a null lookup, while
   Python just returns False or None. Guard lookups of attribute values, which may be
   absent.
6. **Element iteration is live, as in lxml.** `iter()` computes the next node lazily.
   Use `.list()` where the Python code wraps the call in `list(...)`.
7. **Module-level names are public.** Python has no access control, and the tests call
   `_private` helpers.
8. **Test seams replace monkeypatching.** Tests swap behaviour through public static
   hooks: `Downloads.SEND_REQUEST`, `Downloads.IS_LIVE_PAGE`, the `SLEEP` hooks in
   Feeds, Sitemaps and Spider, and `Settings.USE_CONFIG_HOOK`. `Conftest` installs them
   for every test, as pytest's autouse fixtures did.
9. **Test ports keep the same test names and assertions.** `assert a == b` becomes
   `assertEquals(b, a)`, `parametrize` becomes `@ParameterizedTest`, and strict
   `xfail` becomes `assertThrows`.

## Status

- The library and CLI are fully ported and compile.
- Test suites ported so far, all passing: deduplication, filters, repeated output
  (3,634 cases), XML/TEI and baseline. The remaining pytest modules (metadata, JSON
  metadata, unit, real-world, downloads, feeds, sitemaps, spider, CLI, evaluation
  scripts) are still to be ported.

## License

This repository's LICENSE file is MIT. The upstream projects the port is derived from
are trafilatura, courlan and htmldate (Apache-2.0, Adrien Barbaresi) and jusText
(BSD-2-Clause). Their copyright and license notices must be kept in any distribution.
