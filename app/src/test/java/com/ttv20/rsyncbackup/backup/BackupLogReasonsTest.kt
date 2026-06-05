package com.ttv20.rsyncbackup.backup

import com.ttv20.rsyncbackup.model.BackupEndReason
import com.ttv20.rsyncbackup.model.BackupProfile
import com.ttv20.rsyncbackup.model.BackupRunTrigger
import com.ttv20.rsyncbackup.model.RunStatus
import com.ttv20.rsyncbackup.model.TargetMode
import com.ttv20.rsyncbackup.model.TargetRecord
import com.ttv20.rsyncbackup.storage.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupLogReasonsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun networkConstraintBlockedRunIsLoggedWithNoNetworkReason() {
        val repository = AppRepository(
            dataFile = temporaryFolder.newFile("state.json"),
            defaultExcludes = "cache/\n",
        )
        repository.loadBlocking()
        val target = TargetRecord(
            id = "target-home",
            name = "Home backup target",
            user = "ttv20",
            lanHost = "192.168.3.200",
        )
        repository.upsertTarget(target)
        repository.upsertProfile(
            BackupProfile(
                id = "profile-phone",
                name = "Phone shared storage",
                sourcePath = "/storage/emulated/0",
                targetId = target.id,
                remotePath = "/mnt/backup/phone",
                targetMode = TargetMode.LAN_ONLY,
                excludes = "cache/",
            ),
        )
        val profile = repository.state.value.profiles.single()

        val log = repository.recordConstraintBlockedBackup(
            profile = profile,
            failures = listOf(ConstraintFailure("wifi_only", "Wi-Fi connection is required")),
            trigger = BackupRunTrigger.AUTOMATIC,
            now = "2026-06-03T01:00:00Z",
        )

        assertEquals(RunStatus.CANCELLED, log.status)
        assertEquals(BackupRunTrigger.AUTOMATIC, log.trigger)
        assertEquals(BackupEndReason.NO_NETWORK, log.endReason)
        assertEquals("Wi-Fi connection is required", log.endReasonDetail)
        assertEquals(log, repository.state.value.logs.single())
    }
}
