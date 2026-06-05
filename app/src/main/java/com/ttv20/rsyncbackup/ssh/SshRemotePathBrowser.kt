package com.ttv20.rsyncbackup.ssh

import android.content.Context
import com.ttv20.rsyncbackup.backup.NativeBinaryManager
import com.ttv20.rsyncbackup.backup.RsyncCommandBuilder
import com.ttv20.rsyncbackup.backup.SshRuntimeFiles
import com.ttv20.rsyncbackup.backup.TailscaleTcpForward
import com.ttv20.rsyncbackup.model.AppState
import com.ttv20.rsyncbackup.model.Route
import com.ttv20.rsyncbackup.model.TargetRecord
import com.ttv20.rsyncbackup.storage.SecretStore
import com.ttv20.rsyncbackup.tailscale.TailscaleManager
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class SshRemotePathEntry(
    val name: String,
    val path: String,
)

data class SshRemotePathListing(
    val requestedPath: String,
    val resolvedPath: String,
    val parentPath: String?,
    val entries: List<SshRemotePathEntry>,
    val route: Route,
    val host: String,
)

class SshRemotePathBrowser(
    private val context: Context,
    private val secretStore: SecretStore,
    private val nativeBinaryManager: NativeBinaryManager = NativeBinaryManager(context),
) {
    private val tailscaleManager = TailscaleManager(context, secretStore, nativeBinaryManager)

    fun openSession(
        state: AppState,
        target: TargetRecord,
        routes: List<Route>,
    ): SshRemotePathBrowserSession {
        val distinctRoutes = routes.distinct()
        require(distinctRoutes.isNotEmpty()) { "No SSH route is configured for this target" }

        val nativeInstall = nativeBinaryManager.ensureInstalled()
        require(nativeInstall.isComplete) {
            "Missing native binaries: ${nativeInstall.missing.joinToString()}"
        }

        val privateKeyAlias = state.sshKeySettings.privateKeySecretAlias
        val privateKeyBytes = privateKeyAlias?.let(secretStore::get)
            ?: error("No SSH private key is configured")
        val passphraseBytes = state.sshKeySettings.passphraseSecretAlias?.let { alias ->
            secretStore.get(alias) ?: error("SSH private key passphrase is missing")
        }
        val knownHostsText = SshRuntimeFiles.knownHostsText(target, state.trustedHostFingerprints)
        require(knownHostsText.isNotBlank()) { "No trusted SSH host key is configured for ${target.name}" }

        val workDir = File(context.cacheDir, "ssh-browser-${System.nanoTime()}").also { it.mkdirs() }
        val inputs = writeCommandInputs(
            workDir = workDir,
            privateKeyBytes = privateKeyBytes,
            passphraseBytes = passphraseBytes,
            knownHostsText = knownHostsText,
            sshPath = nativeInstall.paths.ssh,
            tsnetHelperPath = nativeInstall.paths.tsnetHelper,
        )
        var lastFailure: String? = null
        try {
            for (route in distinctRoutes) {
                val targetHost = runCatching { route.targetHost(target) }
                    .onFailure { error ->
                        lastFailure = "Route ${route.label()} is not configured: ${error.message}"
                    }
                    .getOrNull()
                    ?: continue
                runCatching {
                    connectRoute(
                        state = state,
                        target = target,
                        route = route,
                        targetHost = targetHost,
                        workDir = workDir,
                        inputs = inputs,
                    )
                }.onSuccess {
                    return it
                }.onFailure { error ->
                    lastFailure = "${route.label()} route to $targetHost failed: ${error.message}"
                }
            }
            error("Could not browse ${target.name}: ${lastFailure ?: "no usable route"}")
        } catch (error: Throwable) {
            workDir.deleteRecursively()
            throw error
        }
    }

    private fun connectRoute(
        state: AppState,
        target: TargetRecord,
        route: Route,
        targetHost: String,
        workDir: File,
        inputs: BrowserCommandInputs,
    ): SshRemotePathBrowserSession {
        var forward: TailscaleTcpForward? = null
        var session: SshRemotePathBrowserSession? = null
        var tailscaleStateAlias: String? = null
        var sessionCreated = false
        return try {
            val connectHost: String
            val connectPort: Int
            if (route == Route.TAILSCALE) {
                val stateAlias = requireNotNull(state.tailscale.stateSecretAlias) { "Tailscale is not configured" }
                require(state.tailscale.isConfigured) { "Tailscale is not configured" }
                val stateDir = File(context.filesDir, "tailscale-state")
                tailscaleManager.restoreState(stateAlias)
                tailscaleStateAlias = stateAlias
                forward = TailscaleTcpForward.start(
                    tsnetHelperPath = inputs.tsnetHelperPath,
                    filesDir = context.filesDir,
                    tailscaleStateDir = stateDir,
                    tailscaleNodeName = state.tailscale.nodeName,
                    targetHost = targetHost,
                    targetPort = target.port,
                )
                connectHost = forward.host
                connectPort = forward.port
            } else {
                connectHost = targetHost
                connectPort = target.port
            }

            val controlPath = File(workDir, "control-${route.name.lowercase(Locale.US)}").absolutePath
            val masterProcess = startSshMasterProcess(
                target = target,
                targetHost = targetHost,
                connectHost = connectHost,
                connectPort = connectPort,
                controlPath = controlPath,
                inputs = inputs,
            )
            session = SshRemotePathBrowserSession(
                context = context,
                masterProcess = masterProcess,
                sshPath = inputs.sshPath,
                controlPath = controlPath,
                target = target,
                targetHost = targetHost,
                connectHost = connectHost,
                connectPort = connectPort,
                knownHostsPath = inputs.knownHostsPath,
                sshKeyPath = inputs.sshKeyPath,
                askpassPath = inputs.askpassPath,
                forward = forward,
                workDir = workDir,
                tailscaleManager = tailscaleManager,
                tailscaleStateAlias = tailscaleStateAlias,
                route = route,
                host = targetHost,
            )
            session.initialize()
            session.also {
                sessionCreated = true
                session = null
                forward = null
            }
        } finally {
            session?.closeAfterFailedOpen()
            forward?.closeQuietly()
            if (route == Route.TAILSCALE && !sessionCreated) {
                tailscaleManager.clearPlainState()
            }
        }
    }

    private fun startSshMasterProcess(
        target: TargetRecord,
        targetHost: String,
        connectHost: String,
        connectPort: Int,
        controlPath: String,
        inputs: BrowserCommandInputs,
    ): Process {
        val command = sshBaseCommand(
            sshPath = inputs.sshPath,
            target = target,
            targetHost = targetHost,
            connectHost = connectHost,
            connectPort = connectPort,
            knownHostsPath = inputs.knownHostsPath,
            sshKeyPath = inputs.sshKeyPath,
            askpassPath = inputs.askpassPath,
        ).toMutableList()
        command += listOf(
            "-N",
            "-M",
            "-S",
            controlPath,
            "${target.user}@$connectHost",
        )

        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        NativeBinaryManager.configureProcessEnvironment(processBuilder, context.filesDir)
        inputs.askpassPath?.let { configureAskpass(processBuilder, it) }
        return processBuilder.start()
    }

    private data class BrowserCommandInputs(
        val knownHostsPath: String,
        val sshKeyPath: String,
        val askpassPath: String?,
        val sshPath: String,
        val tsnetHelperPath: String,
    )

    private fun writeCommandInputs(
        workDir: File,
        privateKeyBytes: ByteArray,
        passphraseBytes: ByteArray?,
        knownHostsText: String,
        sshPath: String,
        tsnetHelperPath: String,
    ): BrowserCommandInputs {
        val knownHosts = File(workDir, "known_hosts").also {
            it.writeText(knownHostsText)
            it.privateFilePermissions()
        }
        val sshKey = File(workDir, "id_ed25519").also {
            it.writeText(SshRuntimeFiles.privateKeyText(privateKeyBytes))
            it.privateFilePermissions()
        }
        val askpass = passphraseBytes?.let { bytes ->
            val passphrase = File(workDir, "id_ed25519.passphrase").also {
                it.writeBytes(bytes)
                it.privateFilePermissions()
            }
            File(workDir, "ssh-askpass").also {
                it.writeText("#!/system/bin/sh\ncat ${RsyncCommandBuilder.shellQuote(passphrase.absolutePath)}\n")
                it.privateFilePermissions(executable = true)
            }
        }
        return BrowserCommandInputs(
            knownHostsPath = knownHosts.absolutePath,
            sshKeyPath = sshKey.absolutePath,
            askpassPath = askpass?.absolutePath,
            sshPath = sshPath,
            tsnetHelperPath = tsnetHelperPath,
        )
    }

    private fun File.privateFilePermissions(executable: Boolean = false) {
        setReadable(false, false)
        setWritable(false, false)
        setExecutable(false, false)
        setReadable(true, true)
        setWritable(true, true)
        if (executable) setExecutable(true, true)
    }
}

