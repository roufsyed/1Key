# Legal Documents - Plan v2 (Round 2 Revisions Applied)

This is the architectural plan for the new `LEGAL_DOCUMENT` credential type, after two rounds of adversarial multi-agent review. v1 was produced by a 6-architect / 10-critic workflow. v2 incorporates the R2 critic's 13 blockers and key revisions.

Status: design complete, ready for Phase 1 PR review.

## Blocker resolutions at a glance

| # | Blocker | Resolution |
|---|---|---|
| B1 | Cross-envelope blob substitution | Per-blob AAD includes `envelope_salt_hex`. New test asserts cross-envelope graft is rejected. |
| B2 | IV strategy self-contradicts | Two-pass encrypt; Cipher always self-generates IV. No new primitive. |
| B3 | Restored photos undecryptable | New `RestoreLegalDocumentUseCase` decrypts under backup key then re-encrypts under local vault key with fresh IV + at-rest AAD. |
| B4 | FK CASCADE silent data loss | Drop FK CASCADE entirely. Follow `credential_history` precedent. Explicit `photoRepo.deleteForCredential` calls. `LEGAL_DOCUMENT` upserts route through photo-preserving helper. |
| B5 | 16 MiB BLOBs hit CursorWindow | Blob bytes move to `context.filesDir/photos/<photo_id>.bin`. Room holds metadata only (~120 bytes/row). |
| B6 | Missing `cipher_version` | Add `cipher_version: Int = 1` column. Drop `aad_version`. Read path dispatches like `CredentialDecryptor`. |
| B7 | `FLAG_SECURE` collector race | New `SecureWindowController` `@Singleton` with `MutableStateFlow<ForceSecure>`. MainActivity computes effective flag. |
| B8 | Process death loses bytes | Encrypt-to-disk immediately on `onCaptureSuccess`. Draft row keyed by `draft_credential_id` in SavedStateHandle. Save promotes atomically. |
| B9 | Phase 1 zero recovery | Phase 1 + 2 collapsed: schema + capture + viewer + V6 manual backup ship together. No Phase 1 release without backup. |
| B10 | `hasPhotos` no population | Extend `SELECT_ACTIVE_SQL` with EXISTS subquery. Add `credential_photos` to snapshot's observed-tables list. `@Ignore`'d `hasPhotos` on `CredentialEntity`. |
| B11 | Restore vs purge race | `Mutex` in `BackupRepository`; purge skips while held; restore on non-empty DB shows clear-and-replace confirm. |
| B12 | DB version race | Reserve version 16 NOW. CI step asserts `schemas/<n>.json` exists for declared `@Database(version)`. |
| B13 | Phase 2/4 version skew | V5 sync REFUSES `LEGAL_DOCUMENT` rows with photos (badged local-only). Removed by Phase 3 (V6 sync). |

## 1. Data model

```kotlin
// CredentialType.kt - add value
LEGAL_DOCUMENT("Legal Document"),

// New file: PhotoSide.kt
enum class PhotoSide { FRONT, BACK }

// Credential.kt - add field
val photos: List<DocumentPhoto> = emptyList(),  // 0..2

// New file: DocumentPhoto.kt
@Immutable
data class DocumentPhoto(
    val id: String,                  // UUID; matches filename in filesDir/photos/
    val side: PhotoSide,
    val width: Int,
    val height: Int,
    val sha256: ByteArray,           // 32 bytes, full plaintext digest (corruption check)
    val plaintextSize: Int,          // for UI heap budgeting
    val cipherVersion: Int = CURRENT_PHOTO_CIPHER_VERSION,  // = 1 today
) {
    companion object {
        const val PHOTO_MAX_BYTES = 16 * 1024 * 1024     // sidecar file, no CursorWindow constraint
        const val PHOTO_TYPICAL_SOFT_LIMIT = 3 * 1024 * 1024  // warns user above this
        const val PHOTO_MAX_LONG_EDGE_PX = 2_048
        const val JPEG_QUALITY_DEFAULT = 92
        const val CURRENT_PHOTO_CIPHER_VERSION = 1
    }
}
```

`bytes` is no longer in the domain model. Bytes flow through transient encrypt/decrypt scopes (capture, encrypt, disk; disk, decrypt, bitmap, recycle). Domain layer never holds plaintext bytes.

## 2. Storage architecture (B4, B5, B6)

**Sidecar-file design**: blob ciphertext lives in `context.filesDir/photos/<photo_id>.bin`. Room row holds metadata only.

```kotlin
@Entity(
    tableName = "credential_photos",
    primaryKeys = ["credential_id", "side"],
    indices = [Index(value = ["credential_id"], name = "index_credential_photos_credential_id")],
    // NO @ForeignKey. Follows credential_history precedent.
)
data class PhotoEntity(
    @ColumnInfo(name = "credential_id") val credentialId: String,
    @ColumnInfo(name = "side") val side: String,         // "FRONT" | "BACK"
    @ColumnInfo(name = "photo_id") val photoId: String,  // UUID; matches sidecar filename
    @ColumnInfo(name = "iv") val iv: ByteArray,          // 12-byte GCM nonce
    @ColumnInfo(name = "ciphertext_size") val ciphertextSize: Long,  // for streaming reads
    @ColumnInfo(name = "sha256") val sha256: ByteArray,  // 32-byte plaintext digest
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "cipher_version") val cipherVersion: Int,  // = 1 today; dispatch on read
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,  // optimistic-concurrency token
)
```

