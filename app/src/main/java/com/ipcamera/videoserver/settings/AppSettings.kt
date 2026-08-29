package com.ipcamera.videoserver.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("app_settings")

@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val SERVER_PORT = intPreferencesKey("server_port")
        val ADMIN_USERNAME = stringPreferencesKey("admin_username")
        val ADMIN_PASSWORD_HASH = stringPreferencesKey("admin_password_hash")
        val ADMIN_PASSWORD_PLAIN = stringPreferencesKey("admin_password_plain")
        val JWT_SECRET = stringPreferencesKey("jwt_secret")
        val SMS_TARGET_NUMBER = stringPreferencesKey("sms_target_number")
        val SMS_SIM_SLOT = intPreferencesKey("sms_sim_slot")
        val IP_POLL_INTERVAL_MINUTES = intPreferencesKey("ip_poll_interval_minutes")
        val LAST_KNOWN_PUBLIC_IP = stringPreferencesKey("last_known_public_ip")
        val ARCHIVE_ENABLED_MAIN = booleanPreferencesKey("archive_enabled_main")
        val ARCHIVE_ENABLED_FRONT = booleanPreferencesKey("archive_enabled_front")
        val ARCHIVE_MAX_FILES = intPreferencesKey("archive_max_files")
        val ARCHIVE_MAX_SIZE_GB = intPreferencesKey("archive_max_size_gb")
        val FTP_ENABLED = booleanPreferencesKey("ftp_enabled")
        val FTP_PORT = intPreferencesKey("ftp_port")
        val SERVER_STARTED_ON_BOOT = booleanPreferencesKey("server_started_on_boot")
    }

    val serverPort: Flow<Int> = context.dataStore.data.map { it[Keys.SERVER_PORT] ?: 8080 }
    val adminUsername: Flow<String> = context.dataStore.data.map { it[Keys.ADMIN_USERNAME] ?: "admin" }
    val adminPasswordHash: Flow<String> = context.dataStore.data.map { it[Keys.ADMIN_PASSWORD_HASH] ?: "" }
    val adminPasswordPlain: Flow<String> = context.dataStore.data.map { it[Keys.ADMIN_PASSWORD_PLAIN] ?: "admin" }
    val jwtSecret: Flow<String> = context.dataStore.data.map { it[Keys.JWT_SECRET] ?: "" }
    val smsTargetNumber: Flow<String> = context.dataStore.data.map { it[Keys.SMS_TARGET_NUMBER] ?: "" }
    val smsSimSlot: Flow<Int> = context.dataStore.data.map { it[Keys.SMS_SIM_SLOT] ?: 0 }
    val ipPollIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.IP_POLL_INTERVAL_MINUTES] ?: 5 }
    val lastKnownPublicIp: Flow<String> = context.dataStore.data.map { it[Keys.LAST_KNOWN_PUBLIC_IP] ?: "" }
    val archiveEnabledMain: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARCHIVE_ENABLED_MAIN] ?: false }
    val archiveEnabledFront: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARCHIVE_ENABLED_FRONT] ?: false }
    val archiveMaxFiles: Flow<Int> = context.dataStore.data.map { it[Keys.ARCHIVE_MAX_FILES] ?: 1440 }
    val archiveMaxSizeGb: Flow<Int> = context.dataStore.data.map { it[Keys.ARCHIVE_MAX_SIZE_GB] ?: 30 }
    val ftpEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FTP_ENABLED] ?: false }
    val ftpPort: Flow<Int> = context.dataStore.data.map { it[Keys.FTP_PORT] ?: 2121 }
    val serverStartedOnBoot: Flow<Boolean> = context.dataStore.data.map { it[Keys.SERVER_STARTED_ON_BOOT] ?: false }

    suspend fun setServerPort(port: Int) = context.dataStore.edit { it[Keys.SERVER_PORT] = port }
    suspend fun setAdminUsername(name: String) = context.dataStore.edit { it[Keys.ADMIN_USERNAME] = name }
    suspend fun setAdminPasswordHash(hash: String) = context.dataStore.edit { it[Keys.ADMIN_PASSWORD_HASH] = hash }
    suspend fun setAdminPasswordPlain(plain: String) = context.dataStore.edit { it[Keys.ADMIN_PASSWORD_PLAIN] = plain }
    suspend fun setJwtSecret(secret: String) = context.dataStore.edit { it[Keys.JWT_SECRET] = secret }
    suspend fun setSmsTargetNumber(number: String) = context.dataStore.edit { it[Keys.SMS_TARGET_NUMBER] = number }
    suspend fun setSmsSimSlot(slot: Int) = context.dataStore.edit { it[Keys.SMS_SIM_SLOT] = slot }
    suspend fun setIpPollIntervalMinutes(minutes: Int) = context.dataStore.edit { it[Keys.IP_POLL_INTERVAL_MINUTES] = minutes }
    suspend fun setLastKnownPublicIp(ip: String) = context.dataStore.edit { it[Keys.LAST_KNOWN_PUBLIC_IP] = ip }
    suspend fun setArchiveEnabledMain(enabled: Boolean) = context.dataStore.edit { it[Keys.ARCHIVE_ENABLED_MAIN] = enabled }
    suspend fun setArchiveEnabledFront(enabled: Boolean) = context.dataStore.edit { it[Keys.ARCHIVE_ENABLED_FRONT] = enabled }
    suspend fun setArchiveMaxFiles(max: Int) = context.dataStore.edit { it[Keys.ARCHIVE_MAX_FILES] = max }
    suspend fun setArchiveMaxSizeGb(gb: Int) = context.dataStore.edit { it[Keys.ARCHIVE_MAX_SIZE_GB] = gb }
    suspend fun setFtpEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.FTP_ENABLED] = enabled }
    suspend fun setFtpPort(port: Int) = context.dataStore.edit { it[Keys.FTP_PORT] = port }
    suspend fun setServerStartedOnBoot(enabled: Boolean) = context.dataStore.edit { it[Keys.SERVER_STARTED_ON_BOOT] = enabled }
}
