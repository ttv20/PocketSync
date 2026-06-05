package com.ttv20.rsyncbackup.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshRemotePathBrowserTest {
    @Test
    fun visibleEntriesHideDotFoldersByDefault() {
        val entries = listOf(
            SshRemotePathEntry(".ssh", "/home/user/.ssh"),
            SshRemotePathEntry("Backups", "/home/user/Backups"),
            SshRemotePathEntry(".cache", "/home/user/.cache"),
        )

        assertEquals(
            listOf("Backups"),
            SshRemotePathBrowserSession.visibleEntries(entries, showHidden = false).map { it.name },
        )
        assertEquals(
            listOf(".cache", ".ssh", "Backups"),
            SshRemotePathBrowserSession.visibleEntries(entries, showHidden = true).map { it.name },
        )
    }

    @Test
    fun childPathHandlesRootAndNestedParents() {
        assertEquals("/mnt", SshRemotePathBrowserSession.childPath("/", "mnt"))
        assertEquals("/mnt/backup", SshRemotePathBrowserSession.childPath("/mnt", "backup"))
        assertEquals("/mnt/backup", SshRemotePathBrowserSession.childPath("/mnt/", "backup"))
    }

    @Test
    fun parentPathHandlesRootAndNestedPaths() {
        assertEquals(null, SshRemotePathBrowserSession.parentPath("/"))
        assertEquals("/", SshRemotePathBrowserSession.parentPath("/mnt"))
        assertEquals("/mnt", SshRemotePathBrowserSession.parentPath("/mnt/backup"))
        assertEquals("/mnt", SshRemotePathBrowserSession.parentPath("/mnt/backup/"))
    }

    @Test
    fun directoryEntryFromRemoteLineKeepsDirectoryNamesWithSpaces() {
        val entry = SshRemotePathBrowserSession.directoryEntryFromRemoteLine(
            "__ANDROID_RSYNC_BACKUP_DIR__My Backups",
            "/mnt",
        )

        assertEquals(SshRemotePathEntry("My Backups", "/mnt/My Backups"), entry)
    }

    @Test
    fun directoryEntryFromRemoteLineIgnoresOtherLinesAndDotEntries() {
        assertEquals(
            null,
            SshRemotePathBrowserSession.directoryEntryFromRemoteLine(
                "__ANDROID_RSYNC_BACKUP_PWD__/mnt",
                "/mnt",
            ),
        )
        assertEquals(
            null,
            SshRemotePathBrowserSession.directoryEntryFromRemoteLine(
                "__ANDROID_RSYNC_BACKUP_DIR__.",
                "/mnt",
            ),
        )
    }
}
