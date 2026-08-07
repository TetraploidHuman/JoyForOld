package com.tetraploid.joyforold.ui.cortana

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.LocalCortanaColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 圆环上的一段亮弧。角度约定与 Compose [drawArc] 一致：
 * 0° 在三点钟方向，顺时针为正。
 */
data class HaloArc(
    val startAngle: Float,
    val sweepAngle: Float,
)

/**
 * 光环情绪 / 状态：决定弧段布局、颜色与动画（旋转、变长、分裂）。
 *
 * - [Idle] 约 1/3 蓝弧转圈（默认待机）
 * - [Happy] 笑脸
 * - [Listening] 长弧，长度轻微呼吸
 * - [Loading] 短弧快速旋转
 * - [Processing] 双弧对置旋转
 * - [Speaking] 中等弧，长度起伏
 * - [Confirm] 三短弧均分
 * - [Success] 绿色笑脸
 * - [Error] 红色忧伤脸（短嘴）
 */
enum class CortanaOrbMood {
    Idle,
    Listening,
    Loading,
    Processing,
    Speaking,
    Happy,
    Confirm,
    Success,
    Error,
}

private data class OrbMotion(
    val baseArcs: List<HaloArc>,
    /** 整圈旋转一周的毫秒数；≤0 表示不旋转 */
    val spinMillis: Int,
    /** 弧长相对 base 的呼吸振幅（0 = 不变长） */
    val lengthPulse: Float,
    val lengthPulseMillis: Int,
    val glowPulse: Boolean,
)

/** 约 1/3 圆周的亮弧（局部坐标，绘制时加 orbitSpin） */
private val ThirdSpinArcs = listOf(HaloArc(startAngle = 0f, sweepAngle = 120f))

/** 笑脸：左眼、右眼 + 底部长弧（嘴） */
private val SmileArcs = listOf(
    HaloArc(startAngle = 218f, sweepAngle = 26f),
    HaloArc(startAngle = 316f, sweepAngle = 26f),
    HaloArc(startAngle = 38f, sweepAngle = 104f),
)

/** 忧伤：双眼 + 底部短平嘴 */
private val SadArcs = listOf(
    HaloArc(startAngle = 212f, sweepAngle = 30f),
    HaloArc(startAngle = 298f, sweepAngle = 30f),
    HaloArc(startAngle = 55f, sweepAngle = 70f),
)

private const val ArcMorphMillis = 560
private const val EntrySpinMillis = 2000
private const val EntrySmileHoldMillis = 500
private const val TapSmileHoldMillis = 500

private fun CortanaOrbMood.motion(): OrbMotion = when (this) {
    CortanaOrbMood.Idle -> OrbMotion(
        baseArcs = ThirdSpinArcs,
        spinMillis = 3200,
        lengthPulse = 0f,
        lengthPulseMillis = 3200,
        glowPulse = true,
    )
    CortanaOrbMood.Happy -> OrbMotion(
        baseArcs = SmileArcs,
        spinMillis = 0,
        lengthPulse = 0.04f,
        lengthPulseMillis = 2400,
        glowPulse = true,
    )
    CortanaOrbMood.Success -> OrbMotion(
        baseArcs = SmileArcs,
        spinMillis = 0,
        lengthPulse = 0.06f,
        lengthPulseMillis = 1600,
        glowPulse = true,
    )
    CortanaOrbMood.Error -> OrbMotion(
        baseArcs = SadArcs,
        spinMillis = 0,
        lengthPulse = 0.05f,
        lengthPulseMillis = 1800,
        glowPulse = true,
    )
    CortanaOrbMood.Listening -> OrbMotion(
        baseArcs = listOf(HaloArc(startAngle = -40f, sweepAngle = 130f)),
        spinMillis = 0,
        lengthPulse = 0.22f,
        lengthPulseMillis = 1600,
        glowPulse = true,
    )
    CortanaOrbMood.Loading -> OrbMotion(
        baseArcs = listOf(HaloArc(startAngle = 0f, sweepAngle = 72f)),
        spinMillis = 3200,
        lengthPulse = 0.18f,
        lengthPulseMillis = 1400,
        glowPulse = false,
    )
    CortanaOrbMood.Processing -> OrbMotion(
        baseArcs = listOf(
            HaloArc(startAngle = 0f, sweepAngle = 55f),
            HaloArc(startAngle = 180f, sweepAngle = 55f),
        ),
        spinMillis = 4000,
        lengthPulse = 0.12f,
        lengthPulseMillis = 1600,
        glowPulse = false,
    )
    CortanaOrbMood.Speaking -> OrbMotion(
        baseArcs = listOf(HaloArc(startAngle = -30f, sweepAngle = 100f)),
        spinMillis = 0,
        lengthPulse = 0.35f,
        lengthPulseMillis = 700,
        glowPulse = true,
    )
    CortanaOrbMood.Confirm -> OrbMotion(
        baseArcs = listOf(
            HaloArc(startAngle = -12f, sweepAngle = 36f),
            HaloArc(startAngle = 108f, sweepAngle = 36f),
            HaloArc(startAngle = 228f, sweepAngle = 36f),
        ),
        spinMillis = 8000,
        lengthPulse = 0.15f,
        lengthPulseMillis = 1400,
        glowPulse = true,
    )
}