**Row size**: ~120 bytes. No CursorWindow issue. List flows are projection-only DTOs containing `(credential_id, side, sha256_prefix(8), width, height)`. Even with 1000 photos the cursor stays under 200 KB.

**Sidecar file lifecycle**:
- `PhotoRepositoryImpl.savePhotos` writes ciphertext to `<photo_id>.bin.part` then atomic-renames to `<photo_id>.bin`. The Room row is written in the same `withTransaction` as the credential, AFTER the file rename succeeds.
- `deleteForCredential(id)` reads all photo rows for that credential, deletes the rows, then deletes the sidecar files (best-effort; orphan-file sweep on next unlock catches misses).
- **Orphan sweep**: on unlock, scan `filesDir/photos/`. Any `.bin` whose `photo_id` does not match a `PhotoEntity` row is deleted. Bounded I/O, only runs after a successful vault unlock.

**Explicit cascade replacement (B4)**: these five use cases call `photoRepo.deleteForCredential(id)` before/after deleting the credential row, inside the same `withTransaction`:
1. `HardDeleteCredentialUseCase`
2. `PurgeFromRecycleBinUseCase`
3. `PurgeExpiredRecycleBinUseCase`
4. `EmptyRecycleBinUseCase`
5. New: `TypeChangeAwayFromLegalDocUseCase` (covers the type-change cascade)

`RestoreFromRecycleBinUseCase` does NOT touch photos. Photos survive soft-delete intact.

**Upsert collision fix (B4)**: `CredentialDao.upsert` stays as-is (REPLACE) for non-Legal-Doc types. `LEGAL_DOCUMENT` rows go through a new `CredentialDao.upsertPreservingPhotos(credential)` that uses INSERT OR IGNORE then explicit UPDATE on the same row, never DELETE-then-INSERT. Existing call sites (autofill save, importer, future cipher migrator) are routed through a small dispatch helper in `CredentialRepositoryImpl`.

## 3. Encryption (B1, B2, B3, B6)

**HKDF labels** (new in `CryptoManager.kt`):
```kotlin
internal const val HKDF_PHOTO_KEY_INFO = "1key-photo-enc-v1"
internal const val HKDF_BACKUP_FASTCHECK_INFO = "1key-bkp-fastcheck-v6"  // renamed from idx
```

**At-rest photo AAD** (cipher_version=1 today):
```
photoAad(credentialId, side) = "1k:v3|<credentialId>|photo|<SIDE>"
```
Namespace `v3` is the AAD-format version (next slot after v1=field, v2=title). Per-row `cipher_version` column dispatches the read path; same shape as `CredentialDecryptor.decryptFull`. When a future cipher rev lands, `cipher_version=2` rows use a different `HKDF_PHOTO_KEY_INFO` (`-v2`) AND a different AAD shape; the read path branches.

**Backup envelope per-blob AAD** (B1 fix):
```
blobAad(credId, side, index, envelopeSalt) =
    "1k:bkp:v6|blob|<credId>|<SIDE>|<index>|" + envelopeSalt.toHex()
```
Where `envelopeSalt` is the 32-byte `SALT` field already in the V6 fixed header. Binding the salt makes each blob's GCM tag uncomputable for any other envelope; cross-envelope graft is impossible (the salt is per-export-random).

**Encrypt primitive** (B2 fix, existing `CryptoManager.encrypt` unchanged):
```kotlin
fun CryptoManager.encrypt(plaintext: ByteArray, key: SecretKey, aad: ByteArray): EncryptedData
// Cipher always self-generates IV. Caller never supplies one.
```

## 4. V6 envelope (two-pass, no pre-planned IVs)

**Byte layout**:
```
A (0..75)    Fixed V6 header: MAGIC(8) VER(1=0x06) FORMAT(1) FLAGS(1)
             KDF_M_KIB(4) KDF_T(4) KDF_P(1) TIMESTAMP(8) VAULT_VER(4)
             SALT(32) IV_JSON(12)
B (76..99)   Section header: JSON_CT_LEN(4 BE) BLOB_COUNT(4 BE) FASTCHECK_HMAC(16)
C            JSON ciphertext + 16B GCM tag (JSON_CT_LEN bytes)
D            Manifest, BLOB_COUNT rows of:
             IV(12) CT_LEN(4 BE) TAG(16) SIDE(1) CIPHER_VER(1) RESERVED(2)
             CRED_ID_LEN(2 BE) CRED_ID(UTF-8, max 64B)
E            Blob bodies in manifest order, each CT_LEN[i] - 16 bytes
```

**Changes from v1 plan**:
- SHA256_PLAIN dropped from each manifest row. Per-row GCM tag plus envelope-bound AAD provides full integrity. SHA was redundant.
- CIPHER_VER added to manifest row so a v2 future cipher does not require a V7 envelope.
- CRED_ID_LEN narrowed to 2 bytes (UUIDs are 36 bytes; 2 bytes is plenty, saves 6 bytes per row).
- FASTCHECK_HMAC (renamed from `IDX_TAG`). Documented explicitly: corruption-fast-fail mechanism only. Security comes from `SHA256(manifest)` bound in JSON AAD.