class SshRemotePathBrowserSession internal constructor(
    private val context: Context,
    private val masterProcess: Process,
    private val sshPath: String,
    private val controlPath: String,
    private val target: TargetRecord,
    private val targetHost: String,
    private val connectHost: String,
    private val connectPort: Int,
    private val knownHostsPath: String,
    private val sshKeyPath: String,
    private val askpassPath: String?,
    private val forward: TailscaleTcpForward?,
    private val workDir: File,
    private val tailscaleManager: TailscaleManager,
    private val tailscaleStateAlias: String?,
    private val route: Route,
    private val host: String,
) : Closeable {
    private val lock = Any()
    private val masterOutput = Collections.synchronizedList(mutableListOf<String>())
    private val masterOutputThread = thread(name = "ssh-browser-master-output", isDaemon = true) {
        runCatching {
            masterProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { masterOutput += it }
            }
        }
    }
    private var closed = false
    private var homePath = "/"
    private var currentPath = "/"

    internal fun initialize() {
        synchronized(lock) {
            waitForControlSocket()
            val output = runRemoteScript("pwd -P\n", emptyList())
            homePath = output.firstOrNull { it.isNotBlank() }
                ?: error(processFailureMessage("SSH did not report the remote home path", output))
            currentPath = homePath
        }
    }

    fun listDirectories(remotePath: String, showHidden: Boolean): SshRemotePathListing =
        synchronized(lock) {
            check(!closed) { "SSH browser session is closed" }
            val requestedPath = remotePath.trim().ifBlank { "~" }
            val output = runRemoteScript(
                script = LIST_DIRECTORIES_SCRIPT,
                args = listOf(requestedPath, if (showHidden) "1" else "0", currentPath),
            )
            val remoteError = output.firstOrNull { it.startsWith(ERROR_PREFIX) }
            require(remoteError == null) {
                remoteError?.removePrefix(ERROR_PREFIX)?.ifBlank { "Could not open remote path: $requestedPath" }
                    ?: "Could not open remote path: $requestedPath"
            }
            val resolvedPath = output.firstOrNull { it.startsWith(PWD_PREFIX) }
                ?.removePrefix(PWD_PREFIX)
                ?.takeIf { it.isNotBlank() }
                ?: error(processFailureMessage("SSH did not report the current remote path", output))
            currentPath = resolvedPath
            val entries = output
                .mapNotNull { directoryEntryFromRemoteLine(it, resolvedPath) }
                .let { visibleEntries(it, showHidden) }
            SshRemotePathListing(
                requestedPath = requestedPath,
                resolvedPath = resolvedPath,
                parentPath = parentPath(resolvedPath),
                entries = entries,
                route = route,
                host = host,
            )
        }

    override fun close() {
        close(persistTailscaleState = true)
    }

    internal fun closeAfterFailedOpen() {
        close(persistTailscaleState = false)
    }

    private fun close(persistTailscaleState: Boolean) {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching {
                val exitCommand = sshCommand(optionsBeforeDestination = listOf("-O", "exit"))
                val processBuilder = ProcessBuilder(exitCommand)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)
                NativeBinaryManager.configureProcessEnvironment(processBuilder, context.filesDir)
                askpassPath?.let { configureAskpass(processBuilder, it) }
                processBuilder.start().waitFor(2, TimeUnit.SECONDS)
            }
            masterProcess.destroy()
            if (!masterProcess.waitFor(2, TimeUnit.SECONDS)) {
                masterProcess.destroyForcibly()
            }
            masterOutputThread.join(1_000)
            forward?.closeQuietly()
            tailscaleStateAlias?.let { alias ->
                if (persistTailscaleState) {
                    runCatching { tailscaleManager.persistState(alias) }
                }
                tailscaleManager.clearPlainState()
            }
            workDir.deleteRecursively()
        }
    }

    private fun waitForControlSocket() {
        val socket = File(controlPath)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MASTER_START_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (socket.exists()) return
            if (!masterProcess.isAlive) {
                masterOutputThread.join(1_000)
                error(processFailureMessage("SSH master session exited", emptyList()))
            }
            Thread.sleep(100)
        }
        error(processFailureMessage("SSH master session did not open a control socket", emptyList()))
    }

    private fun runRemoteScript(script: String, args: List<String>): List<String> {
        check(!closed) { "SSH browser session is closed" }
        val command = sshCommand(remoteCommand = listOf("sh", "-s", "--") + args)
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        NativeBinaryManager.configureProcessEnvironment(processBuilder, context.filesDir)
        askpassPath?.let { configureAskpass(processBuilder, it) }

        val process = processBuilder.start()
        val output = Collections.synchronizedList(mutableListOf<String>())
        val readerThread = thread(name = "ssh-browser-command-output", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output += it }
                }
            }
        }
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(script)
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            readerThread.join(1_000)
            error(processFailureMessage("SSH command timed out", output.toList()))
        }
        readerThread.join(1_000)
        val lines = output.toList()
        val exit = process.exitValue()
        if (exit != 0) {
            error(processFailureMessage("SSH command failed with exit $exit", lines))
        }
        return lines
    }

    private fun sshCommand(
        optionsBeforeDestination: List<String> = emptyList(),
        remoteCommand: List<String> = emptyList(),
    ): List<String> =
        sshBaseCommand(
            sshPath = sshPath,
            target = target,
            targetHost = targetHost,
            connectHost = connectHost,
            connectPort = connectPort,
            knownHostsPath = knownHostsPath,
            sshKeyPath = sshKeyPath,
            askpassPath = askpassPath,
        ) + listOf("-S", controlPath) + optionsBeforeDestination + "${target.user}@$connectHost" + remoteCommand

    private fun processFailureMessage(prefix: String, commandOutput: List<String>): String {
        val masterExit = runCatching { masterProcess.exitValue() }.getOrNull()
        val masterTail = synchronized(masterOutput) { masterOutput.takeLast(20) }
        val detail = (commandOutput + masterTail)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        return buildString {
            append(prefix)
            masterExit?.let { append("; master exit $it") }
            if (detail.isNotBlank()) {
                append(": ")
                append(detail)
            }
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 30L
        private const val MASTER_START_TIMEOUT_SECONDS = 20L
        private const val PWD_PREFIX = "__ANDROID_RSYNC_BACKUP_PWD__"
        private const val DIR_PREFIX = "__ANDROID_RSYNC_BACKUP_DIR__"
        private const val ERROR_PREFIX = "__ANDROID_RSYNC_BACKUP_ERROR__"

        private const val LIST_DIRECTORIES_SCRIPT = """
set -u
requested=$1
show_hidden=$2
base=$3
home=$(pwd -P)
case "${'$'}requested" in
  "~") target_path=${'$'}home ;;
  "~/"*) target_path=${'$'}home/${'$'}{requested#~/} ;;
  /*) target_path=${'$'}requested ;;
  *) target_path=${'$'}{base%/}/${'$'}requested ;;
esac
if [ ! -d "${'$'}target_path" ]; then
  echo "__ANDROID_RSYNC_BACKUP_ERROR__Remote path is not a directory: ${'$'}target_path"
  exit 0
fi
if ! cd "${'$'}target_path" 2>/dev/null; then
  echo "__ANDROID_RSYNC_BACKUP_ERROR__You don't have permission to open: ${'$'}target_path"
  exit 0
fi
if [ ! -r . ]; then
  echo "__ANDROID_RSYNC_BACKUP_ERROR__You don't have permission to list: ${'$'}target_path"
  exit 0
fi
resolved=$(pwd -P)
echo "__ANDROID_RSYNC_BACKUP_PWD__${'$'}resolved"
if [ "${'$'}show_hidden" = "1" ]; then
  set -- "${'$'}resolved"/* "${'$'}resolved"/.[!.]* "${'$'}resolved"/..?*
else
  set -- "${'$'}resolved"/*
fi
for entry do
  [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
  [ -d "${'$'}entry" ] || continue
  name=${'$'}{entry##*/}
  [ "${'$'}name" = "." ] && continue
  [ "${'$'}name" = ".." ] && continue
  echo "__ANDROID_RSYNC_BACKUP_DIR__${'$'}name"
done
"""

        internal fun visibleEntries(
            entries: List<SshRemotePathEntry>,
            showHidden: Boolean,
        ): List<SshRemotePathEntry> =
            entries
                .asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .distinctBy { it.path }
                .sortedBy { it.name.lowercase(Locale.US) }
                .toList()

        internal fun childPath(parent: String, childName: String): String =
            if (parent == "/") "/$childName" else "${parent.trimEnd('/')}/$childName"

        internal fun parentPath(path: String): String? {
            val normalized = path.trimEnd('/').ifBlank { "/" }
            if (normalized == "/") return null
            val separator = normalized.lastIndexOf('/')
            val parent = when {
                separator <= 0 -> "/"
                else -> normalized.substring(0, separator)
            }
            return parent.takeUnless { it == normalized }
        }

        internal fun directoryEntryFromRemoteLine(line: String, parentPath: String): SshRemotePathEntry? {
            val name = line.removePrefix(DIR_PREFIX).takeIf { it.length != line.length } ?: return null
            if (name == "." || name == ".." || name.isBlank()) return null
            return SshRemotePathEntry(name, childPath(parentPath, name))
        }
    }
}

