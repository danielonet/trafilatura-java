# Contributing to trafilatura-java

Thanks for helping. Most useful, in order:

1. **Pages that extract badly.** Open an issue with the URL, the command or code you ran,
   and what you expected. If Python trafilatura 2.3.1 gets it right and this port does
   not, say so: that is a porting bug.
2. **Ported tests.** The real-world tests (`tests/realworld_tests.py`) and the evaluation
   scripts are not ported yet.
3. **Fixes to the stand-ins.** courlan, htmldate and jusText are simplified; see
   "Differences from the Python version" in the README.

Questions and ideas go to [Discussions](https://github.com/danielonet/trafilatura-java/discussions).

## Building and testing

JDK 21 is all you need. Either build works; CI runs both.

```bash
./mvnw test                          # or: ./gradlew test
./mvnw test -Dtest=UnitTests         # one suite
./mvnw test -Dsurefire.excludedGroups=   # also the known-deviation and network tests
./gradlew test -PallTests            # the same with Gradle
```

Tests tagged `known-deviation` fail on purpose: their expectations need something this
port does not have (libxml2 parsing, full htmldate, jusText). Leave the tag and the
comment explaining it when you add one.

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
10. **Mark ported files.** Start each file with `// Java port of <module>.py`; the NOTICE
    file relies on it to identify derived code.

## Pull requests

- One topic per pull request, with a test when the change affects behaviour.
- Say which upstream function or test the change corresponds to.
- New files derived from upstream code keep the Apache-2.0 license; by contributing you
  agree your changes are licensed under it too.