**Outer JSON AAD**:
```
V6_JSON_AAD = V5_AAD_BYTES(VER=0x06) || BLOB_COUNT(4 BE) || JSON_CT_LEN(4 BE) || SHA256(manifest_D)
```

**Two-pass encrypt flow** (B2 fix):
```
Pass 1 (per blob):
    ct_and_tag = Cipher.doFinal(plain)  // Cipher self-generates IV
    iv = cipher.iv                       // read back the actual IV used
    ct  = ct_and_tag[..-16]
    tag = ct_and_tag[-16..]
    rows[i] = ManifestRow(iv, ct.size + 16, tag, side, cipher_ver=1, credId)
    plain.fill(0)

Pass 2 (after all blobs encrypted):
    manifestBytes = serialize(rows)
    manifestDigest = SHA-256(manifestBytes)
    jsonBytes = buildJsonDtoWithBlobRefs(credentials, rows)
    jsonAad = buildV6JsonAad(header, BLOB_COUNT, JSON_CT_LEN, manifestDigest)
    jsonCtAndTag = AES-GCM(jsonBytes, derivedKey, jsonAad)
    fastcheckKey = HKDF-Expand(derivedKey, HKDF_BACKUP_FASTCHECK_INFO)
    fastcheckTag = HMAC-SHA256(fastcheckKey, manifestBytes)[0..16]

Phase 3 (single linear write):
    out << header(A) << sectionHeader(B) << jsonCtAndTag(C) << manifestBytes(D) << ct[*](E)
```

Worst-case heap: 1 blob plaintext (16 MiB) + 1 blob ciphertext (16 MiB) + manifest (~300 KiB) + JSON (~1 MiB). Peak ~33 MiB, well below low-end heap budgets.

**V6 decrypt flow** with pre-authentication bounds checks:
1. Read header A (76 B). Validate MAGIC, VERSION=0x06, FORMAT=JSON-only.
2. Read section header B (24 B).
3. Bounds checks BEFORE any allocation (all fail with single generic `InvalidEnvelopeException`, never leak which check fired):
   - `BLOB_COUNT <= MAX_BLOBS_V6 (4096)`
   - `JSON_CT_LEN >= 16 && JSON_CT_LEN <= MAX_JSON_CT_BYTES (8 MiB)`
   - `JSON_CT_LEN + V6_HEADER_LEN + V6_SECTION_HEADER_LEN <= file.length()`
4. If `FLAGS.requiresSK && secretKey == null` then `SecretKeyRequiredException` (no KDF work done).
5. Derive key (Argon2id + SK).
6. Read JSON ciphertext (`JSON_CT_LEN` bytes).
7. Read manifest D row-by-row. Each row validates:
   - `CT_LEN[i] >= 16 && CT_LEN[i] <= MAX_BLOB_BYTES + 16`
   - `CRED_ID_LEN[i] in 1..64`
   - `SIDE[i] in {0,1}` (FRONT=0, BACK=1)
   - `CIPHER_VER[i] in 1..CURRENT_PHOTO_CIPHER_VERSION` (future-cipher rejected, never coerced)
   - `RESERVED[i] == 0`
   - Running `sum(CT_LEN[i]) <= MAX_TOTAL_BLOB_BYTES (64 MiB)`
8. Verify FASTCHECK_HMAC. Mismatch then `InvalidEnvelopeException`.
9. Decrypt JSON with `buildV6JsonAad(...SHA-256(manifestBytes)...)`. Any upstream tampering surfaces here as `AEADBadTagException` then "Wrong password or corrupted backup".
10. Return `DecryptedV6(plaintext, blobs: Sequence<BlobHandle>)`. Each `BlobHandle.decrypt()` reads `CT_LEN-16` bytes from the source stream, appends the row tag, decrypts with `blobAad(credId, side, index, envelopeSalt)`.

**Test corpus** enumerated in the Phase 1 PR description:
- Truncation at every section boundary (mid-A through mid-E)
- `BLOB_COUNT=0` with `FLAG_HAS_BLOBS` set
- `BLOB_COUNT=4097`
- `CRED_ID_LEN={0, 65, 0xFFFF}`
- `JSON_CT_LEN > file.size()`
- `SIDE=0xFF`, `CIPHER_VER=99`, `RESERVED!=0`
- FASTCHECK_HMAC verifies but manifest digest in AAD does not match
- Duplicate `(credId, side)` rows in manifest
- V5 reader fed V6 bytes asserts stable error message string
- Cross-envelope graft test: export same vault twice with 2s delay; attempt manifest-row + blob-body swap from A into B, decrypt fails
- Happy path with 1, 2, 4, 4096 blobs
- CI fuzz target: 1000 random byte-flips on a valid V6 envelope, assert no SIGSEGV/OOM and AEADBadTagException carries no header metadata

## 5. Restore flow (B3 - new use case)

