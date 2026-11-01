package com.music.bitchord.ko

import android.util.Log
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.StoredBucket
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.YearMonth

/**
 * [KO] listening-import
 *
 * A restore that swaps whole months and can recover from a process death
 * halfway through. Kept out of upstream ListeningStats; the upstream object
 * only exposes the small accessors this needs.
 */
internal object KoListeningStatsImport {

    private const val TAG = "BitChordListening"

    /** Rows waiting to replace a month, and the old month they replace. */
    private const val IMPORTING_SUFFIX = ".importing"
    private const val BACKUP_SUFFIX = ".bak"

    /**
     * Written before the first old month is moved and removed at the commit
     * point. Its presence on the next launch means the import did not finish.
     */
    private const val IMPORT_JOURNAL = "import.journal"

    /**
     * Replaces everything held with [buckets], returning whether the restored
     * files reached disk.
     *
     * A replace rather than a merge, and deliberately so: an import is a restore
     * onto a new device, and two copies of the same month added together would
     * silently double every number on the page with no way to tell afterwards.
     *
     * The new months are written aside first and only then swapped in. Existing
     * months are renamed to `.bak`, never deleted, and the swap is journaled:
     * a failure in this process restores the backups before returning, while a
     * process death between the rename steps is rolled back on the next
     * `ListeningStats.init`. The journal is removed at the commit point, so a
     * death after it leaves only cleanup (the new files are already in place).
     *
     * A month that cannot be parsed is refused by [prepareImport] rather than
     * skipped, because a silent skip here is exactly the "applied" summary
     * disagreeing with what is on disk.
     */
    suspend fun importAll(buckets: List<StoredBucket>): Boolean {
        if (!ListeningStats.koReady()) {
            Log.w(TAG, "Refusing to import listening buckets before the store is open")
            return false
        }
        val prepared = runCatching { prepareImport(buckets) }
            .onFailure { Log.w(TAG, "Refusing an unreadable listening import", it) }
            .getOrElse { return false }

        synchronized(ListeningStats.koLock()) { ListeningStats.koSetImporting(true) }
        try {
            // A flush taken before the flag flipped may still be queued. Joining
            // it before taking the write lock lets it finish instead of
            // cancelling it halfway through, and stops it landing after the
            // restore.
            ListeningStats.koAwaitLastWrite()
            return ListeningStats.koWriteLock().withLock {
                synchronized(ListeningStats.koLock()) {
                    // A previous import may have died after writing its
                    // journal. Roll that one back before this import writes a
                    // new journal over the only record of what it left in
                    // `.bak`; otherwise the next no-journal open would purge
                    // those months as leftovers. If the rollback is incomplete
                    // the old journal is the only thing that can make the next
                    // open retry, so this import must not replace it.
                    if (!recoverInterruptedImport()) {
                        Log.w(TAG, "Refusing to import while an earlier rollback is incomplete")
                        return@synchronized false
                    }
                    val directory = ListeningStats.koDirectory()
                    directory.mkdirs()
                    val staged = mutableListOf<Pair<String, File>>()
                    for ((key, text) in prepared) {
                        val temporary = File(directory, "$key$IMPORTING_SUFFIX")
                        val written = runCatching { temporary.writeText(text) }
                            .onFailure { Log.w(TAG, "Could not stage listening bucket $key", it) }
                            .isSuccess
                        if (!written) {
                            staged.forEach { (_, file) -> file.delete() }
                            temporary.delete()
                            return@synchronized false
                        }
                        staged += key to temporary
                    }

                    // Every new month is now on disk under its staging name.
                    val existing = directory.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".json") }
                        .orEmpty()
                    val journal = File(directory, IMPORT_JOURNAL)
                    val journalText = buildString {
                        existing.forEach { appendLine("O ${it.name}") }
                        prepared.forEach { (key, _) -> appendLine("N $key.json") }
                    }
                    val journalWritten = runCatching { journal.writeText(journalText) }.isSuccess
                    if (!journalWritten) {
                        Log.w(TAG, "Could not write the listening import journal")
                        staged.forEach { (_, file) -> file.delete() }
                        return@synchronized false
                    }

                    // Move the old months aside first. Nothing is deleted until
                    // every new month is in place.
                    val backups = mutableListOf<Pair<File, File>>()
                    var backedUp = true
                    for (file in existing) {
                        val backup = File(directory, file.name + BACKUP_SUFFIX)
                        if (file.renameTo(backup)) {
                            backups += file to backup
                        } else {
                            Log.w(TAG, "Could not back up listening bucket ${file.name}")
                            backedUp = false
                            break
                        }
                    }
                    if (!backedUp) {
                        staged.forEach { (_, file) -> file.delete() }
                        // Only drop the journal once every old month is back.
                        // If a rename back fails, leaving it lets init retry;
                        // deleting it would let the next open purge the `.bak`
                        // as a leftover and lose that month for good.
                        if (restoreBackups(backups)) {
                            journal.delete()
                        } else {
                            Log.w(TAG, "Could not restore every listening backup; keeping the journal")
                        }
                        return@synchronized false
                    }

                    val applied = mutableListOf<File>()
                    var committed = true
                    for ((key, temporary) in staged) {
                        val target = File(directory, "$key.json")
                        if (temporary.renameTo(target) ||
                            (target.delete() && temporary.renameTo(target))
                        ) {
                            applied += target
                        } else {
                            Log.w(TAG, "Could not commit listening bucket $key")
                            committed = false
                            break
                        }
                    }
                    if (!committed) {
                        // Put the old months back exactly as they were. The
                        // journal stays if any rename back fails, so the same
                        // rollback runs again on the next init instead of
                        // losing the remaining `.bak` files.
                        staged.forEach { (_, file) -> file.delete() }
                        if (restoreBackups(backups, applied)) {
                            journal.delete()
                        } else {
                            Log.w(TAG, "Could not restore every listening backup; keeping the journal")
                        }
                        return@synchronized false
                    }

                    // Commit point. Once the journal is gone the new folder is
                    // the answer; only backup cleanup is left.
                    journal.delete()
                    backups.forEach { (_, backup) -> backup.delete() }
                    ListeningStats.koFinishImport()
                    true
                }
            }
        } finally {
            synchronized(ListeningStats.koLock()) { ListeningStats.koSetImporting(false) }
        }
    }

    /**
     * Puts every old month named by [backups] back where it was.
     *
     * Returns false when any target could not be cleared or any `.bak` could
     * not be renamed back. The caller must then keep the import journal so
     * [recoverInterruptedImport] retries on the next open; dropping it would
     * make that recovery treat the surviving `.bak` files as leftovers.
     */
    fun restoreBackups(
        backups: List<Pair<File, File>>,
        applied: List<File> = emptyList(),
    ): Boolean {
        var restored = true
        // A month committed by this import has to be gone before its old copy
        // can take that name. A failed delete is as much a rollback failure as
        // a failed rename: leaving it out of [restored] would let the caller
        // delete the journal and strand the only remaining copy in `.bak`.
        applied.forEach { file ->
            if (file.exists() && !file.delete()) restored = false
        }
        for ((original, backup) in backups) {
            if (!backup.isFile) continue
            if (original.exists() && !original.delete()) {
                restored = false
                continue
            }
            if (!backup.renameTo(original)) {
                Log.w(TAG, "Could not restore listening bucket ${original.name}")
                restored = false
            }
        }
        return restored
    }

    /**
     * Rolls back an import that died mid-swap and reports whether the folder is
     * whole again.
     *
     * Callers that are about to write a new journal must abort when this
     * returns false: overwriting the old journal would discard the only record
     * of which months survive as `.bak`, and the next open would purge them.
     */
    fun recoverInterruptedImport(): Boolean {
        val directory = ListeningStats.koDirectory()
        val journal = File(directory, IMPORT_JOURNAL)
        if (!journal.exists()) {
            // A completed import may have died before deleting its staging
            // files or `.bak` copies. Neither is a month any more.
            directory.listFiles()?.forEach { file ->
                if (file.isFile &&
                    (file.name.endsWith(IMPORTING_SUFFIX) || file.name.endsWith(BACKUP_SUFFIX))
                ) {
                    file.delete()
                }
            }
            return true
        }
        val oldNames = mutableSetOf<String>()
        val newNames = mutableSetOf<String>()
        runCatching { journal.readLines() }.getOrDefault(emptyList()).forEach { line ->
            when {
                line.startsWith("O ") -> oldNames += line.removePrefix("O ")
                line.startsWith("N ") -> newNames += line.removePrefix("N ")
            }
        }
        // Only delete a `.json` that is a backup target or a month the file
        // brought. An old month not yet moved aside has no `.bak` and must stay.
        var rolledBack = true
        newNames.forEach { name ->
            val target = File(directory, name)
            val backup = File(directory, name + BACKUP_SUFFIX)
            if (target.isFile && (name !in oldNames || backup.isFile)) {
                if (!target.delete()) rolledBack = false
            }
        }
        oldNames.forEach { name ->
            val backup = File(directory, name + BACKUP_SUFFIX)
            if (backup.isFile) {
                val target = File(directory, name)
                if (target.exists() && !target.delete()) rolledBack = false
                if (!backup.renameTo(target)) rolledBack = false
            }
        }
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(IMPORTING_SUFFIX)) file.delete()
        }
        if (!rolledBack) {
            // A rename that failed now may succeed next time (a full volume, a
            // file briefly held open). Keeping the journal is what stops the
            // next open from treating the surviving `.bak` files as leftovers.
            Log.w(TAG, "Could not fully roll back an interrupted listening import; retrying next open")
            return false
        }
        journal.delete()
        Log.w(TAG, "Rolled back an interrupted listening import")
        return true
    }

    /**
     * Serialises a whole import before [importAll] touches the directory.
     *
     * Kept separate and pure so the refusal path — a month this build cannot
     * parse, or two rows for one month that would overwrite each other — can be
     * tested without an Android context or a writable files directory.
     */
    fun prepareImport(buckets: List<StoredBucket>): List<Pair<String, String>> {
        val seen = HashSet<String>(buckets.size)
        return buckets.map { bucket ->
            val key = runCatching { YearMonth.parse(bucket.month).toString() }
                .getOrElse { throw IllegalArgumentException("잘못된 청취 달: ${bucket.month}", it) }
            require(seen.add(key)) { "같은 청취 달이 두 번 있습니다: $key" }
            val text = runCatching {
                ListeningStats.koJson().encodeToString(StoredBucket.serializer(), bucket)
            }.getOrElse { throw IllegalArgumentException("청취 기록을 저장할 수 없습니다: $key", it) }
            key to text
        }
    }
}
