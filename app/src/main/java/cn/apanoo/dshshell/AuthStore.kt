package cn.apanoo.dshshell

import android.content.Context
/**
 * Basic 认证凭证存储。
 * SharedPreferences 位于应用私有沙箱（/data/data/<pkg>/），普通应用无法读取；
 * 如需加固可换 EncryptedSharedPreferences + Android Keystore（本场景 LAN 工具暂无必要）。
 */
class AuthStore(context: Context) {
    private val sp = context.getSharedPreferences("dsh_auth", Context.MODE_PRIVATE)

    fun load(): Pair<String, String>? {
        val u = sp.getString(KEY_USER, null) ?: return null
        val p = sp.getString(KEY_PASS, null) ?: return null
        return u to p
    }

    fun save(user: String, pass: String) {
        sp.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply()
    }

    companion object {
        private const val KEY_USER = "u"
        private const val KEY_PASS = "p"
    }
}