```kotlin
class RestoreLegalDocumentUseCase(
    private val credRepo: CredentialRepository,
    private val photoRepo: PhotoRepository,
    private val keyHolder: VaultKeyHolder,
    private val crypto: CryptoManager,
    private val database: OneKeyDatabase,
) {
    suspend operator fun invoke(
        decrypted: DecryptedV6,
        backupDerivedKey: SecretKey,
    ): AppResult<Int> = withContext(Dispatchers.Default) {
        keyHolder.withUnlockedKey {
            val localPhotoKey = crypto.deriveSubkey(keyHolder.requireKey(), HKDF_PHOTO_KEY_INFO)

            var restoredCount = 0
            for (blobHandle in decrypted.blobs) {
                val plaintext = blobHandle.decrypt()  // under backup key + envelope AAD
                try {
                    // Re-encrypt under local vault key + at-rest AAD with fresh IV
                    val atRestAad = photoAad(blobHandle.credId, blobHandle.side)
                    val reEncrypted = crypto.encrypt(plaintext, localPhotoKey, atRestAad)
                    val photoId = UUID.randomUUID().toString()
                    photoRepo.writeBlobFile(photoId, reEncrypted.ciphertext)  // atomic .part rename
                    database.withTransaction {
                        photoRepo.insertMetadata(PhotoEntity(
                            credentialId = blobHandle.credId,
                            side = blobHandle.side.name,
                            photoId = photoId,
                            iv = reEncrypted.iv,
                            ciphertextSize = reEncrypted.ciphertext.size.toLong(),
                            sha256 = SHA-256(plaintext),
                            width = blobHandle.width,
                            height = blobHandle.height,
                            cipherVersion = CURRENT_PHOTO_CIPHER_VERSION,
                            createdAt = blobHandle.createdAt,
                            updatedAt = now(),
                        ))
                    }
                    restoredCount++
                } finally {
                    plaintext.fill(0)   // bound transient residency to one blob at a time
                }
            }
            AppResult.Success(restoredCount)
        }
    }
}
```

Heap residency capped at one 16 MiB JPEG plaintext at a time. Threat model updated: TV11 "Transient plaintext during restore" bounded to single-blob window, plaintext zeroed in `finally`.

## 6. Save orchestration with draft persistence (B8)

**Capture sheet**: on `onCaptureSuccess(ImageProxy)`:
1. Decode, downscale to `PHOTO_MAX_LONG_EDGE_PX`, `Bitmap.compress(JPEG, quality)`. EXIF stripped via re-encode (TV1).
2. **Immediately encrypt** with `localPhotoKey` + at-rest AAD using a `draftPhotoAad(draftCredId, side) = "1k:v3:draft|<draftCredId>|photo|<SIDE>"`. Namespaced to draft so an unfinished credential's photo cannot be referenced from a real-credential save path.
3. Write ciphertext to `filesDir/photos/<photo_id>.bin.part`, atomic rename.
4. Insert a row into a new `draft_photos` table (same schema as `credential_photos` plus a `draft_credential_id` column, no FK).
5. Return `DocumentPhoto(id=photo_id, side, width, height, sha256, plaintextSize)` to the caller. No bytes in the return value.
6. Wipe the plaintext byte array in `finally`.

**`draftCredentialId`** is a UUID generated when the credential editor opens for a new LEGAL_DOCUMENT. Stored in `SavedStateHandle["draft_credential_id"]` (36 bytes, fits trivially). On process recreate, the ViewModel re-queries `draft_photos` for that draftId and rehydrates filled slots.

**Save**: `SaveLegalDocumentUseCase`:
```kotlin
suspend operator fun invoke(credential: Credential, draftCredentialId: String) = withContext(Dispatchers.Default) {
    check(Thread.currentThread().name.startsWith("DefaultDispatcher"))
    keyHolder.withUnlockedKey {
        validate(credential)  // type, photo count, sha dedupe, size soft warning
        database.withTransaction {
            credRepo.upsertPreservingPhotos(credential.copy(photos = emptyList()))
            // Promote draft rows to real photo rows.
            photoRepo.promoteDraftsToCredential(draftCredentialId, credential.id)
        }
    }
}
```

`promoteDraftsToCredential` decrypts under draft AAD, re-encrypts under real AAD with fresh IV. Heap-bounded to one blob at a time. This is the only place where the existing photo gets re-encrypted on save; it is a known cost (~100ms per blob) and acceptable.

**BackHandler** on the edit screen: if any draft photos exist and the user backs out without saving, prompt "Discard pending photos? They cannot be recovered." Yes runs `photoRepo.deleteDraftsFor(draftCredentialId)`. No stays on the screen.

**Orphan draft sweep**: drafts older than 7 days are purged on unlock. The 7-day window covers users who interrupt a save and return days later.

## 7. SecureWindowController (B7)

New `core/security/SecureWindowController.kt`:
```kotlin
@Singleton
class SecureWindowController @Inject constructor() {
    val forceSecure = MutableStateFlow(false)
}
```

`MainActivity.kt` (replaces the existing isolated FLAG_SECURE collector):
```kotlin
lifecycleScope.launch {
    combine(
        userPrefs.observeScreenshotsEnabled(),
        secureWindowController.forceSecure,
    ) { screenshotsEnabled, forceSecure ->
        forceSecure || !screenshotsEnabled
    }.collect { effectiveSecure ->
        if (effectiveSecure) window.addFlags(FLAG_SECURE) else window.clearFlags(FLAG_SECURE)
    }
}
```

`LockAwarePhotoViewer` and `DocumentPhotoCaptureSheet`:
```kotlin
DisposableEffect(Unit) {
    secureWindowController.forceSecure.value = true
    onDispose { secureWindowController.forceSecure.value = false }
}
```

