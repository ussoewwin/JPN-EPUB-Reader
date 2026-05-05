# EPUB OPF Load Failure - Complete Technical Postmortem (v1.09 scope)

## 1) Error Message (Observed Symptom)

The app failed to open some EPUB files and showed:

- `Invalid EPUB: missing OPF at OPS/package.opf`

This happened even though the same file opened correctly in Perfect Viewer.

---

## 2) Essential Root Cause

The EPUB file itself was valid.

The real issue was in our parser input path and ZIP-reading strategy, not in filename text, and not in the desktop path string.

### What was happening

1. The app opened EPUB via Android `content://` URI (`OpenDocument` flow).
2. `EpubParser.parse(uri)` read ZIP entries using a streaming approach (`ZipInputStream`).
3. For some provider/file combinations, this stream path was not robust enough in practice, causing inconsistent resource-map population before OPF lookup.
4. OPF lookup then failed at `resources[rootFilePath]`, producing the `missing OPF` exception.

### Why this is not a "path-name" issue

- The EPUB contains `META-INF/container.xml` with `full-path="OPS/package.opf"`.
- The same archive includes a real `OPS/package.opf` entry.
- Perfect Viewer opens it, proving the EPUB package is valid.
- Therefore, the failure was our loading pipeline robustness, not the human-readable path or filename.

---

## 3) File(s) Modified

- `app/src/main/java/com/jpnepub/reader/epub/EpubParser.kt`

No other production parser file was changed for this OPF-loading fix.

---

## 4) All Additional Code Added/Changed

Below is the complete added/changed code scope for this fix.

### 4.1 Import update

```kotlin
import java.util.zip.ZipFile
```

### 4.2 `parse(uri: Uri)` rewritten to path-independent, deterministic ZIP loading

```kotlin
fun parse(uri: Uri): EpubBook {
    val resources = mutableMapOf<String, ByteArray>()
    val tmp = File.createTempFile("jpnepub_", ".epub", context.cacheDir)
    try {
        // Copy content:// to a local file, then read ZIP central directory with ZipFile.
        // Some providers can cause missing-entry behavior with direct ZipInputStream reads.
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        } ?: throw IOException("Cannot open EPUB file")

        ZipFile(tmp).use { zip ->
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                if (entry.isDirectory) continue
                zip.getInputStream(entry).use { ins ->
                    resources[normalizePath(entry.name)] = ins.readBytes()
                }
            }
        }
    } finally {
        tmp.delete()
    }

    val containerXml = resources["META-INF/container.xml"]
        ?: throw IOException("Invalid EPUB: missing container.xml")
    val rootFilePath = parseContainer(containerXml)
    val resolvedOpfPath = resolveResourcePath(rootFilePath, resources)
        ?: throw IOException("Invalid EPUB: missing OPF at $rootFilePath")
    val opfData = resources[resolvedOpfPath]
        ?: throw IOException("Invalid EPUB: missing OPF at $rootFilePath")
    val opfDir = resolvedOpfPath.substringBeforeLast('/', "")

    return parseOpf(opfData, opfDir, resources)
}
```

### 4.3 `normalizePath` strengthened

```kotlin
private fun normalizePath(path: String): String {
    return path.replace('\\', '/').removePrefix("/").removePrefix("./")
}
```

### 4.4 New resolver for `container.xml` `full-path` -> real ZIP entry key

```kotlin
/**
 * Absorb path variations between container.xml full-path and ZIP entry keys.
 * - separator differences (`\\` / `/`)
 * - leading `./` / `/`
 * - case differences
 */
private fun resolveResourcePath(path: String, resources: Map<String, ByteArray>): String? {
    val normalized = normalizePath(path).trim()
    if (normalized.isEmpty()) return null

    resources[normalized]?.let { return normalized }
    resources.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }

    // Final fallback: match by trailing file segment if needed.
    val tail = normalized.substringAfterLast('/')
    resources.keys.firstOrNull { it.substringAfterLast('/').equals(tail, ignoreCase = true) }?.let {
        return it
    }
    return null
}
```

### 4.5 `parseContainer` now normalizes returned `full-path`

```kotlin
private fun parseContainer(data: ByteArray): String {
    val parser = newParser()
    parser.setInput(ByteArrayInputStream(data), "UTF-8")

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
            val raw = parser.getAttributeValue(null, "full-path")
                ?: throw IOException("Invalid container.xml")
            return normalizePath(raw)
        }
    }
    throw IOException("No rootfile found in container.xml")
}
```

---

## 5) Meaning of Each Change (Why It Matters)

### A. `ZipInputStream` -> `ZipFile` (via temp file)

- **Meaning:** We switched from sequential stream parsing to central-directory-based ZIP reading.
- **Impact:** Entry discovery is deterministic and complete for problematic provider/stream combinations.
- **Reason:** This removes the class of failures where OPF is physically present in archive but effectively not available in the parser resource map at lookup time.

### B. Path normalization (`\\`/`/`, prefixes)

- **Meaning:** We enforce a canonical key format before storage and lookup.
- **Impact:** Prevents mismatches caused by separator style or leading markers.

### C. `resolveResourcePath(...)` fallback strategy

- **Meaning:** OPF key resolution now handles case and minor path-shape drift safely.
- **Impact:** Avoids brittle single-key exact-match failure at OPF lookup.

### D. `parseContainer(...)` normalized return

- **Meaning:** `full-path` from container is canonicalized immediately.
- **Impact:** Keeps OPF resolution consistent from the earliest point.

---

## 6) Final Conclusion

This was a parser robustness defect in our Android URI/ZIP loading path.

- Not a filename-language issue
- Not a desktop path-string issue
- Not an EPUB validity issue

The implemented fix makes OPF detection path-independent and provider-resistant by combining:

1. deterministic ZIP entry enumeration (`ZipFile`), and
2. canonical + tolerant OPF path resolution.

That is why the same book now opens successfully.