/**
 * Cortana 圆形光环：灰色底环 + 亮色弧（可旋转 / 变长 / 分裂），带小范围泛光。
 *
 * 旋转中切换表情时，会先把当前转角烘焙进弧段，再沿最短角路径变形，避免跳变。
 */
@Composable
fun CortanaOrb(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    mood: CortanaOrbMood = CortanaOrbMood.Idle,
    arcs: List<HaloArc>? = null,
    playEntryAnimation: Boolean = false,
) {
    var entryFinished by rememberSaveable { mutableStateOf(!playEntryAnimation) }
    var displayMood by remember {
        mutableStateOf(
            if (playEntryAnimation && !entryFinished) CortanaOrbMood.Idle else mood,
        )
    }
    var smiledByTap by remember { mutableStateOf(false) }
    var tapSmileNonce by remember { mutableStateOf(0) }

    LaunchedEffect(tapSmileNonce) {
        if (tapSmileNonce == 0) return@LaunchedEffect
        smiledByTap = true
        delay(ArcMorphMillis.toLong() + TapSmileHoldMillis.toLong())
        smiledByTap = false
    }

    LaunchedEffect(playEntryAnimation) {
        if (!playEntryAnimation || entryFinished) return@LaunchedEffect
        smiledByTap = false
        displayMood = CortanaOrbMood.Idle
        delay(EntrySpinMillis.toLong())
        displayMood = CortanaOrbMood.Happy
        delay(ArcMorphMillis.toLong() + EntrySmileHoldMillis.toLong())
        displayMood = CortanaOrbMood.Idle
        delay(ArcMorphMillis.toLong())
        entryFinished = true
    }

    LaunchedEffect(mood, entryFinished) {
        if (mood != CortanaOrbMood.Idle) {
            smiledByTap = false
        }
        if (!entryFinished) {
            if (mood != CortanaOrbMood.Idle && mood != CortanaOrbMood.Happy) {
                displayMood = mood
                entryFinished = true
            }
            return@LaunchedEffect
        }
        displayMood = mood
    }

    val effectiveMood = when {
        arcs != null -> mood
        smiledByTap -> CortanaOrbMood.Happy
        else -> displayMood
    }
    val motion = effectiveMood.motion()
    val targetArcs = arcs ?: motion.baseArcs
    val isDark = LocalCortanaColors.current.isDark
    val ringBase = if (isDark) {
        Color(0xFF6A6A6A).copy(alpha = 0.4f)
    } else {
        Color(0xFFB0B0B0).copy(alpha = 0.35f)
    }
    val arcColor = when (effectiveMood) {
        CortanaOrbMood.Success -> CortanaColors.Success
        CortanaOrbMood.Error -> CortanaColors.Error
        else -> CortanaColors.AccentGlow
    }

    val tapEnabled = entryFinished && arcs == null &&
        (mood == CortanaOrbMood.Idle || displayMood == CortanaOrbMood.Idle) &&
        !smiledByTap
    val interactionSource = remember { MutableInteractionSource() }

    val infiniteTransition = rememberInfiniteTransition(label = "halo")
    val lengthPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = motion.lengthPulseMillis.coerceAtLeast(1),
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloLength",
    )
    val glowPhase by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = if (motion.glowPulse) 1f else 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloGlow",
    )

    val animatedArcs = remember { List(6) { AnimatablePair() } }
    val orbitSpin = remember { Animatable(0f) }
    var applyOrbitSpin by remember { mutableStateOf(false) }
    var arcsInitialized by remember { mutableStateOf(false) }

    val wantSpin = motion.spinMillis > 0

    // 连续旋转（不整圈归零，避免切表情时跳变）
    LaunchedEffect(applyOrbitSpin, motion.spinMillis) {
        if (!applyOrbitSpin || motion.spinMillis <= 0) return@LaunchedEffect
        while (true) {
            val from = orbitSpin.value
            orbitSpin.animateTo(
                targetValue = from + 360f,
                animationSpec = tween(motion.spinMillis, easing = LinearEasing),
            )
        }
    }

    // 表情 / 弧段切换：先烘焙当前世界坐标，再最短路径变形
    LaunchedEffect(effectiveMood, targetArcs) {
        val morphSpec = tween<Float>(ArcMorphMillis, easing = FastOutSlowInEasing)

        if (!arcsInitialized) {
            targetArcs.forEachIndexed { index, arc ->
                if (index >= animatedArcs.size) return@forEachIndexed
                animatedArcs[index].start.snapTo(arc.startAngle)
                animatedArcs[index].sweep.snapTo(arc.sweepAngle)
            }
            for (i in targetArcs.size until animatedArcs.size) {
                animatedArcs[i].sweep.snapTo(0f)
            }
            orbitSpin.snapTo(0f)
            applyOrbitSpin = wantSpin
            arcsInitialized = true
            return@LaunchedEffect
        }

        // 1) 把当前画面上的弧烘焙成绝对角（停转但不跳位置）
        if (applyOrbitSpin) {
            orbitSpin.stop()
            val spinNow = orbitSpin.value
            animatedArcs.forEach { pair ->
                if (abs(pair.sweep.value) > 0.5f) {
                    pair.start.snapTo((pair.start.value + spinNow).mod360())
                }
            }
            orbitSpin.snapTo(0f)
            applyOrbitSpin = false
        }

        if (wantSpin) {
            // 2a) 收到转圈目标：先收到「当前位置上的目标局部弧」，再把整体当作 orbitSpin 继续转
            val live = animatedArcs.mapNotNull { pair ->
                if (abs(pair.sweep.value) > 0.5f) pair else null
            }
            val anchor = live.maxByOrNull { it.sweep.value }?.start?.value?.mod360() ?: 0f

            // 收拢到锚点附近的目标形状（世界坐标）
            coroutineScope {
                targetArcs.forEachIndexed { index, arc ->
                    val slot = animatedArcs[index]
                    val worldStart = (anchor + arc.startAngle).mod360()
                    launch { slot.start.animateStartTo(worldStart, morphSpec) }
                    launch { slot.sweep.animateTo(arc.sweepAngle, morphSpec) }
                }
                for (i in targetArcs.size until animatedArcs.size) {
                    launch { animatedArcs[i].sweep.animateTo(0f, morphSpec) }
                }
            }

            // 转为局部坐标 + 续转
            targetArcs.forEachIndexed { index, arc ->
                animatedArcs[index].start.snapTo(arc.startAngle)
                animatedArcs[index].sweep.snapTo(arc.sweepAngle)
            }
            for (i in targetArcs.size until animatedArcs.size) {
                animatedArcs[i].sweep.snapTo(0f)
            }
            orbitSpin.snapTo(anchor)
            applyOrbitSpin = true
        } else {
            // 2b) 静态表情：从当前弧连续分裂/滑动到目标
            seedArcsForSplit(animatedArcs, targetArcs.size)
            coroutineScope {
                targetArcs.forEachIndexed { index, arc ->
                    val slot = animatedArcs[index]
                    launch { slot.start.animateStartTo(arc.startAngle, morphSpec) }
                    launch { slot.sweep.animateTo(arc.sweepAngle, morphSpec) }
                }
                for (i in targetArcs.size until animatedArcs.size) {
                    launch { animatedArcs[i].sweep.animateTo(0f, morphSpec) }
                }
            }
            animatedArcs.forEach { pair ->
                if (abs(pair.sweep.value) > 0.5f) {
                    pair.start.snapTo(pair.start.value.mod360())
                }
            }
        }
    }

    val pulse = if (motion.lengthPulse > 0f) {
        1f + (lengthPhase * 2f - 1f) * motion.lengthPulse
    } else {
        1f
    }
    val spinDraw = if (applyOrbitSpin) orbitSpin.value else 0f
    val drawnArcs = animatedArcs.map { HaloArc(it.start.value, it.sweep.value) }

    Canvas(
        modifier = modifier
            .size(size)
            .clickable(
                enabled = tapEnabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "变成笑脸",
                onClick = { tapSmileNonce += 1 },
            ),
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f * 0.78f
        val stroke = (this.size.minDimension * 0.055f).coerceIn(3.5.dp.toPx(), 6.5.dp.toPx())

        drawCircle(
            color = ringBase,
            radius = radius,
            center = center,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        for (arc in drawnArcs) {
            val sweep = arc.sweepAngle
            if (abs(sweep) < 0.5f) continue
            val pulsedSweep = (sweep * pulse).coerceIn(4f, 350f)
            val startAdjusted = arc.startAngle + spinDraw - (pulsedSweep - sweep) / 2f
            drawGlowingArc(
                color = arcColor,
                startAngle = startAdjusted,
                sweepAngle = pulsedSweep,
                center = center,
                radius = radius,
                strokeWidth = stroke,
                glowStrength = glowPhase,
            )
        }
    }
}