Test: `LegalDocViewerSecureWindowTest` opens the viewer with screenshots-pref enabled, fires a forced preference re-emission, asserts the window still rejects screenshots throughout.

Note: if the viewer is rendered as a Compose `Dialog`, the Dialog owns a separate window. `FLAG_SECURE` is NOT inherited. Either move the viewer off `Dialog` to a route destination, or extend `SecureWindowController` to plumb the flag onto the Dialog's own window via `DialogWindowProvider`. Decision pending Q17 (see open decisions).

## 8. Snapshot store integration (B10)

`CredentialDao.SELECT_ACTIVE_SQL` extended:
```sql
SELECT c.*, EXISTS(SELECT 1 FROM credential_photos p WHERE p.credential_id = c.id) AS has_photos
FROM credentials c WHERE c.deleted_at IS NULL
```

`CredentialEntity` gains:
```kotlin
@Ignore @JvmField var hasPhotos: Boolean = false
```

`CredentialDao.observeListRaw` adds `credential_photos` to its observed-tables array so Room invalidates the cursor when a photo is added/removed.

`SnapshotCredential.hasPhotos: Boolean` propagates the flag. List-row UI shows a small document icon when `hasPhotos`.

**Bypassed mode** (vaults > 10K creds): the lean SQL fallback path mirrors the same EXISTS subquery. No N+1 query, no special-casing.

`PhotoRepository` exposes `observeForCredential(id)` as a separate `Flow<List<PhotoEntity>>` distinct from `CredentialRepository.observeCredential(id)`. A corrupt photo blob surfaces a per-side error state in the viewer but does NOT poison the credential's title/customFields/notes.

## 9. Restore/purge serialization (B11)

`BackupRepository` gains a `Mutex restoreInProgress`. `PurgeExpiredRecycleBinUseCase` calls `restoreInProgress.tryLock()`; if false, skip this purge cycle (next unlock retries). `RestoreLegalDocumentUseCase` (and its sibling `RestoreFromBackupUseCase`) hold the mutex for their entire duration including all photo writes.

V6 restore on a non-empty DB shows a clear-and-replace confirmation: "Restoring this backup will replace your current vault. Continue? [Cancel] [Replace]". On Replace, restore runs `database.clearAllTables()` + `filesDir/photos/` purge first, then writes.

## 10. DB version reservation + CI check (B12)

**Version 16 reserved NOW** for this feature. The plan claims version 16; any other in-flight branch must rebase to 17. Documented in `CONTRIBUTING.md`: "DB-bump PRs serialize. Coordinate in #eng before claiming a version slot."

**CI check** in `.github/workflows/android.yml`:
```yaml
- name: Verify schema files match @Database(version)
  run: |
    VER=$(grep -oP 'version\s*=\s*\K\d+' app/src/main/java/com/onekey/core/data/local/database/OneKeyDatabase.kt | head -1)
    for i in $(seq 1 $VER); do
      test -f app/schemas/roufsyed.onekey.core.data.local.database.OneKeyDatabase/$i.json \
        || (echo "Missing schema file for version $i" && exit 1)
    done
    grep -q '"tableName": "credential_photos"' app/schemas/.../$VER.json \
      || (echo "Missing credential_photos in latest schema" && exit 1)
```

## 11. UX (revised - B7, B8, B10, R9, R10)

**LegalDocumentPhotosSection**: two slots (FRONT, BACK). Empty tap opens `DocumentPhotoCaptureSheet`. Filled tap opens `LockAwarePhotoViewer`. Long-press menu: Retake / Remove / (Phase 2) Run OCR. Independent per-side error state if blob decrypt fails.

**Capture sheet**:
- `bindToLifecycle` wrapped in try/catch on `CameraInUseException` + `CameraInfoUnavailableException`.
- Cameraless devices: `packageManager.hasSystemFeature(FEATURE_CAMERA_ANY)`, if absent the slot shows "No camera available" disabled state with deep-link to settings (no recovery, but no crash).
- Permission denied + "don't ask again": bottom sheet routes to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with `ActivityResult` recheck on return.
- Quality control: M3 `SegmentedButton` (Smaller / Balanced / Best), default Best (q=92).
- `onTrimMemory(RUNNING_LOW | CRITICAL)`: recycle bitmap, dismiss sheet with "Capture interrupted, please retry" toast.

**LockAwarePhotoViewer**:
- `SecureWindowController.forceSecure.value = true` on enter, false on dispose.
- Predictive back: `rememberPredictiveBackHandler` keeps the viewer rendering through the gesture; bitmap recycle moves into a `LaunchedEffect` keyed on a `fullyDisposed` signal.
- Bitmap held in `remember { mutableStateOf<Bitmap?> }`; old bitmap recycled only after new one paints (avoids predictive-back transient flash).
- `ComponentCallbacks2.onTrimMemory` recycles and pops the viewer on `RUNNING_LOW/CRITICAL`. `bitmap.isRecycled` guard at every draw site.
- Pinch-to-zoom: two-pass decode, small `inSampleSize` for first paint (~512px), full resolution on first pinch-out > 1.0x. Recycle small bitmap when full ready. Clamp scale [1.0, 5.0].

