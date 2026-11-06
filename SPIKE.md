# SPIKE findings (milestone 2)

Method: static inspection (`javap -p -c`) of `intellij.platform.ide.impl.jar` and related jars from
CLion **261.27258.141 (2026.1.5.2)**, **262.11879.106 (2026.2.4.1)**, **263.7307.209 (2026.3)**.
**Nothing below was verified at runtime** (no IDE was launched); items marked RUNTIME-TODO need `runIde`.

## 1. Exact signatures and cross-version diff

```java
// com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider   (EP: com.intellij.updateSettingsProvider, dynamic)
public interface UpdateSettingsProvider {
  ExtensionPointName<UpdateSettingsProvider> EP_NAME;
  List<String> getPluginRepositories();
  static List<String> getRepositoriesFromProviders();
}

// com.intellij.ide.plugins.auth.PluginRepositoryAuthProvider        (EP: com.intellij.pluginRepositoryAuthProvider, dynamic)
public interface PluginRepositoryAuthProvider {
  ExtensionPointName<PluginRepositoryAuthProvider> EP_NAME;
  Map<String, String> getAuthHeaders(String url);
  boolean canHandle(String url);
}
```

| Class | 261 vs 262 vs 263 |
|---|---|
| `UpdateSettingsProvider` | Signatures identical in all three. Annotated `@ApiStatus.Internal` in all three. |
| `PluginRepositoryAuthProvider` | Signatures identical. **`@ApiStatus.Internal` appears from 262; 261 has no annotation.** |
| `PluginRepositoryAuthService` (consumer) | Same public surface; 263 adds one method/internal changes only. |

Notes:
- `CustomPluginRepoContributor` / EP `customPluginRepoContributor` **does not exist** in 263 (no class, no EP). Not usable; `UpdateSettingsProvider` is the only route.
- Class files: 261 = Java 21 (major 65); 262 and 263 = Java 25 (major 69). Irrelevant for us (we compile to 21).
- Because signatures are identical, the adapter needs no per-version branching. It still must tolerate `NoClassDefFoundError`/`LinkageError` for future builds.

## 2. Does the auth provider apply to the zip download?

Statically: **yes.**
- `PluginRepositoryAuthService.connectionTuner` runs `getAllCustomHeaders(connection.url)` and calls `URLConnection.addRequestProperty(k, v)` for every returned entry. So **arbitrary headers (including `Accept`) can be set.**
- `getAllCustomHeaders(url)` asks every provider `canHandle(url)` then merges `getAuthHeaders(url)`.
- The tuner is attached via `MarketplaceRequestsKt.setHeadersViaTuner` in `MarketplacePluginDownloadService.downloadPlugin` (the zip download used by `PluginDownloader`/`PluginInstaller`), and also in the block-map/range download paths. It is not limited to the XML fetch.
- RUNTIME-TODO: confirm the XML fetch (`RepositoryHelper.loadPlugins`) also passes through the tuner (not needed for our design: the XML is served by us on 127.0.0.1 with no auth).

## 3. GitHub 302 handling

`HttpRequests.openConnection` is recursive per hop: it sets `setInstanceFollowRedirects(false)`, reads `Location`, and opens a **new** connection (limit via `idea.redirect.limit`, default builder limit). The tuner runs again on each new connection with the new URL.
Consequence: the signed redirect target is passed to **`canHandle(url)`**; our provider must return `false` for it, so `Authorization` is not sent to the signed host (S3/Azure reject an extra `Authorization` header alongside a signed query). `canHandle` should be true only for the GitHub API asset URL (and GitLab API/package URLs of configured hosts). RUNTIME-TODO: verify against a real private release asset.

## 4. Is `http://127.0.0.1:<port>` accepted?

Static: `RepositoryHelper.getCustomPluginRepositoryHosts()` concatenates the `CustomPluginRepositorySource` enum values (`STORED_SETTINGS`, `IDEA_PLUGIN_HOSTS_PROPERTY`, `UPDATE_SETTINGS_PROVIDERS`, `CUSTOM_BUILT_IN_REPOSITORY_PROPERTY`) and de-duplicates. I found no scheme or loopback validation in that path. RUNTIME-TODO: confirm plain http is not rejected at fetch time.

## 5. Do contributed URLs show in "Manage Plugin Repositories"?

**No (static).** `PluginHostsConfigurable` reads only `UpdateSettings.getStoredPluginHosts()`; provider URLs come from the `UPDATE_SETTINGS_PROVIDERS` source and are merged only when plugins are loaded. They will appear as a source in the Marketplace tab/search results, but not in that dialog. Our own Settings page is therefore the place to show them.

## 6. Does an installed plugin keep its update source if the port changes?

**Probably not, in 262/263 (static).** There is a `PluginUpdateSourceService` (persisted per-plugin update source). `createCustomRepositoryPluginUpdateSourceId(host)` builds a `Repository(host, ...)` where `host` is the (normalized) full repository URL string including port and path token. If the URL changes, the persisted source no longer matches any configured repository. Whether this blocks updates depends on registry flags (`update.source.initialization.enabled`, `PluginUpdateSourceService.isFunctionalitySupported`). Presence in 261 was not confirmed. RUNTIME-TODO: install from the catalog, change port, restart, check the update badge.
Mitigation: keep the port and path token stable (persisted port first, only fall back when occupied), and keep the repo-id stable.

## Conflicts with the plan (need your decision before building further)

1. **Internal API vs failing verification.** Both extension points are `@ApiStatus.Internal` (auth provider from 262). `verifyPlugin` is configured to fail on `INTERNAL_API_USAGES`, so it will fail as soon as the adapter implements either interface. Options: (a) keep failing on internal usage but allow-list our adapter via the verifier's ignore list, (b) report internal usage without failing (move `INTERNAL_API_USAGES` out of `failureLevel`; still visible in the report), (c) keep the build failing and accept red CI. I recommend (b) plus keeping COMPATIBILITY_PROBLEMS, SCHEDULED_FOR_REMOVAL and INVALID_PLUGIN as failures.
2. **Update source is URL-keyed** (item 6): a port change can orphan installed plugins' update source. The "notify the user if the port changed" design stays, but the notification should say updates may need a re-install.
3. **Manage Plugin Repositories won't list our URLs** (item 5): the plan expected them to maybe show; they won't.

Approach B (auth provider) looks feasible. Approach A is not needed on this evidence, pending a runtime check.

## Decisions taken (after the spike)

1. Internal API: `INTERNAL_API_USAGES` removed from the verifier's failure levels; it is still printed in the report
   (3 usages on 262/263, all in `adapter/InternalApiAdapter.kt`). Compatibility problems, scheduled-for-removal API and invalid plugin still fail the build.
   The verifier did not flag `UpdateSettingsProvider` at all, only `PluginRepositoryAuthProvider`.
2. Port/URL stability: the port and the URL token are persisted and reused; on a forced port change the user gets a notification
   saying to reinstall a plugin that stops offering updates.
3. Our own settings page (Tools | Repo Release Sources) is the place that lists the repositories.