private fun sshBaseCommand(
    sshPath: String,
    target: TargetRecord,
    targetHost: String,
    connectHost: String,
    connectPort: Int,
    knownHostsPath: String,
    sshKeyPath: String,
    askpassPath: String?,
): List<String> {
    val command = mutableListOf(
        sshPath,
        "-F",
        "/dev/null",
        "-p",
        connectPort.toString(),
        "-i",
        sshKeyPath,
        "-o",
        "ConnectTimeout=10",
        "-o",
        "ServerAliveInterval=15",
        "-o",
        "ServerAliveCountMax=2",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        "UserKnownHostsFile=$knownHostsPath",
        "-o",
        "IdentitiesOnly=yes",
        "-o",
        "PubkeyAuthentication=yes",
        "-o",
        "PasswordAuthentication=no",
        "-o",
        "KbdInteractiveAuthentication=no",
        "-o",
        if (askpassPath != null) "BatchMode=no" else "BatchMode=yes",
    )
    if (connectHost != targetHost || connectPort != target.port) {
        command += "-o"
        command += "HostKeyAlias=$targetHost"
    }
    return command
}

private fun configureAskpass(processBuilder: ProcessBuilder, askpassPath: String) {
    val env = processBuilder.environment()
    env["SSH_ASKPASS"] = askpassPath
    env["SSH_ASKPASS_REQUIRE"] = "force"
    env["DISPLAY"] = ":0"
}

private fun Route.targetHost(target: TargetRecord): String =
    when (this) {
        Route.LAN -> target.lanHost.takeIf { it.isNotBlank() } ?: error("LAN route requires a LAN host")
        Route.TAILSCALE -> target.tailscaleHost?.takeIf { it.isNotBlank() }
            ?: error("Tailscale route requires a Tailscale host")
    }

private fun Route.label(): String =
    when (this) {
        Route.LAN -> "LAN"
        Route.TAILSCALE -> "Tailscale"
    }

private fun Closeable.closeQuietly() {
    runCatching { close() }
}