**Accessibility**:
- Slot contentDescription localized: "Front side of document, captured" / "Front side of document, empty, tap to capture" / "Back side of document, ...".
- Inner `Image` uses `clearAndSetSemantics` to suppress pixel-content announcement.
- Photo subtree (parent `Box` covering both slots + viewer) declares `Modifier.semantics { contentType = ContentType.Unknown }` (Compose 1.7+ API). `AutofillIgnoresPhotosTest` walks `AssistStructure` and asserts photo subtree is invisible to the autofill service.

**`DocumentPhoto.bytes` lifecycle**: bytes never enter `remember`, never enter `rememberSaveable`, never enter `SavedStateHandle`. Only path: capture, encrypt, disk (in capture sheet ViewModel scope, on Dispatchers.Default). Bytes wiped in `finally`.

## 12. V5 sync hardening (B13)

`SyncEngineImpl.collectActiveCredentials()` now **refuses** to upload `LEGAL_DOCUMENT` rows that have any photos. The credential row stays local-only with a per-row `localOnly: Boolean` flag (derived at query time from `LEGAL_DOCUMENT + hasPhotos`). The credential list shows a "local only" badge on those rows.

**At capture time** (not just at export time): the first save of a LEGAL_DOCUMENT photo on any device shows a one-time modal: "Photos on Legal Documents do not sync. Add them on each device. Backup files (manual export) do include photos. [Got it]". Dismissed sets preference `legal_docs_no_sync_warning_seen = true`.

Phase 3 (V6 sync) lifts the refusal: `SyncEngineImpl` switches to `encryptToStreamV6` and uploads everything.

## 13. Diagnostics (R12 - opt-in local only)

New `LegalDocDiagnostics` `@Singleton`:
- Bounded `RingBuffer<LegalDocOperationFailure>(50)`. Each entry: `(timestamp, op=ENCRYPT|DECRYPT|CAPTURE|SAVE|RESTORE, credentialIdHash, exceptionClass, sideEffect)`. **Never** clear credentialId, paths, byte sizes, or stack traces.
- Off by default. Settings toggle "Local diagnostics" enables capture.
- "Copy diagnostics" button in Settings + FAQ surfaces redacted output for the user to paste into a support ticket.
- Manual "Purge diagnostics" button. Auto-purged on vault reset.
- Buffer cleared on lock; survives only the unlocked session.

Without this, a feature with permanent-data-loss potential ships into a no-telemetry codebase with zero visibility into production failures.

## 14. Revised phasing (B9, B13)

**Phase 1 - Schema + capture + viewer + V6 manual backup (combined)**

Why combined: shipping schema without V6 backup means dogfooders lose real ID photos on factory reset. Unacceptable.

Deliverables:
- `CredentialType.LEGAL_DOCUMENT` + `PhotoSide` + `DocumentPhoto`
- `PhotoEntity` + `PhotoDao` + `PhotoRepository(Impl)` with sidecar-file storage
- DB migration to version 16 + AndroidTest (no FK, composite PK, file lifecycle)
- `HKDF_PHOTO_KEY_INFO` + `photoAad()` helper
- V6 envelope constants, `buildV6JsonAad`, `buildV6BlobAad`, `encryptToStreamV6`, `decryptStreamingV6`
- `BackupEncryptionV6Test` full corpus + fuzz target
- `RestoreLegalDocumentUseCase`
- `SaveLegalDocumentUseCase` with `keyHolder.withUnlockedKey` + `withTransaction`
- Draft photo persistence (`draft_photos` table, draft AAD, orphan sweep)
- `DocumentPhotoCaptureSheet` (CameraX, EXIF strip, downscale, quality SegmentedButton)
- `LegalDocumentPhotosSection` composable in `CredentialDetailScreen`
- `LockAwarePhotoViewer` with predictive-back + onTrimMemory + isRecycled guards
- `SecureWindowController` `@Singleton` (replaces existing isolated MainActivity flag collector)
- `SnapshotCredential.hasPhotos` via EXISTS subquery + observed-tables list
- `AuthRepositoryImpl.resetVault()` extension calling `database.clearAllTables()` + photo file purge
- `CredentialDao.upsertPreservingPhotos()` + dispatch helper for LEGAL_DOCUMENT writes
- 5 use case extensions to call `photoRepo.deleteForCredential(id)`: HardDelete, PurgeFromRecycleBin, PurgeExpiredRecycleBin, EmptyRecycleBin, TypeChangeAwayFromLegalDoc
- `restoreInProgress` Mutex + clear-and-replace confirm dialog
- V5 sync refusal for LEGAL_DOCUMENT-with-photos rows + capture-time modal + list-row badge
- `VaultExporterImpl` CSV: omit photos with Toast warning
- `LegalDocDiagnostics` (off by default)
- `SettingsFaqScreen` + `SettingsSearchIndex` entries
- `PhotoExifStripTest`, `LegalDocViewerSecureWindowTest`, `AutofillIgnoresPhotosTest`
- `NoNotificationsInLegalDocs` lint rule
- CI schema-presence check
- Feature flag `legal_documents_enabled` (build-time const; default OFF in v1.x, ON in v2.0)

**Phase 2 - OCR retrofit for Legal Documents**

Same as old Phase 3.

