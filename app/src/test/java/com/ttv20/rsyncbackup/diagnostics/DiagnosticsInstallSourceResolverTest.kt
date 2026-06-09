package com.ttv20.rsyncbackup.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsInstallSourceResolverTest {
    @Test
    fun googlePlayInstallSourceIsNormalized() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames(
            installingPackageName = "com.android.vending",
        )

        assertEquals("google_play", source.source)
        assertEquals("com.android.vending", source.packageName)
    }

    @Test
    fun knownStoreBeatsSystemPackageInstaller() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames(
            installingPackageName = "com.google.android.packageinstaller",
            initiatingPackageName = "org.fdroid.fdroid",
        )

        assertEquals("fdroid", source.source)
        assertEquals("org.fdroid.fdroid", source.packageName)
    }

    @Test
    fun packageInstallerIsNormalized() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames(
            installingPackageName = "com.google.android.packageinstaller",
        )

        assertEquals("package_installer", source.source)
        assertEquals("com.google.android.packageinstaller", source.packageName)
    }

    @Test
    fun missingPackageIsGroupedAsUnknownOrAdb() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames()

        assertEquals("unknown_or_adb", source.source)
        assertNull(source.packageName)
    }

    @Test
    fun unknownPackageDoesNotExposePackageName() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames(
            installingPackageName = "com.private.installer",
        )

        assertEquals("other", source.source)
        assertNull(source.packageName)
    }

    @Test
    fun invalidPackageNameIsIgnored() {
        val source = DiagnosticsInstallSourceResolver.fromPackageNames(
            installingPackageName = "host=nas.local",
            initiatingPackageName = "/storage/emulated/0/app.apk",
        )

        assertEquals("unknown_or_adb", source.source)
        assertNull(source.packageName)
    }
}
