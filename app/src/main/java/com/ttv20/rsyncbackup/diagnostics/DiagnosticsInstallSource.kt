package com.ttv20.rsyncbackup.diagnostics

import android.content.Context
import android.os.Build

internal data class DiagnosticsInstallSource(
    val source: String,
    val packageName: String? = null,
)

internal object DiagnosticsInstallSourceResolver {
    private const val UNKNOWN_OR_ADB = "unknown_or_adb"
    private const val OTHER = "other"
    private const val PACKAGE_INSTALLER = "package_installer"

    private val packageNamePattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
    private val knownSources = mapOf(
        "app.accrescent.client" to "accrescent",
        "cm.aptoide.pt" to "aptoide",
        "com.amazon.venezia" to "amazon_appstore",
        "com.android.vending" to "google_play",
        "com.apkpure.aegon" to "apkpure",
        "com.aurora.store" to "aurora_store",
        "com.github.android" to "github_app",
        "com.huawei.appmarket" to "huawei_appgallery",
        "com.looker.droidify" to "droid_ify",
        "com.machiav3lli.fdroid" to "neo_store",
        "com.sec.android.app.samsungapps" to "samsung_galaxy_store",
        "com.uptodown" to "uptodown",
        "com.xiaomi.mipicks" to "xiaomi_getapps",
        "dev.imranr.obtainium" to "obtainium",
        "org.fdroid.basic" to "fdroid",
        "org.fdroid.fdroid" to "fdroid",
    )
    private val knownPackageInstallers = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    fun resolve(context: Context): DiagnosticsInstallSource =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolveModern(context)
        } else {
            resolveLegacy(context)
        }

    fun fromPackageNames(
        installingPackageName: String? = null,
        initiatingPackageName: String? = null,
        originatingPackageName: String? = null,
    ): DiagnosticsInstallSource {
        val packageNames = listOf(
            installingPackageName,
            initiatingPackageName,
            originatingPackageName,
        ).mapNotNull(::safePackageName)

        val knownStorePackage = packageNames.firstOrNull { it in knownSources }
        if (knownStorePackage != null) {
            return DiagnosticsInstallSource(
                source = knownSources.getValue(knownStorePackage),
                packageName = knownStorePackage,
            )
        }

        val packageInstaller = packageNames.firstOrNull(::isPackageInstaller)
        if (packageInstaller != null) {
            return DiagnosticsInstallSource(
                source = PACKAGE_INSTALLER,
                packageName = packageInstaller.takeIf { it in knownPackageInstallers },
            )
        }

        return if (packageNames.isEmpty()) {
            DiagnosticsInstallSource(source = UNKNOWN_OR_ADB)
        } else {
            DiagnosticsInstallSource(source = OTHER)
        }
    }

    private fun resolveModern(context: Context): DiagnosticsInstallSource =
        runCatching {
            val sourceInfo = context.packageManager.getInstallSourceInfo(context.packageName)
            fromPackageNames(
                installingPackageName = sourceInfo.installingPackageName,
                initiatingPackageName = sourceInfo.initiatingPackageName,
                originatingPackageName = sourceInfo.originatingPackageName,
            )
        }.getOrElse {
            resolveLegacy(context)
        }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(context: Context): DiagnosticsInstallSource =
        runCatching {
            fromPackageNames(
                installingPackageName = context.packageManager.getInstallerPackageName(context.packageName),
            )
        }.getOrDefault(DiagnosticsInstallSource(source = UNKNOWN_OR_ADB))

    private fun safePackageName(value: String?): String? {
        val packageName = value?.trim().orEmpty()
        return packageName.takeIf { it.matches(packageNamePattern) }
    }

    private fun isPackageInstaller(packageName: String): Boolean =
        packageName in knownPackageInstallers ||
            packageName.endsWith(".packageinstaller") ||
            packageName.endsWith(".packageinstaller2")
}
