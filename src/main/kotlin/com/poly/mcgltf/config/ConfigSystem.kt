package com.poly.mcgltf.config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
@Serializable
enum class PerformanceProfile {
    LOW, MEDIUM, HIGH, ULTRA;
    fun isEnabled(subsystem: String): Boolean = when (this) {
        LOW -> subsystem in LOW_ENABLED
        MEDIUM -> subsystem in MEDIUM_ENABLED
        HIGH -> true
        ULTRA -> true
    }
    companion object {
        private val LOW_ENABLED = setOf("frustumCulling")
        private val MEDIUM_ENABLED = setOf("frustumCulling", "gpuInstancing", "lodSystem", "shadowProjection")
    }
}
@Serializable
enum class LightingMode { PER_MODEL, PER_VERTEX }
@Serializable
data class Config(
    val enabled: Boolean = true,
    val performanceProfile: PerformanceProfile = PerformanceProfile.HIGH,
    val gpuInstancing: Boolean? = null,
    val lodSystem: Boolean? = null,
    val frustumCulling: Boolean? = null,
    val obbCollision: Boolean? = null,
    val debugHud: Boolean? = null,
    val clothSimulation: Boolean? = null,
    val shadowProjection: Boolean? = null,
    val devMode: Boolean = false,
    val maxCacheSize: Int = 64,
    val lightingMode: LightingMode = LightingMode.PER_MODEL
)

object ConfigSystem {
    private val LOGGER = LoggerFactory.getLogger("MCglTF-Config")
    private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private const val CONFIG_FILE = "mcgltf.json"
    private val listeners = CopyOnWriteArrayList<(Config) -> Unit>()
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private val watching = AtomicBoolean(false)
    private var configDir: Path? = null
    var current: Config = Config()
        private set
    fun load(configDir: Path) {
        this.configDir = configDir
        val configFile = configDir.resolve(CONFIG_FILE)
        if (!Files.exists(configFile)) {
            Files.createDirectories(configDir)
            save(configDir)
            return
        }
        tryLoadFrom(configFile)
    }
    fun save(configDir: Path) {
        val configFile = configDir.resolve(CONFIG_FILE)
        Files.createDirectories(configDir)
        Files.writeString(configFile, JSON.encodeToString(Config.serializer(), current))
    }
    fun isSubsystemEnabled(subsystem: String): Boolean {
        val explicitSwitch = when (subsystem) {
            "gpuInstancing" -> current.gpuInstancing
            "lodSystem" -> current.lodSystem
            "frustumCulling" -> current.frustumCulling
            "obbCollision" -> current.obbCollision
            "debugHud" -> current.debugHud
            "clothSimulation" -> current.clothSimulation
            "shadowProjection" -> current.shadowProjection
            else -> null
        }
        return explicitSwitch ?: current.performanceProfile.isEnabled(subsystem)
    }
    fun addListener(listener: (Config) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Config) -> Unit) { listeners.remove(listener) }
    fun startWatching() {
        val dir = configDir ?: return
        if (watching.getAndSet(true)) return
        val ws = dir.fileSystem.newWatchService()
        watchService = ws
        dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY)
        watchThread = Thread({
            while (watching.get()) {
                try {
                    val key = ws.poll(3, TimeUnit.SECONDS) ?: continue
                    var configChanged = false
                    for (event in key.pollEvents()) {
                        if ((event.context() as? Path)?.toString() == CONFIG_FILE) configChanged = true
                    }
                    key.reset()
                    if (configChanged) {
                        Thread.sleep(500)
                        tryLoadFrom(dir.resolve(CONFIG_FILE))
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    LOGGER.error("""{"event":"config_watch_error","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
                }
            }
        }, "MCglTF-ConfigWatcher").apply { isDaemon = true; start() }
    }
    fun stopWatching() {
        watching.set(false)
        watchThread?.interrupt()
        watchThread = null
        runCatching { watchService?.close() }
        watchService = null
    }
    private fun tryLoadFrom(configFile: Path) {
        try {
            val text = Files.readString(configFile)
            val loaded = JSON.decodeFromString(Config.serializer(), text)
            val old = current
            current = loaded
            if (old != current) listeners.forEach { it(current) }
        } catch (e: SerializationException) {
            LOGGER.error("""{"event":"config_parse_error","file":"$configFile","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
        } catch (e: Exception) {
            LOGGER.error("""{"event":"config_load_error","file":"$configFile","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
        }
    }
}