Deliverables:
- `ocrTargetsFor(LEGAL_DOCUMENT)` branch with: Document Number (sensitive), Full Name, Date of Birth, Date of Issue, Date of Expiry, Issuing Authority, Nationality, Place of Birth, MRZ Line 1 (sensitive), MRZ Line 2 (sensitive), Notes
- `OcrScannerSheet` refactor to accept optional `initialBitmap: Bitmap?` parameter (estimated 1-2 days)
- "Run OCR on this photo" CTA in `LegalDocumentPhotosSection` long-press menu
- Hardening pass on existing OCR capture path: `inSampleSize` cap, explicit `bitmap.recycle()`, 30s timeout with Cancel button, explicit `ImageCaptureException` / `CameraInUseException` UI
- Auto-suggest, user reviews and accepts/edits before save, no auto-commit
- Updated FAQ

**Phase 3 - V6 sync integration**

Deliverables:
- `appPrefs.getSyncIncludesPhotosDirect()` + one-time prompt on first Legal Doc save
- `SyncEngineImpl` V6 branch using `encryptToStreamV6`
- WAKE_LOCK + FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC (API 34+) manifest declarations
- Foreground service for syncs over 10 MiB (configurable threshold)
- WakeLock acquisition with `finally` release
- Lift V5-sync refusal for LEGAL_DOCUMENT-with-photos
- FAQ entries: file size, doze behavior, last-writer-wins conflicts
- Lift SK+sync gate to handle photos+sync combination

**Phase 4 - V7 chunked AEAD design doc (no cipher migrator)**

The R2 critic flagged that the original Phase 5 cipher migrator was a no-op until a v4 AAD actually lands (which is not currently planned). When v4 is designed (e.g. for chunked AEAD or a new AEAD primitive), the migrator pattern follows from `CredentialCipherMigrator` and the existing `cipher_version` column on `PhotoEntity`.

Deliverable: V7 envelope design doc only. No code.

## 15. Open decisions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Feature flag rollout | Build-time const. Default OFF in v1.x, ON in v2.0. Phase 1 (now backup-inclusive) flips it ON. |
| Q2 | V6 sync foreground-service threshold | 10 MiB. Measure on low-end devices in Phase 3. |
| Q3 | Biometric re-prompt to view photo | NO for v1. |
| Q4 | Gallery picker support | YES later; mandatory EXIF re-encode pipeline. |
| Q5 | `PHOTO_MAX_BYTES` | 16 MiB sidecar (B5 makes this no longer a CursorWindow issue). 3 MiB soft warning to user. |
| Q6 | Soft-deleted Legal Docs in V6 backup | YES include photos. Matches existing text-only behavior. |
| Q7 | SK + photos same V6 envelope | YES. Lift sync-refuses-SK restriction in Phase 3. |
| Q8 | Type-change AWAY from LEGAL_DOCUMENT | Explicit confirm dialog + same-txn delete of photos + sidecar files. |
| Q9 | Phase 1 rollout | ENG-internal first, security audit before TestFlight. Now safer because V6 backup is in Phase 1. |
| Q10 | FRONT/BACK localisation | User labels via strings.xml. DB enum stays English. |
| Q11 | Plaintext zero in finally | YES (best-effort; documented as TV13 "native/Skia/CameraX copies persist outside our control"). |
| Q12 | List-row thumbnail | NO. Generic doc icon. |
| Q13 | Drop SHA256_PLAIN from manifest | YES. Redundant with per-row GCM tag + envelope-bound AAD. Saves 32 bytes/row. |
| Q14 | `CredentialDao.upsert` resolution | Add `upsertPreservingPhotos`; route all LEGAL_DOCUMENT writes through it via a dispatch helper. Non-Legal-Doc writes untouched. |
| Q15 | KdfMigrator collision | Reserve DB version 16 NOW for Legal Docs. If KdfMigrator lands first, it gets 17. Synchronized in #eng. |
| Q16 | Phase 1 + 2 combined ship vs separate | Combined. R2 conclusively showed Phase 1 alone is unsafe. |
| Q17 | Photo viewer: Dialog vs route destination | Pending. Dialog's separate window does NOT inherit FLAG_SECURE - either move off Dialog OR extend `SecureWindowController` to plumb the flag via `DialogWindowProvider`. Recommend: move to a route destination for v1. |
| Q18 | Edge-to-edge photo viewer | Pending. Yes possible; would extend `SecureWindowController` plumbing and add inset-aware overlay for viewer controls. Recommend: ship with platform-default insets in v1, edge-to-edge in a follow-up after Q17 is resolved. |

## 16. Threat model summary

