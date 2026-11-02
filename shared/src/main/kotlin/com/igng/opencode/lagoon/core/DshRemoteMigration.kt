package com.igng.opencode.lagoon.core

/**
 * A direct DSH profile cannot become a Remote device. Its launch token, signing secret, cookie,
 * drafts and caches stay with the original profile until the user explicitly deletes that profile.
 */
data class DshRemoteMigration(
  val profileId: String,
  val retained: Boolean,
  val reason: String
) {
  companion object {
    fun plan(profile: ServerProfile): DshRemoteMigration? {
      if (profile.kind != ServerKind.DSH) return null
      return DshRemoteMigration(
        profile.id,
        retained = true,
        reason = "启动令牌、签名密钥和 Cookie 不能转换成 Remote 设备凭据；草稿和缓存仍留在原资料。"
      )
    }
  }
}
