package com.igng.opencode.lagoon.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors

/**
 * Scan-to-Pair v1 (`dsh-pairing-v1` + `fragment-ticket-v1`) flow:
 * 1) Scan via CameraX / pick from gallery / paste link
 * 2) Explicitly confirm Server origin (+ HTTP switch if cleartext) and Host 64-char fingerprint
 * 3) Display 8-digit code with `FLAG_SECURE` while foreground-only polling completes & auto-connects.
 */
@Composable
internal fun RemotePairingSheet(
  controller: LagoonController,
  onDismiss: () -> Unit,
  onPaired: (hostName: String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val recoveryStore = remember(context.applicationContext) { RemotePairingRecoveryStore(context.applicationContext) }

  var hasCameraPermission by remember {
    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    hasCameraPermission = granted
  }

  var rawInput by remember { mutableStateOf("") }
  var allowHttp by rememberSaveable { mutableStateOf(false) }
  var needsHttpConsent by remember { mutableStateOf(false) }
  var proposedOrigin by remember { mutableStateOf<String?>(null) }
  var scannedIsTicket by remember { mutableStateOf(true) }
  var cameraActive by remember { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  var offer by remember { mutableStateOf<RemotePairingOffer?>(null) }
  // Hold mobile identity & frozen claim body for the lifetime of this session so network retries
  // recover the same verification code without minting another identity.
  var sessionIdentity by remember { mutableStateOf<RemoteIdentity?>(null) }
  var sessionDevice by remember { mutableStateOf<RemotePairingDevice?>(null) }
  var frozenClaimBody by remember { mutableStateOf<String?>(null) }
  var claim by remember { mutableStateOf<RemotePairingClaim?>(null) }
  var statusState by remember { mutableStateOf(RemotePairingState.CREATED) }
  var terminalFailure by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf<PendingRemotePairing?>(null) }
  var recoveryLoaded by remember { mutableStateOf(false) }
  var recoveryReadFailed by remember { mutableStateOf(false) }
  var cancelling by remember { mutableStateOf(false) }
  var cancelIntentFailed by remember { mutableStateOf(false) }
  var connectionScheduled by remember { mutableStateOf(false) }

  LaunchedEffect(recoveryStore) {
    try {
      val saved = withContext(Dispatchers.IO) { recoveryStore.load() }
      if (saved != null) {
        pending = saved
        offer = saved.offer
        sessionIdentity = saved.identity
        sessionDevice = saved.device
        frozenClaimBody = saved.claimBody
        allowHttp = saved.allowCleartext
        cameraActive = false
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (e: Exception) {
      recoveryReadFailed = true
      error = userFacingError(e, "未能读取之前的配对")
    } finally {
      recoveryLoaded = true
    }
  }

  fun resetToScan(clearInput: Boolean = false) {
    offer = null
    sessionIdentity = null
    sessionDevice = null
    frozenClaimBody = null
    claim = null
    statusState = RemotePairingState.CREATED
    terminalFailure = null
    pending = null
    proposedOrigin = null
    connectionScheduled = false
    cancelIntentFailed = false
    error = null
    busy = false
    if (clearInput) {
      rawInput = ""
      allowHttp = false
      needsHttpConsent = false
    }
    cameraActive = true
  }

  fun resolveInput(text: String, explicitHttpConsent: Boolean, originConfirmed: Boolean = false) {
    if (busy) return
    rawInput = text
    cameraActive = false
    val inspected = try {
      parseRemotePairingScan(text, allowCleartext = true)
    } catch (e: RemotePairingException) {
      error = e.message ?: "无效的配对二维码"
      return
    }
    scannedIsTicket = inspected is RemotePairingScan.Ticket
    if (!originConfirmed || proposedOrigin != inspected.origin) {
      proposedOrigin = inspected.origin
      needsHttpConsent = inspected.isCleartext
      error = null
      return
    }
    if (inspected.isCleartext && !explicitHttpConsent) {
      needsHttpConsent = true
      cameraActive = false
      error = "配对链接使用明文 HTTP (${inspected.origin})，请确认受信任后再允许连接"
      return
    }
    val confirmedOrigin = proposedOrigin
    scope.launch {
      busy = true
      error = null
      needsHttpConsent = false
      try {
        val resolved = withContext(Dispatchers.IO) {
          resolveConfirmedRemotePairing(text, explicitHttpConsent, confirmedOrigin) { scan ->
            RemotePairingClient(RemoteServerApi(scan.origin, explicitHttpConsent)).resolve(scan)
          }
        }
        offer = resolved
        rawInput = ""
        sessionIdentity = null
        sessionDevice = null
        frozenClaimBody = null
        claim = null
        statusState = RemotePairingState.CREATED
        terminalFailure = null
        proposedOrigin = null
        cameraActive = false
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (e: Exception) {
        error = userFacingError(e, "解析配对码失败")
      } finally {
        busy = false
      }
    }
  }

  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      busy = true
      error = null
      val decoded = withContext(Dispatchers.IO) { decodeQrFromUri(context, uri) }
      busy = false
      if (decoded == null) {
        error = "未能从所选图片中识别出二维码"
      } else {
        allowHttp = false
        resolveInput(decoded, explicitHttpConsent = false)
      }
    }
  }

  // Both the scanned ticket and the verification code are secrets.
  DisposableEffect(Unit) {
    val activity = context.findActivity()
    val window = activity?.window
    val hadSecure = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    if (window != null && !hadSecure) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    onDispose {
      if (window != null && !hadSecure) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
    }
  }

  // The exact claim is written before it is sent. On recreation, try that same claim first;
  // if the server has already completed it, status still recovers the original credentials.
  LaunchedEffect(pending?.offer?.sessionId, pending?.cancelRequested, cancelling, cancelIntentFailed, lifecycleOwner) {
    val active = pending ?: return@LaunchedEffect
    if (cancelling || cancelIntentFailed) return@LaunchedEffect
    val client = RemotePairingClient(RemoteServerApi(active.offer.origin, active.allowCleartext))
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (pending?.offer?.sessionId == active.offer.sessionId && terminalFailure == null) {
        var nextDelayMs = 2_500L
        try {
          val needsCode = !active.cancelRequested && claim == null &&
            (statusState == RemotePairingState.CREATED || statusState == RemotePairingState.CLAIMED)
          var cancelError: Exception? = null
          val attempt = remotePairingOperationMutex.withLock {
            withContext(Dispatchers.IO) {
              if (active.cancelRequested) {
                try { client.cancel(active.offer, active.identity) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { cancelError = failure }
              }
              recoverRemotePairingStatus(
                pending = active,
                recoverCode = needsCode,
                claim = { client.claim(it.offer, it.identity, it.device, it.claimBody) },
                status = { client.pollStatus(it.offer, it.identity, it.device) },
              )
            }
          }
          attempt.claim?.let { claim = it }
          val polled = attempt.status
          statusState = polled.state
          if (polled.recoverUntilMillis != null && pending?.recoverUntilMillis != polled.recoverUntilMillis) {
            val updated = active.copy(recoverUntilMillis = polled.recoverUntilMillis)
            withContext(Dispatchers.IO) { recoveryStore.save(updated) }
            pending = updated
          }
          when (polled.state) {
            RemotePairingState.COMPLETED -> {
              val credentials = polled.credentials
                ?: throw RemotePairingException("PAIRING_FAILED", "配对完成但未收到设备凭据")
              val catalog = withContext(Dispatchers.IO) {
                controller.completeRemotePairing(active.offer, credentials, active.identity, active.allowCleartext)
              }
              if (!connectionScheduled) {
                controller.connectPairedRemoteHostAsync(catalog, active.offer.host.deviceId)
                connectionScheduled = true
              }
              // If cleanup fails, retain the recovery record. Re-adoption preserves rotated tokens.
              withContext(Dispatchers.IO) { recoveryStore.clear(active.offer.sessionId) }
              pending = null
              onPaired(active.offer.host.name)
              return@repeatOnLifecycle
            }
            RemotePairingState.FAILED -> {
              terminalFailure = pairingFailureMessage(polled.failureReason)
              return@repeatOnLifecycle
            }
            RemotePairingState.CANCELLED -> {
              terminalFailure = "本次配对已取消"
              return@repeatOnLifecycle
            }
            RemotePairingState.EXPIRED -> {
              terminalFailure = "本次配对已超时，请在电脑上重新生成二维码"
              return@repeatOnLifecycle
            }
            RemotePairingState.CREATED, RemotePairingState.CLAIMED, RemotePairingState.APPROVED -> {
              error = if (active.cancelRequested) cancelError?.let { userFacingError(it, "取消结果暂时未知，正在重试") }
              else if (polled.state == RemotePairingState.CLAIMED && claim == null)
                attempt.claimError?.let { userFacingError(it, "验证码暂时无法恢复，正在重试") } else null
            }
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (e: RemotePairingException) {
          val recoveryDeadline = pending?.recoverUntilMillis ?: active.offer.expiresAtMillis
          if (e.code == "PAIRING_NOT_FOUND" && System.currentTimeMillis() > recoveryDeadline) {
            terminalFailure = "配对会话已不存在，请在电脑端重新生成二维码"
            return@repeatOnLifecycle
          }
          nextDelayMs = e.retryAfterMs ?: 2_500L
          error = userFacingError(e, "配对状态暂时无法确认，正在重试")
        } catch (e: Exception) {
          error = userFacingError(e, if (statusState == RemotePairingState.COMPLETED) "已完成配对，保存失败，正在重试" else "配对状态暂时无法确认，正在重试")
        }
        delay(nextDelayMs.coerceAtLeast(500L))
      }
    }
  }

  HyperBottomSheet(
    title = "扫码配对电脑",
    show = true,
    // Closing the sheet pauses polling; it is not consent to cancel an approved transaction.
    onDismissRequest = onDismiss,
  ) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
      LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        val activeOffer = offer
        val activeClaim = claim
        when {
          !recoveryLoaded -> item { Text("正在检查未完成的配对…") }
          recoveryReadFailed -> item {
            HyperInlineNotice(message = "无法读取之前的配对资料，请稍后重试。为保护已有身份，暂不能开始新配对。", type = MiuixToastType.ERROR)
          }
          activeOffer == null -> {
            item {
              Text(
                "在电脑端 DSH Remote 设置或配对弹窗打开配对二维码，使用相机扫描或粘贴配对链接，即可免账号安全接入单台电脑。",
                style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
              )
            }
            if (hasCameraPermission && cameraActive) {
              item {
                PairingCameraPreview(
                  onDetected = { text ->
                    if (!busy && text != rawInput) {
                      allowHttp = false
                      resolveInput(text, explicitHttpConsent = false)
                    }
                  },
                )
              }
            } else if (!hasCameraPermission) {
              item {
                HyperButton(
                  text = "授予相机权限以扫码",
                  onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
            item {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HyperButton(
                  text = "从相册选择二维码",
                  variant = HyperButtonVariant.SECONDARY,
                  loading = busy,
                  enabled = !busy,
                  onClick = { galleryLauncher.launch("image/*") },
                  modifier = Modifier.weight(1f),
                )
                if (hasCameraPermission && !cameraActive) {
                  HyperButton(
                    text = "恢复相机",
                    variant = HyperButtonVariant.SECONDARY,
                    enabled = !busy,
                    onClick = { rawInput = ""; proposedOrigin = null; allowHttp = false; cameraActive = true; error = null },
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
            item {
              TextField(
                value = rawInput,
                onValueChange = {
                  if (busy) return@TextField
                  val changed = it != rawInput
                  rawInput = it
                  if (changed) {
                    allowHttp = false
                    needsHttpConsent = false
                    proposedOrigin = null
                  }
                  error = null
                },
                label = "或粘贴配对链接 / 配对码 JSON",
              )
            }
            val parsedPreview = runCatching {
              if (rawInput.isBlank()) null else parseRemotePairingScan(rawInput, allowCleartext = true)
            }.getOrNull()
            proposedOrigin?.let { origin ->
              item {
                HyperInlineNotice(
                  message = "请确认电脑端显示的服务器地址：$origin。确认后才会向该地址发送配对请求。",
                  type = MiuixToastType.WARNING,
                )
              }
            }
            if (needsHttpConsent || parsedPreview?.isCleartext == true) {
              item {
                HyperSwitchRow(
                  title = "允许此地址使用明文 HTTP",
                  summary = "仅在信任的局域网或自托管环境开启",
                  checked = allowHttp,
                  onCheckedChange = { allowHttp = it },
                )
              }
            }
            item {
              HyperButton(
                text = if (proposedOrigin == null) "检查配对码" else "确认服务器并继续",
                loading = busy,
                enabled = rawInput.isNotBlank() && (!(needsHttpConsent || parsedPreview?.isCleartext == true) || allowHttp),
                onClick = { resolveInput(rawInput, explicitHttpConsent = allowHttp, originConfirmed = proposedOrigin != null) },
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          activeClaim == null && pending == null -> {
            item {
              HyperInlineNotice(
                message = if (scannedIsTicket) {
                  "短凭据二维码通过已确认的中枢地址获取主机身份，请确认服务器地址与电脑端显示的 64 位指纹完全一致。"
                } else {
                  "请核对中枢服务器地址与下方 64 位主机身份指纹与电脑界面完全一致。"
                },
                type = MiuixToastType.WARNING,
              )
            }
            item {
              Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("中枢地址", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                  Text(activeOffer.origin, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium))
                  Spacer(Modifier.height(2.dp))
                  Text("目标电脑", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                  Text(
                    "${activeOffer.host.name} · ${activeOffer.host.platform}",
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                  )
                  Spacer(Modifier.height(2.dp))
                  Text("主机公钥指纹（SHA-256）", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                  Text(
                    formatRemotePairingFingerprint(activeOffer.host.fingerprint),
                    style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                  )
                }
              }
            }
            item {
              HyperButton(
                text = "确认主机并获取 8 位验证码",
                loading = busy,
                enabled = !busy,
                onClick = {
                  if (busy) return@HyperButton
                  scope.launch {
                    busy = true
                    error = null
                    try {
                      val identity = sessionIdentity ?: RemoteIdentity.generate().also { sessionIdentity = it }
                      val device = sessionDevice ?: RemotePairingDevice(
                        deviceId = identity.deviceId,
                        name = mobileDeviceLabel(),
                        role = "client",
                        platform = "android",
                        identityKey = identity.publicKey,
                        clientVersion = appVersionName(context),
                      ).also { sessionDevice = it }
                      val body = frozenClaimBody ?: pairingClaimBody(activeOffer.scanSecret, device).also { frozenClaimBody = it }
                      val prepared = PendingRemotePairing(activeOffer, identity, device, body, allowHttp)
                      withContext(Dispatchers.IO) { recoveryStore.save(prepared) }
                      pending = prepared
                    } catch (cancel: CancellationException) {
                      throw cancel
                    } catch (e: Exception) {
                      error = userFacingError(e, "领取验证码失败")
                    } finally {
                      busy = false
                    }
                  }
                },
                modifier = Modifier.fillMaxWidth(),
              )
            }
            item {
              HyperButton(
                text = "取消并重新扫码",
                variant = HyperButtonVariant.SECONDARY,
                enabled = !busy,
                onClick = { resetToScan(clearInput = true) },
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          else -> {
            if (activeClaim != null && !statusState.isTerminal && pending?.cancelRequested != true) {
              val formattedCode = "${activeClaim.verificationCode.substring(0, 4)} ${activeClaim.verificationCode.substring(4)}"
              item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                      "配对验证码",
                      style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    )
                    Text(
                      formattedCode,
                      style = MiuixTheme.textStyles.title1.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                      textAlign = TextAlign.Center,
                    )
                    Text(
                      "请在电脑端输入这 8 位数字并点击确认。为保护安全，此界面已开启防截屏。",
                      style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                      textAlign = TextAlign.Center,
                    )
                  }
                }
              }
            }
            val failText = terminalFailure
            if (failText != null) {
              item { HyperInlineNotice(message = failText, type = MiuixToastType.ERROR) }
              item {
                HyperButton(
                  text = "重新扫码",
                  onClick = {
                    scope.launch {
                      try {
                        withContext(Dispatchers.IO) { recoveryStore.clear(activeOffer.sessionId) }
                        resetToScan(clearInput = true)
                      } catch (cancel: CancellationException) {
                        throw cancel
                      } catch (e: Exception) {
                        error = userFacingError(e, "清理已结束的配对失败")
                      }
                    }
                  },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            } else {
              item {
                Row(
                  Modifier.fillMaxWidth().padding(vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  InfiniteProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                  Text(
                    when {
                      statusState == RemotePairingState.COMPLETED -> "电脑已完成配对，正在安全保存…"
                      pending?.cancelRequested == true -> "正在确认取消配对…"
                      statusState == RemotePairingState.APPROVED -> "电脑已提交正确验证码，正在完成绑定…"
                      activeClaim == null -> "正在恢复配对…"
                      else -> "等待你在 ${activeOffer.host.name} 上输入验证码…"
                    },
                    style = MiuixTheme.textStyles.body2,
                  )
                }
              }
              item {
                HyperButton(
                  text = "取消配对",
                  variant = HyperButtonVariant.DESTRUCTIVE,
                  enabled = !busy && !cancelling && pending?.cancelRequested != true && statusState != RemotePairingState.COMPLETED,
                  onClick = {
                    val current = pending ?: return@HyperButton
                    cancelling = true
                    scope.launch {
                      try {
                        val requested = current.copy(cancelRequested = true)
                        withContext(Dispatchers.IO) { recoveryStore.save(requested) }
                        pending = requested
                        cancelIntentFailed = false
                        error = null
                      } catch (cancel: CancellationException) {
                        throw cancel
                      } catch (e: Exception) {
                        cancelIntentFailed = true
                        error = userFacingError(e, "取消请求未能保存，请重试")
                      } finally {
                        cancelling = false
                      }
                    }
                  },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }

        error?.let { msg ->
          item { HyperInlineNotice(message = msg, type = MiuixToastType.ERROR) }
        }
      }
    }
  }
}

@Composable
private fun PairingCameraPreview(
  onDetected: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val latestOnDetected by rememberUpdatedState(onDetected)
  val previewView = remember(context) {
    PreviewView(context).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }

  DisposableEffect(lifecycleOwner, previewView) {
    val executor = Executors.newSingleThreadExecutor()
    val future = ProcessCameraProvider.getInstance(context)
    var boundProvider: ProcessCameraProvider? = null
    var disposed = false

    val listener = Runnable {
      if (disposed) return@Runnable
      runCatching {
        val provider = future.get()
        boundProvider = provider
        val preview = Preview.Builder().build().also {
          it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val reader = createQrReader()
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { imageAnalysis ->
            imageAnalysis.setAnalyzer(executor) { image ->
              try {
                decodeQrFromImageProxy(reader, image)?.let { text ->
                  previewView.post { latestOnDetected(text) }
                }
              } finally {
                image.close()
              }
            }
          }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
      }
    }
    future.addListener(listener, ContextCompat.getMainExecutor(context))

    onDispose {
      disposed = true
      runCatching { boundProvider?.unbindAll() }
      executor.shutdown()
    }
  }

  Box(
    Modifier.fillMaxWidth().height(220.dp).clip(miuixSquircleShape(16.dp)).background(HyperTheme.colors.container),
    contentAlignment = Alignment.Center,
  ) {
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
  }
}

private fun createQrReader(): MultiFormatReader = MultiFormatReader().apply {
  setHints(
    mapOf(
      DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
      DecodeHintType.TRY_HARDER to true,
    )
  )
}

private fun decodeQrFromImageProxy(reader: MultiFormatReader, image: ImageProxy): String? {
  val plane = image.planes.firstOrNull() ?: return null
  val width = image.width
  val height = image.height
  if (width <= 0 || height <= 0) return null
  val yBytes = extractYPlane(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
  val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
  return decodeWithReader(reader, source)
}

private fun decodeQrFromUri(context: Context, uri: Uri): String? = runCatching {
  val resolver = context.contentResolver
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
  var sample = 1
  val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
  while (maxSide / sample > 1600) sample *= 2
  val opts = BitmapFactory.Options().apply {
    inSampleSize = sample
    inPreferredConfig = Bitmap.Config.ARGB_8888
  }
  val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
  try {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    decodeWithReader(createQrReader(), RGBLuminanceSource(w, h, pixels))
  } finally {
    bitmap.recycle()
  }
}.getOrNull()

private fun decodeWithReader(reader: MultiFormatReader, source: LuminanceSource): String? = try {
  reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
} catch (_: NotFoundException) {
  null
} finally {
  reader.reset()
}

private fun extractYPlane(
  buffer: java.nio.ByteBuffer,
  width: Int,
  height: Int,
  rowStride: Int,
  pixelStride: Int,
): ByteArray {
  val data = ByteArray(width * height)
  val dup = buffer.duplicate().apply { rewind() }
  if (pixelStride == 1 && rowStride == width && dup.remaining() >= data.size) {
    dup.get(data, 0, data.size)
    return data
  }
  val row = ByteArray(rowStride)
  for (y in 0 until height) {
    val toRead = minOf(rowStride, dup.remaining())
    if (toRead <= 0) break
    dup.get(row, 0, toRead)
    if (pixelStride == 1) {
      System.arraycopy(row, 0, data, y * width, minOf(width, toRead))
    } else {
      for (x in 0 until width) {
        val idx = x * pixelStride
        if (idx < toRead) data[y * width + x] = row[idx]
      }
    }
  }
  return data
}

private fun pairingFailureMessage(reason: String?): String = when (reason) {
  "verification_failed" -> "验证码不匹配，本次配对已失效，请在电脑上重新生成二维码"
  "PAIRING_ACCOUNT_CONFLICT" -> "电脑与当前手机属于不同账号，未覆盖现有凭据"
  "HOST_DEVICE_LIMIT_REACHED" -> "目标电脑关联设备数量已达上限"
  "PAIRING_DEVICE_CONFLICT" -> "设备身份冲突或已被撤销，无法完成配对"
  null -> "配对未能完成，请在电脑上重新生成二维码"
  else -> "配对失败（$reason），请在电脑上重新生成二维码"
}

private fun mobileDeviceLabel(): String {
  val model = Build.MODEL.orEmpty().replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").trim().ifBlank { "Android" }
  val raw = "Lagoon · $model"
  val bytes = raw.toByteArray(Charsets.UTF_8)
  if (bytes.size <= 128) return raw
  var end = raw.length
  while (end > 0 && raw.substring(0, end).toByteArray(Charsets.UTF_8).size > 128) end--
  return raw.substring(0, end).trim().ifBlank { "Lagoon Android" }
}

private fun appVersionName(context: Context): String? = runCatching {
  context.packageManager.getPackageInfo(context.packageName, 0).versionName
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 64 }
}.getOrNull()

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