| Vector | Mitigation |
|---|---|
| TV1 EXIF GPS leakage | Bitmap pipeline hard-strips EXIF via `Bitmap.compress(JPEG)` re-encode. `PhotoExifStripTest` asserts FF E1 marker absent. |
| TV2 Bitmap residency | `DisposableEffect.onDispose { recycle() }`. No Coil/AsyncImage cache. Viewer collects `keyHolder.isUnlocked` and wipes on lock. |
| TV3 Recents thumbnail | `SecureWindowController.forceSecure` forces FLAG_SECURE during viewer/capture. |
| TV4 MediaStore exposure | CameraX in-process capture via `OnImageCapturedCallback`. No FileProvider URI, no MediaStore touch. |
| TV5 Vendor temp-file spill | Documented residual; RootDetector + one-time advisory on first Legal Doc capture. |
| TV6 Accessibility tree leak | `Modifier.semantics { contentDescription = "<label>" }`. MRZ/Document Number TextFields use `clearAndSetSemantics`. |
| TV7 Autofill scrape | `Modifier.semantics { contentType = ContentType.Unknown }`. `AutofillIgnoresPhotosTest` walks `AssistStructure`. |
| TV8 Lockscreen notifications | New lint rule `NoNotificationsInLegalDocs`. |
| TV9 Car/Wear projection | `CarAppService` and `WearListenerService` deliberately not declared. Comment in manifest. |
| TV10 ML Kit logcat | Release-build R8 rules strip `android.util.Log`. CI smoke test greps release APK. |
| TV11 Transient plaintext during restore | Bounded to single-blob window (one 16 MiB JPEG max), plaintext `.fill(0)` in `finally`. |
| TV12 Cross-envelope blob substitution | Per-blob AAD includes `envelope_salt_hex`. Cross-envelope graft fails GCM tag. |
| TV13 Native/Skia/CameraX byte copies | Documented as out-of-process residual; `bytes.fill(0)` is best-effort on the heap buffer we own. |

## 17. Edge cases (top-impact)

Full enumeration in `/tmp/legaldocs-plan/edge_cases.json` (103 scenarios from the multi-agent workflow). High-impact rulings:

| # | Scenario | Decision |
|---|---|---|
| 1-4 | Camera permission denied / hardware unavailable | Explicit error UI, Settings deep-link CTA, no silent revert |
| 11, 24 | 4K bitmap OOM | `inSampleSize` cap at 2048px BEFORE `image.toBitmap()` |
| 18 | OCR over 10s | 30s timeout, Cancel button during Processing |
| 20 | Date locale ambiguity | OCR stores raw string only; user types final format |
| 21 | MRZ | Stored as raw text in two MRZ Line 1 / Line 2 fields; no checksum validation in V1 |
| 23 | App killed mid-save | `withTransaction` + draft photo persistence + 7-day orphan sweep |
| 25 | Storage full | `SaveLegalDocumentUseCase` surfaces `AppResult.Error` from IOException via existing toast |
| 26 | Storage budget | `MAX_LEGAL_DOCS_PER_VAULT = 50` (soft warn-then-allow); `MAX_PHOTOS_PER_CREDENTIAL = 2` (hard structural cap) |
| 28, 32 | Zero-photo Legal Doc | Allowed. Title + text fields only is a valid degenerate state |
| 29, 30 | Asymmetric front/back | Allowed. Empty slot renders as placeholder |
| 31 | Identical front/back | `sha256` dedupe in `SaveLegalDocumentUseCase` confirmation dialog |
| 33, 43, 44 | Orphan blobs / hard-delete cascade | Explicit `deleteForCredential` calls in 5 use cases + orphan-file sweep on unlock |
| 34 | Blob missing on present credential | Viewer renders "Photo missing, retake?" placeholder with capture CTA |
| 35 | Corrupt ciphertext | GCM tag failure surfaces as per-side error state. Credential's other fields still load. |
| 38 | Photo replace | UPSERT generates fresh IV via `Cipher.doFinal` self-IV-gen |
| 40-41 | Type change away from LEGAL_DOCUMENT | `TypeChangeAwayFromLegalDocUseCase` with confirm dialog + same-txn delete |
| 58 | FLAG_SECURE | Forced ON via `SecureWindowController` during viewer/capture |
| 65 | `ocrTargetsFor` exhaustiveness | LEGAL_DOCUMENT branch added in Phase 2 |
| 80 | IV reuse | NEVER pass IV into `crypto.encrypt`; only the self-IV-gen overload is used |
| 92 | Duplicate PK | Composite PK `(credential_id, side)` rejects duplicates |
| 96-98 | FAQ / search index | New entries: "Legal Documents", "Photo storage", "EXIF stripping" |
| 101 | CSV export | Omits photos with Toast warning. V6 encrypted backup is the only photo round-trip |
| 102 | Third-party import | Imports keep their inferred type; no auto-promotion to LEGAL_DOCUMENT |

## Reference materials

Multi-agent workflow artifacts (architect proposals, critiques, edge cases, threats):
- `/tmp/legaldocs-plan/proposal_*_*.json` (5 originals + 2 revised)
- `/tmp/legaldocs-plan/critique_*.json` (10 round-1 critiques)
- `/tmp/legaldocs-plan/edge_cases.json` (103 scenarios)
- `/tmp/legaldocs-plan/threats.json` (25 vectors)
- `/tmp/legaldocs-plan/synthesis.json` (v1 first-pass)
- `/tmp/legaldocs-plan/critic.json` (v1 final completeness audit)
- Round 2 critic output at the wf_c094c7cd-009 workflow transcript directory

Process history:
- v1 plan: 6-architect / 10-critic workflow + edge-case sweep + threat sweep + synthesizer + completeness audit
- R2 critic: 6 independent lens critiques (cryptography, storage, UX, production-readiness, edge-case completeness, cross-domain consistency) + synthesizer rolled up 13 blockers
- v2 plan (this document): applies all 13 R2 blockers + key revisions in one pass

Ready to implement after a Phase 1 PR design review against this plan.
