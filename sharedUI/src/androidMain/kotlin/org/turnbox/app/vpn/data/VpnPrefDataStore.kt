package org.turnbox.app.vpn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.vpnPrefDataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_preferences")
val KEY_IS_VPN_CONFIG_READY = booleanPreferencesKey("is_vpn_config_ready")
val KEY_VPN_CONFIG_PATH = stringPreferencesKey("vpn_config_path")
