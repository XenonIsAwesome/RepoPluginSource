# Repo Release Source

Install and update IDE plugins straight from the **Releases** of a GitHub or GitLab repository, through the normal
**Settings | Plugins** UI. The repository does not need to publish an `updatePlugins.xml`; this plugin generates one.

Primary target: CLion 2026.1+ (builds 261+). Depends only on `com.intellij.modules.platform`, so it also loads in other
JetBrains IDEs. There is intentionally **no until-build**: compatibility is decided by the Plugin Verifier (CI runs it on
every push and weekly against the newest CLion release/RC/EAP).

## Using it

1. **Settings | Tools | Repo Release Sources** → **+** and paste a repository URL
   (`https://github.com/owner/repo`, `https://gitlab.com/group/project`, or a self-hosted GitLab project URL).
   The dialog validates the URL while you type and shows the detected provider.
2. Private repository? Enter an access token (GitHub: read access to Contents; GitLab: `read_api`).
   It is stored in the IDE's password store, never in settings XML, URLs, logs or the served XML.
3. The row shows the result of an immediate check (for example `OK — My Plugin 1.2.0 (release v1.2.0)`) or a plain-language problem.
4. Open **Settings | Plugins | Marketplace**, search for the plugin and install it. Later releases show the normal update badge.

Release requirements: the latest release has a `.zip` asset containing the plugin distribution (`lib/*.jar` with
`META-INF/plugin.xml`). The catalog version is read from the plugin's own `plugin.xml`, not from the tag.
GitLab: attach the zip as a release link (a generic package registry URL works).

## How it works

* A loopback-only HTTP server (`127.0.0.1`) serves `/<random-token>/<repo-id>/updatePlugins.xml`. The port is persisted per
  IDE product; if busy, a free port is chosen, persisted, and you get a notification.
* On each request the latest release is looked up (5 min cache + ETag), each zip asset is downloaded **once per asset id**
  and its `plugin.xml` is read. The XML points at the real HTTPS asset URL.
* Private downloads: the IDE downloads the zip itself, so a `PluginRepositoryAuthProvider` adds the auth headers, only for
  the repository's own API asset URL (GitHub) or host (GitLab), and never for the signed redirect target.
* Internal platform API is confined to `adapter/InternalApiAdapter.kt` and fails soft (see `SPIKE.md`).

Known limits: the asset cache is in memory (re-downloaded once after an IDE restart); the repositories do not appear in
**Manage Plugin Repositories** (that dialog only lists user-stored URLs); self-hosted GitLab under a sub-path is not supported.

## Development

```bash
./gradlew test          # unit tests with a mocked HTTP server
./gradlew verifyPlugin  # Plugin Verifier on the latest CLion 2026.1 / 2026.2 / 2026.3 (and any newer)
./gradlew runIde        # sandbox CLion 2026.1
```

## Manual test notes

Not automated; to be run in `runIde`:

1. Public GitHub repo with a plugin zip release: add it, status shows OK, plugin appears in Marketplace search, install it.
2. Publish a newer release: **Check now**, then Plugins | Installed shows the update (the IDE re-reads on its update schedule or via "Check for updates").
3. Wrong URL / repo without releases / private repo without token: the status explains what to do.
4. Private GitHub repo + token: install works (confirms the `Accept: application/octet-stream` header and 302 handling). If the
   IDE sends its own `Accept` too and GitHub answers JSON, see `SPIKE.md` §3.
5. Self-hosted GitLab project with the zip as a release link.
6. Occupy the saved port, restart: a notification mentions the new port.
7. Check `http://127.0.0.1` catalog URLs are accepted by the Marketplace tab (spike item 4) and that an installed plugin keeps
   its update source across a restart (spike item 6).
