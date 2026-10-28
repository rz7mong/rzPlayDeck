package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.session.NoSteamLaunch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32

object GameInstaller {
    private val INSTALLER_NAME = Regex("(?i)^(setup|install|installer|.*[-_. ]setup|.*[-_. ]installer)(\\.exe)?$")

    class Record(
        val installerPath: String,
        val targetPath: String,
        val beforeExes: List<String>,
        val startedAt: Long,
    )

    sealed class FinishResult {
        class Installed(val games: List<AddedGames.Game>) : FinishResult()
        object NoGameFound : FinishResult()
        object TargetMissing : FinishResult()
    }

    fun isInstallerExe(file: File): Boolean =
        file.name.endsWith(".exe", ignoreCase = true) &&
            INSTALLER_NAME.matches(file.name.dropLast(4))

    fun targetFor(installer: File, gamesRoot: File?): File {
        val stem = installer.name.dropLast(4).ifEmpty { "game" }
        val root = gamesRoot ?: installer.parentFile ?: File("/storage/emulated/0/Games")
        return File(root, "$stem-installed")
    }

    fun snapshotExes(folder: File): List<String> =
        folder.walkTopDown().filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            .map { it.absolutePath }.sorted().toList()

    @Synchronized
    fun start(context: Context, installer: File, gamesRoot: File? = null): Record? {
        if (!installer.isFile) return null
        if (AddedGames.guestPath(context, installer) == null) {
            AddedGames.bindFolder(context, installer.parentFile ?: return null)
            if (AddedGames.guestPath(context, installer) == null) return null
        }
        val target = targetFor(installer, gamesRoot).apply { mkdirs() }
        val record = Record(installer.absolutePath, target.absolutePath, snapshotExes(target), System.currentTimeMillis())
        put(context, record)
        return record
    }

    fun scope(record: Record): String {
        val id = CRC32().apply { update(record.installerPath.toByteArray()) }.value and 0xFFFFFFFFL
        return if (id == 0L) "1" else id.toString()
    }

    fun installerCommand(context: Context, record: Record): NoSteamLaunch.Command? {
        val installer = File(record.installerPath)
        val guestExe = AddedGames.guestPath(context, installer) ?: return null
        val guestDir = AddedGames.guestPath(context, installer.parentFile ?: return null) ?: return null
        return NoSteamLaunch.Command(
            NoSteamLaunch.PROTON_RUN,
            listOf("--app", scope(record), "--dir", guestDir, "--", guestExe),
            installer.nameWithoutExtension,
        )
    }

    fun finish(context: Context, record: Record): FinishResult {
        val target = File(record.targetPath)
        if (!target.isDirectory) return FinishResult.TargetMissing
        val before = record.beforeExes.toSet()
        val fresh = snapshotExes(target).filter { it !in before }
            .map { File(it) }
            .filter { !AddedGames.skipExe(it.name) }
        if (fresh.isEmpty()) return FinishResult.NoGameFound
        val games = fresh.mapNotNull { exe ->
            runCatching {
                when (val r = AddedGames.addExe(context, exe)) {
                    is AddedGames.AddResult.Added -> r.game
                    is AddedGames.AddResult.Existing -> r.game
                    is AddedGames.AddResult.Switched -> r.game
                    else -> null
                }
            }.getOrNull()
        }
        drop(context, record.installerPath)
        if (games.isNotEmpty()) {
            runCatching {
                com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(context, games) { true }
            }
        }
        return if (games.isEmpty()) FinishResult.NoGameFound else FinishResult.Installed(games)
    }

    private fun file(context: Context) = File(context.filesDir, "game-installs.json")

    @Synchronized
    fun list(context: Context): List<Record> = try {
        val array = JSONArray(file(context).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val installer = o.optString("installer"); val target = o.optString("target")
            if (installer.isEmpty() || target.isEmpty()) null else Record(
                installer, target,
                run {
                    val beforeArr = o.optJSONArray("before")
                    (0 until (beforeArr?.length() ?: 0)).mapNotNull { j -> beforeArr.optString(j) }
                },
                o.optLong("startedAt"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun put(context: Context, record: Record) =
        write(context, list(context).filter { it.installerPath != record.installerPath } + record)

    @Synchronized
    fun drop(context: Context, installerPath: String) =
        write(context, list(context).filter { it.installerPath != installerPath })

    private fun write(context: Context, records: List<Record>) {
        val array = JSONArray()
        records.forEach {
            val before = JSONArray()
            it.beforeExes.forEach { e -> before.put(e) }
            array.put(JSONObject().put("installer", it.installerPath).put("target", it.targetPath).put("before", before).put("startedAt", it.startedAt))
        }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