/**
 * 若目标弧数更多，沿当前最长弧撒出新弧起点，再变形，避免从 0 突然冒出来。
 */
private suspend fun seedArcsForSplit(slots: List<AnimatablePair>, targetCount: Int) {
    val live = slots.filter { abs(it.sweep.value) > 0.5f }
    if (live.isEmpty() || targetCount <= live.size) return

    val source = live.maxBy { it.sweep.value }
    val base = source.start.value
    val sweep = source.sweep.value
    val piece = (sweep / targetCount).coerceAtLeast(12f)
    for (i in 0 until targetCount) {
        if (i >= slots.size) break
        val t = (i + 0.5f) / targetCount
        slots[i].start.snapTo((base + sweep * t - piece / 2f).mod360())
        slots[i].sweep.snapTo(piece)
    }
    for (i in targetCount until slots.size) {
        slots[i].sweep.snapTo(0f)
    }
}

private class AnimatablePair {
    val start = Animatable(0f)
    val sweep = Animatable(0f)
}

private fun Float.mod360(): Float {
    var a = this % 360f
    if (a < 0f) a += 360f
    return a
}

/** 沿最短圆弧动画到目标角，避免跨 360° 时绕远路。 */
private suspend fun Animatable<Float, AnimationVector1D>.animateStartTo(
    targetDegrees: Float,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Float>,
) {
    val from = value
    val to = targetDegrees.mod360()
    var delta = to - from.mod360()
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    animateTo(from + delta, animationSpec)
}

/**
 * 单色亮弧 + 小范围泛光。
 * 泛光用 [BlendMode.Plus] 提亮，避免半透明蓝叠在灰环上发成深蓝描边。
 */
private fun DrawScope.drawGlowingArc(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    center: Offset,
    radius: Float,
    strokeWidth: Float,
    glowStrength: Float,
) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    drawArc(
        color = color.copy(alpha = 0.22f * glowStrength),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth * 1.7f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
    drawArc(
        color = color.copy(alpha = 0.35f * glowStrength),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth * 1.3f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
    drawArc(
        color = color.copy(alpha = 1f),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth * 1.08f, cap = StrokeCap.Round),
    )
}
