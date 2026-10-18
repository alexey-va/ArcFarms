# Bundled Minecraft labels

ArcFarms resolves vanilla item, block and GUI components to Russian before
publishing its text. Plugin catalog selection remains in `ArcFarmsLocale`;
`lang/en.yml` remains available as its English fallback/reference catalog.
No source is registered with Adventure's `GlobalTranslator`.

`src/main/resources/lang/minecraft/ru_ru.json` is the sorted subset of the
official Minecraft Java 1.21.11 Russian language asset containing keys beginning
with `item.minecraft.`, `block.minecraft.` or `gui.` (3,128 entries).

- Source: <https://resources.download.minecraft.net/8a/8a57cfb9eba6a0030253e1065e74f7c63da43a8b>
- Full source SHA-1: `8a57cfb9eba6a0030253e1065e74f7c63da43a8b`
- Minecraft 1.21.11 asset-index SHA-1: `e81d9e2536723a11eb68e11c5642708926b8be5f`
- Version-manifest SHA-1: `718cd84b4912691fe5de829aab6876a4b7f6c5c4`

The resource is packaged in the plugin. Runtime rendering performs no downloads
or file reads. `ArcFarmsLocale` initializes the private Adventure translation
store during startup. Minecraft `%s`/`%N$s` arguments are converted to Java
`MessageFormat` indices; Adventure preserves component arguments, children and
styles while resolving the text.
